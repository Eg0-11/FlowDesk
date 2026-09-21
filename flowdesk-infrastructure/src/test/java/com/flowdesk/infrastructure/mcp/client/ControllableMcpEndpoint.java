package com.flowdesk.infrastructure.mcp.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 可控的 Streamable HTTP 测试端点（FD-0016）。
 *
 * <p>它是一个真实的本机 HTTP 服务（{@code com.sun.net.httpserver}，随机端口、只绑回环），
 * 说的是 MCP Streamable HTTP 的最小必要子集：</p>
 * <ul>
 *   <li>{@code POST /mcp} + {@code initialize} → 200 + {@code application/json} + {@code Mcp-Session-Id}；</li>
 *   <li>{@code POST /mcp} + 通知（{@code notifications/initialized}）→ 202，无响应体；</li>
 *   <li>{@code POST /mcp} + {@code tools/call} → 200 + {@code text/event-stream} 或 {@code application/json}
 *       （形状与两个真实服务实测一致：{@code content} 里恰好一个 {@code type=text}），
 *       会话标识缺失或不匹配 → 404；</li>
 *   <li>{@code DELETE /mcp} → 200（会话清理）；{@code GET /mcp} → 405。</li>
 * </ul>
 *
 * <p><b>为什么不用 SDK 的服务端</b>：本模块的职责是<b>客户端</b>，
 * 不允许内嵌 MCP Server；而且非法响应（缺字段、{@code source:null}、编号错配、未知枚举、
 * 越界数值、非文本内容…）必须能被<b>故意造出来</b>，SDK 服务端只会按类型序列化，
 * 造不出来。这里因此手写协议子集，帧格式逐字对照两个真实服务实测结果。</p>
 *
 * <p>它同时是「证据端点」：记录每个请求的方法、被调用的工具名、会话标识与各类计数，
 * 测试据此断言「非法输入零请求」「固定工具名」「会话真的被释放」。</p>
 */
