package com.flowdesk.mcp.asset;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 直接发原始 JSON-RPC / HTTP 报文的测试客户端（FD-0014-R1）。
 *
 * <p>SDK 客户端（{@link AssetMcpTestClient}）隐藏了会话标识与 HTTP 方法，
 * 而 FD-0014-R1 要验收的正是这两件事：</p>
 * <ul>
 *   <li>{@code initialize} 响应里的 {@code capabilities} 到底声明了什么（原始 JSON）；</li>
 *   <li>客户端能否用 {@code DELETE} 结束自己的 MCP 会话，以及会话结束后是不是真的查不到了。</li>
 * </ul>
 *
 * <p>这里只用 JDK 的 {@link HttpClient}，不引入任何测试专用框架，
 * 断言的是服务端真实的 HTTP 状态码、响应头与 JSON 报文。</p>
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
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(this.endpoint))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (sessionId != null) {
            builder.header(SESSION_HEADER, sessionId);
        }
        return send(builder.build());
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
}
