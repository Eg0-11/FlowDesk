package com.flowdesk.infrastructure.knowledge.rerank;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 测试用的合成重排端点（RAG 6/6）。
 *
 * <p>它不是 DashScope，也不会访问网络：只在本地回环端口上按脚本应答，并把收到的每一个请求
 * （方法、Authorization、Content-Type、请求体）原样记录下来，供测试断言<b>真实发出的报文</b>
 * —— 包括「有没有多余字段」与「凭证怎么带的」。</p>
 *
 * <p>用它而不是真实上游有两条理由：本阶段不允许调用真实付费接口；而且只有合成端点才能确定性
 * 地构造 429、5xx、超时与畸形响应。</p>
 */
final class SyntheticRerankEndpoint {

    private final HttpServer server;

    private final List<CapturedRequest> requests = Collections.synchronizedList(new ArrayList<>());

    private volatile int status = 200;

    private volatile String responseBody = "{\"results\":[]}";

    private volatile long delayMillis = 0L;

    private SyntheticRerankEndpoint() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/reranks", this::handle);
        this.server.start();
    }

    /**
     * @return 已启动的端点
     * @throws IOException 端口绑定失败
     */
    static SyntheticRerankEndpoint start() throws IOException {
        return new SyntheticRerankEndpoint();
    }

    /**
     * @return 可直接作为重排 Endpoint 使用的地址
     */
    URI uri() {
        return URI.create("http://127.0.0.1:" + this.server.getAddress().getPort() + "/reranks");
    }

    /**
     * @param status       响应状态码
     * @param responseBody 响应体
     */
    void willReturn(int status, String responseBody) {
        this.status = status;
        this.responseBody = responseBody;
    }

    /**
     * @param delayMillis 响应前的延迟（用于构造读取超时）
     */
    void willDelay(long delayMillis) {
        this.delayMillis = delayMillis;
    }

    void clearRequests() {
        this.requests.clear();
    }

    List<CapturedRequest> requests() {
        return List.copyOf(this.requests);
    }

    int calls() {
        return this.requests.size();
    }

    void stop() {
        this.server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        this.requests.add(new CapturedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                body));

        if (this.delayMillis > 0) {
            try {
                Thread.sleep(this.delayMillis);
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }

        byte[] payload = this.responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(this.status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    /**
     * 一次被记录下来的请求。
     *
     * @param method        HTTP 方法
     * @param authorization Authorization 头
     * @param contentType   Content-Type 头
     * @param body          请求体原文
     */
    record CapturedRequest(String method, String authorization, String contentType, String body) {
    }
}