final class ControllableMcpEndpoint implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PROTOCOL_VERSION = "2025-06-18";

    /** 请求头名（与 SDK 一致）。 */
    private static final String SESSION_HEADER = "Mcp-Session-Id";

    private final HttpServer server;

    private final ExecutorService executor;

    private final String sessionIdPrefix = UUID.randomUUID().toString();

    private final AtomicInteger postCount = new AtomicInteger();

    private final AtomicInteger initializeCount = new AtomicInteger();

    private final AtomicInteger toolCallCount = new AtomicInteger();

    private final AtomicInteger deleteCount = new AtomicInteger();

    private final List<String> calledTools = Collections.synchronizedList(new ArrayList<>());

    private final List<String> sessionsSeen = Collections.synchronizedList(new ArrayList<>());

    /** 仍然活跃的会话（initialize 建立、DELETE 结束）：用来证明「没有悬挂会话」。 */
    private final Set<String> liveSessions = ConcurrentHashMap.newKeySet();

    private volatile Reply toolReply = Reply.toolText(RESULT_TEXT_STUB);

    private volatile int deleteStatus = 200;

    private volatile boolean requireSession = true;

    /** initialize 响应里的 serverInfo.name（可注入哨兵：SDK 的 INFO 日志会原样打印它）。 */
    private volatile String serverName = "controllable-test-endpoint";

    /** initialize 响应里的 instructions（可注入哨兵：SDK 的 INFO 日志会原样打印它）。 */
    private volatile String instructions;

    private ControllableMcpEndpoint(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    /** 默认的工具成功载荷（调用方通常会用 {@link #respondWithToolText(String)} 覆盖）。 */
    private static final String RESULT_TEXT_STUB = "{\"assetId\":\"AST-900001\",\"assetType\":\"SERVER\","
            + "\"status\":\"IN_SERVICE\",\"source\":\"DEMO\"}";

    /**
     * 启动一个随机端口的端点。
     *
     * @return 已启动的端点（调用方负责 {@link #close()}）
     * @throws IOException 端口绑定失败
     */
    static ControllableMcpEndpoint start() throws IOException {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ExecutorService executor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "controllable-mcp-endpoint");
            thread.setDaemon(true);
            return thread;
        });
        ControllableMcpEndpoint endpoint = new ControllableMcpEndpoint(server, executor);
        server.createContext("/mcp", endpoint::handle);
        server.setExecutor(executor);
        server.start();
        return endpoint;
    }

    /**
     * @return {@code http://127.0.0.1:<port>}（不含 {@code /mcp}）
     */
    String baseUrl() {
        return "http://127.0.0.1:" + this.server.getAddress().getPort();
    }

    /** @return 已收到的 POST 次数 */
    int postCount() {
        return this.postCount.get();
    }

    /** @return 已收到的 initialize 次数 */
    int initializeCount() {
        return this.initializeCount.get();
    }

    /** @return 已收到的 tools/call 次数 */
    int toolCallCount() {
        return this.toolCallCount.get();
    }

    /** @return 已收到的 DELETE 次数（会话释放证据） */
    int deleteCount() {
        return this.deleteCount.get();
    }

    /** @return 收到过的工具名（固定契约证据） */
    List<String> calledTools() {
        return List.copyOf(this.calledTools);
    }

    /** @return 收到过的会话标识（去重后的快照） */
    List<String> sessionsSeen() {
        return List.copyOf(this.sessionsSeen);
    }

    /**
     * @return 当前仍然活跃的会话标识（客户端释放会话后应当为空）
     */
    Set<String> liveSessions() {
        return Set.copyOf(this.liveSessions);
    }

    // ---------- 行为编程 ----------

    /**
     * 让下一次（及之后所有）{@code tools/call} 返回一个成功的文本载荷（SSE 传输）。
     *
     * @param payloadJson 工具载荷（单个 text content 的内容）
     */
    void respondWithToolText(String payloadJson) {
        this.toolReply = Reply.toolText(payloadJson);
    }

    /**
     * 与 {@link #respondWithToolText(String)} 相同，但用普通 JSON 而不是 SSE 承载。
     *
     * @param payloadJson 工具载荷
     */
    void respondWithToolTextAsJson(String payloadJson) {
        this.toolReply = Reply.toolTextAsJson(payloadJson);
    }

    /**
     * 让 {@code tools/call} 返回 {@code isError=true} 的固定失败载荷。
     *
     * @param payloadJson 失败载荷（例如 {@code {"error":"ASSET_SOURCE_UNAVAILABLE","message":"…"}}）
     */
    void respondWithToolError(String payloadJson) {
        this.toolReply = Reply.toolError(payloadJson);
    }

    /**
     * 让 {@code tools/call} 返回一个 JSON-RPC 错误（协议层拒绝）。
     *
     * @param code    错误码
     * @param message 错误文案
     */
    void respondWithJsonRpcError(int code, String message) {
        this.toolReply = Reply.jsonRpcError(code, message);
    }

    /**
     * 让 {@code tools/call} 返回任意 HTTP 状态与响应体（用于构造形状不合法的响应）。
     *
     * @param status      HTTP 状态
     * @param contentType Content-Type
     * @param body        响应体
     */
    void respondWithRaw(int status, String contentType, String body) {
        this.toolReply = new Reply(status, contentType, id -> body, 0L);
    }

    /**
     * 自定义 {@code result} 节点的形状（用于 content 数量、结构化内容等非法形状）。
     *
     * @param resultNode JSON-RPC 的 result 节点
     */
    void respondWithToolResultNode(ObjectNode resultNode) {
        this.toolReply = Reply.resultNode(resultNode);
    }

    /**
     * 让 {@code tools/call} 一直不回应（客户端必须在自己配置的上界内超时结束）。
     */
    void hang() {
        this.toolReply = Reply.hang();
    }

    /**
     * 在 {@code initialize} 响应里注入哨兵文本。
     *
     * <p>SDK 的 {@code LifecycleInitializer} 会在 INFO 级别把 {@code serverInfo} 与
     * {@code instructions} 原样打印出来，因此这两个字段是把「远端原文会不会进日志」
     * 变成可断言事实的最直接位置。</p>
     *
     * @param serverName   响应里的 {@code serverInfo.name}
     * @param instructions 响应里的 {@code instructions}（{@code null} 表示不返回该字段）
     */
    void respondWithInitializeMetadata(String serverName, String instructions) {
        this.serverName = serverName;
        this.instructions = instructions;
    }

    /**
     * @param status DELETE 的响应状态（默认 200）
     */
    void respondToDeleteWith(int status) {
        this.deleteStatus = status;
    }

    /**
     * @param requireSession 是否要求 {@code tools/call} 带正确会话标识（默认 true）
     */
    void requireSession(boolean requireSession) {
        this.requireSession = requireSession;
    }

    @Override
    public void close() {
        this.server.stop(0);
        this.executor.shutdownNow();
    }

    // ---------- HTTP 处理 ----------

    private void handle(HttpExchange exchange) throws IOException {
        try {
            switch (exchange.getRequestMethod()) {
                case "POST" -> handlePost(exchange);
                case "DELETE" -> handleDelete(exchange);
                default -> {
                    // Streamable HTTP 允许服务端不提供 GET 流
                    exchange.sendResponseHeaders(405, -1);
                }
            }
        }
        catch (IOException | RuntimeException ex) {
            // 端点自身出错时不让测试线程挂死
            try {
                exchange.sendResponseHeaders(500, -1);
            }
            catch (IOException ignored) {
                // 连接可能已经断了
            }
        }
        finally {
            exchange.close();
        }
    }

    private void handlePost(HttpExchange exchange) throws IOException {
        this.postCount.incrementAndGet();
        String session = exchange.getRequestHeaders().getFirst(SESSION_HEADER);
        if (session != null) {
            this.sessionsSeen.add(session);
        }

        String body = readBody(exchange);
        JsonNode message = readJson(body);
        if (message == null || !message.isObject() || !message.hasNonNull("method")) {
            writeJson(exchange, 400, "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,"
                    + "\"message\":\"Parse error\"}}");
            return;
        }

        String method = message.path("method").asText();
        if ("initialize".equals(method)) {
            this.initializeCount.incrementAndGet();
            String issued = this.sessionIdPrefix + "-" + this.initializeCount.get();
            this.liveSessions.add(issued);
            ObjectNode result = MAPPER.createObjectNode();
            result.put("protocolVersion", PROTOCOL_VERSION);
            ObjectNode capabilities = MAPPER.createObjectNode();
            capabilities.set("tools", MAPPER.createObjectNode());
            result.set("capabilities", capabilities);
            ObjectNode serverInfo = MAPPER.createObjectNode();
            serverInfo.put("name", this.serverName);
            serverInfo.put("version", "1.0.0");
            result.set("serverInfo", serverInfo);
            if (this.instructions != null) {
                result.put("instructions", this.instructions);
            }
            ObjectNode envelope = envelope(message.get("id"), "result", result);
            exchange.getResponseHeaders().add(SESSION_HEADER, issued);
            writeJson(exchange, 200, envelope.toString());
            return;
        }

        if (method.startsWith("notifications/")) {
            exchange.sendResponseHeaders(202, -1);
            return;
        }

        if (this.requireSession && (session == null || !this.liveSessions.contains(session))) {
            writeJson(exchange, 404, "{\"jsonrpc\":\"2.0\",\"id\":" + idOf(message)
                    + ",\"error\":{\"code\":-32600,\"message\":\"Invalid request\"}}");
            return;
        }

        if (!"tools/call".equals(method)) {
            writeJson(exchange, 200, envelope(message.get("id"), "error", errorNode(-32601, "Method not found"))
                    .toString());
            return;
        }

        this.toolCallCount.incrementAndGet();
        this.calledTools.add(message.path("params").path("name").asText());
        Reply reply = this.toolReply;
        if (reply.delayMillis() > 0) {
            try {
                Thread.sleep(reply.delayMillis());
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (reply.hanging()) {
            // 挂起：不发任何响应（客户端必须在自己配置的上界内超时结束）
            return;
        }
        writeRaw(exchange, reply.status(), reply.contentType(), reply.body(message.get("id")));
    }

    private void handleDelete(HttpExchange exchange) throws IOException {
        this.deleteCount.incrementAndGet();
        String session = exchange.getRequestHeaders().getFirst(SESSION_HEADER);
        boolean known = session != null && this.liveSessions.remove(session);
        if (this.requireSession && !known) {
            writeJson(exchange, 404, "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,"
                    + "\"message\":\"Invalid request\"}}");
            return;
        }
        writeRaw(exchange, this.deleteStatus, "application/json", "");
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static JsonNode readJson(String body) {
        try {
            return MAPPER.readTree(body);
        }
        catch (Exception ex) {
            return null;
        }
    }

    private static String idOf(JsonNode message) {
        JsonNode id = message.get("id");
        return id == null || id.isNull() ? "null" : id.toString();
    }

    private static ObjectNode envelope(JsonNode id, String field, JsonNode payload) {
        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("jsonrpc", "2.0");
        envelope.set("id", id == null ? MAPPER.nullNode() : id);
        envelope.set(field, payload);
        return envelope;
    }

    private static ObjectNode errorNode(int code, String message) {
        ObjectNode error = MAPPER.createObjectNode();
        error.put("code", code);
        error.put("message", message);
        return error;
    }

    private static void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        writeRaw(exchange, status, "application/json; charset=UTF-8", body);
    }

    private static void writeRaw(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        if (bytes.length == 0) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    /** 一次 {@code tools/call} 的编程响应；响应体按请求的 JSON-RPC {@code id} 生成（必须回显同一个 id）。 */
    private record Reply(int status, String contentType, Function<JsonNode, String> bodyFactory, long delayMillis,
            boolean hanging) {

        Reply(int status, String contentType, Function<JsonNode, String> bodyFactory, long delayMillis) {
            this(status, contentType, bodyFactory, delayMillis, false);
        }

        static Reply toolText(String payloadJson) {
            return new Reply(200, "text/event-stream; charset=UTF-8",
                    id -> sse(toolResultEnvelope(id, payloadJson, false)), 0L);
        }

        static Reply toolTextAsJson(String payloadJson) {
            return new Reply(200, "application/json; charset=UTF-8",
                    id -> toolResultEnvelope(id, payloadJson, false), 0L);
        }

        static Reply toolError(String payloadJson) {
            return new Reply(200, "text/event-stream; charset=UTF-8",
                    id -> sse(toolResultEnvelope(id, payloadJson, true)), 0L);
        }

        static Reply resultNode(ObjectNode resultNode) {
            return new Reply(200, "application/json; charset=UTF-8",
                    id -> envelope(id, "result", resultNode).toString(), 0L);
        }

        static Reply jsonRpcError(int code, String message) {
            return new Reply(200, "application/json; charset=UTF-8",
                    id -> envelope(id, "error", errorNode(code, message)).toString(), 0L);
        }

        static Reply hang() {
            // 挂住连接不给响应：客户端必须在自己配置的上界内超时结束
            return new Reply(200, "application/json; charset=UTF-8", id -> "",
                    Duration.ofSeconds(30).toMillis(), true);
        }

        String body(JsonNode id) {
            return this.bodyFactory.apply(id);
        }

        private static String sse(String data) {
            return "id:" + UUID.randomUUID() + "\nevent:message\ndata:" + data + "\n\n";
        }

        private static String toolResultEnvelope(JsonNode id, String payloadJson, boolean isError) {
            ObjectNode text = MAPPER.createObjectNode();
            text.put("type", "text");
            text.put("text", payloadJson);
            ArrayNode content = MAPPER.createArrayNode().add(text);
            ObjectNode result = MAPPER.createObjectNode();
            result.set("content", content);
            result.put("isError", isError);
            return envelope(id, "result", result).toString();
        }
    }
}
