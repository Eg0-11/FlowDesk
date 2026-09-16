package com.flowdesk.mcp.asset;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 直接发原始 JSON-RPC / HTTP 报文的测试客户端（FD-0014-R1 / FD-0014-R2）。
 *
 * <p>SDK 客户端（{@link AssetMcpTestClient}）隐藏了会话标识、HTTP 方法、状态码与原始报文，
 * 而这里要验收的恰恰是这些：会话标识、{@code DELETE}、以及「错误响应到底长什么样」。</p>
 *
 * <p>{@link #postBounded} 是本类存在的另一个理由：MCP 的请求应答可能是
 * {@code text/event-stream}，而「流有没有结束」不能靠请求超时判断（超时只管到响应头），
 * 必须真的去读并观察 EOF。因此它用一个有界的读取窗口来判断，
 * 读不到 EOF 就如实报告 —— 这正是 FD-0014-R2 要钉住的行为。</p>
 */
final class AssetMcpRawClient {

    /** 与 {@code spring.ai.mcp.server.streamable-http.mcp-endpoint} 一致。 */
    static final String MCP_ENDPOINT = "/mcp";

    /** 会话标识响应头 / 请求头（MCP Streamable HTTP）。 */
    static final String SESSION_HEADER = "Mcp-Session-Id";

    /** {@code initialize} 请求体（协议版本与 SDK 客户端一致）。 */
    static final String INITIALIZE_BODY = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",\
            "capabilities":{},"clientInfo":{"name":"raw-test-client","version":"1.0.0"}}}""";

    /** 握手完成通知（不需要响应）。 */
    static final String INITIALIZED_NOTIFICATION = """
            {"jsonrpc":"2.0","method":"notifications/initialized"}""";

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final String endpoint;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * @param port 服务端口
     */
    AssetMcpRawClient(int port) {
        this.endpoint = "http://127.0.0.1:" + port + MCP_ENDPOINT;
    }

    /**
     * 发一个不带会话标识的 POST。
     *
     * @param body JSON-RPC 报文
     * @return 响应
     * @throws Exception 请求失败
     */
    RawResponse post(String body) throws Exception {
        return post(body, null);
    }

    /**
     * 发一个 POST。
     *
     * @param body      JSON-RPC 报文
     * @param sessionId 会话标识；{@code null} 表示不带该头
     * @return 响应
     * @throws Exception 请求失败
     */
    RawResponse post(String body, String sessionId) throws Exception {
        return send(request(body, sessionId).build());
    }

    /**
     * 发一个 POST，并在有界窗口内观察响应流是否结束。
     *
     * @param body      JSON-RPC 报文
     * @param sessionId 会话标识；{@code null} 表示不带该头
     * @param window    观察 EOF 的时间窗口
     * @return 响应（含 EOF 判断）
     * @throws Exception 请求失败
     */
    BoundedResponse postBounded(String body, String sessionId, Duration window) throws Exception {
        HttpResponse<InputStream> response =
                this.httpClient.send(request(body, sessionId).build(), HttpResponse.BodyHandlers.ofInputStream());

        InputStream stream = response.body();
        StringBuilder received = new StringBuilder();
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicBoolean ended = new AtomicBoolean(false);

        Thread reader = new Thread(() -> {
            try (stream) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    synchronized (received) {
                        received.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    }
                }
                ended.set(true);
            }
            catch (Exception ex) {
                failure.set(ex.getClass().getName());
            }
        });
        reader.setDaemon(true);
        reader.start();

        long deadline = System.nanoTime() + window.toNanos();
        while (System.nanoTime() < deadline && !ended.get()) {
            Thread.sleep(10);
        }

        String text;
        synchronized (received) {
            text = received.toString();
        }
        return new BoundedResponse(response.statusCode(),
                response.headers().firstValue("Content-Type").orElse(null),
                text, ended.get(), failure.get());
    }

    /**
     * 结束会话（MCP 的 {@code DELETE /mcp}）。
     *
     * @param sessionId 会话标识
     * @return 响应
     * @throws Exception 请求失败
     */
    RawResponse delete(String sessionId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(this.endpoint))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json, text/event-stream")
                .header(SESSION_HEADER, sessionId)
                .DELETE()
                .build();
        return send(request);
    }

    /** 用 {@link #INITIALIZE_BODY} 建一个会话。 */
    RawResponse initialize() throws Exception {
        return post(INITIALIZE_BODY);
    }

    private HttpRequest.Builder request(String body, String sessionId) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(this.endpoint))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (sessionId != null) {
            builder.header(SESSION_HEADER, sessionId);
        }
        return builder;
    }

    private RawResponse send(HttpRequest request) throws Exception {
        HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return new RawResponse(response.statusCode(), response.body(),
                response.headers().firstValue(SESSION_HEADER).orElse(null));
    }

    /**
     * 原始响应。
     *
     * @param status    HTTP 状态码
     * @param body      响应体
     * @param sessionId 响应头里的会话标识；没有则为 {@code null}
     */
    record RawResponse(int status, String body, String sessionId) {
    }

    /**
     * 带 EOF 判断的原始响应。
     *
     * @param status      HTTP 状态码
     * @param contentType 响应内容类型
     * @param body        在观察窗口内读到的内容
     * @param ended       响应流是否在窗口内结束（EOF）
     * @param readFailure 读取失败时的异常类名；没有则为 {@code null}
     */
    record BoundedResponse(int status, String contentType, String body, boolean ended, String readFailure) {
    }
}
