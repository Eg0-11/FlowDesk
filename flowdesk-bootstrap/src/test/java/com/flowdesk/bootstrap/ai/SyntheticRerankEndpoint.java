package com.flowdesk.bootstrap.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 测试用的合成重排端点（FD-0013-R1）。
 *
 * <p>它不是 DashScope，也不会访问网络：只在本地回环端口上按脚本应答，并记录收到的请求。
 * 与 infrastructure 的同类替身相比，这里的用途不同 —— 它配合<b>真实的</b>
 * {@code DashScopeKnowledgeRerankAdapter} 跑完整链路，用来证明
 * 「供应商返回非法下标 → 整次请求失败、不返回引用、也不调用 DeepSeek」。</p>
 *
 * <p>回环明文 HTTP 是 {@code RerankEndpointPolicy} 唯一放行的非 HTTPS 场景，
 * 本类同时也是那条边界的正向验证。</p>
 */
final class SyntheticRerankEndpoint {

    private final HttpServer server;

    private final List<String> authorizationHeaders = Collections.synchronizedList(new ArrayList<>());

    private volatile String responseBody = "{\"results\":[]}";

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
     * @return 可直接作为重排 Endpoint 使用的本机回环地址
     */
    URI uri() {
        return URI.create("http://127.0.0.1:" + this.server.getAddress().getPort() + "/reranks");
    }

    /**
     * @param responseBody 下一次响应的响应体
     */
    void willReturn(String responseBody) {
        this.responseBody = responseBody;
    }

    /**
     * @return 收到的 Authorization 头（用于确认凭证确实按 Bearer 发送）
     */
    List<String> authorizationHeaders() {
        return List.copyOf(this.authorizationHeaders);
    }

    int calls() {
        return this.authorizationHeaders.size();
    }

    void clearRequests() {
        this.authorizationHeaders.clear();
    }

    void stop() {
        this.server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        this.authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));

        byte[] payload = this.responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }
}
