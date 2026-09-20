package com.flowdesk.mcp.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowdesk.mcp.monitoring.snapshot.MonitoringSnapshotSource;
import com.flowdesk.mcp.monitoring.snapshot.MonitoringSnapshot;
import com.flowdesk.mcp.monitoring.snapshot.SnapshotOrigin;
import com.flowdesk.mcp.monitoring.snapshot.DemoSnapshotSource;
import com.flowdesk.mcp.monitoring.tool.MonitoringSnapshotGetTool;
import com.flowdesk.mcp.monitoring.transport.McpRequestGateFilter;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * MCP 传输层错误与响应流收口的验收测试（FD-0015）。
 *
 * <p>修复前的实测行为（FD-0014-R2 阶段在资产 MCP 上实测；本模块复用同一基线，见 ADR 0012）：</p>
 * <ul>
 *   <li>畸形 JSON-RPC 报文 → 400，响应体是 {@code McpError}（{@code RuntimeException}）的
 *       Throwable 序列化，含 {@code stackTrace}、SDK 与业务类名、文件名与行号；</li>
 *   <li>{@code tools/call} 的 {@code arguments} 不是对象 → 500 且响应体为空；</li>
 *   <li>未实现的方法 → 错误只写进 {@code text/event-stream}，而该流<b>永不结束</b>，
 *       服务端一直挂着一个活跃请求，关停时被 Surefire 强杀。</li>
 * </ul>
 *
 * <p>现在的契约：错误响应必须是**固定、可解析、不含任何内部信息**的 JSON-RPC 错误，
 * 且响应流必须在有界时间内结束。本类同时断言「工具与监控数据源都没有被调用」，
 * 用计数数据源 + 日志两条互补证据。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "flowdesk.monitoring.source.mode=demo"
})
class MonitoringMcpTransportErrorTests {

    /** 只要有一个能跑完的观察窗口就够了：修复后这些响应都是普通 JSON，EOF 立刻到达。 */
    private static final Duration EOF_WINDOW = Duration.ofSeconds(2);

    /** 绝不允许出现在任何错误响应里的内部信息片段。 */
    private static final List<String> FORBIDDEN_FRAGMENTS = List.of(
            "stackTrace",
            "java.lang.",
            "io.modelcontextprotocol",
            "com.flowdesk",
            "org.springframework",
            "Exception",
            "at [Source",
            "Redacted",
            "file:",
            "C:/",
            "C:\\",
            "secret",
            "password",
            "apiKey",
            "monitoring-source.properties");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private CountingSnapshotSource snapshotSource;

    private final ListAppender<ILoggingEvent> toolLog = new ListAppender<>();

    @BeforeEach
    void attachToolLog() {
        this.snapshotSource.reset();
        this.toolLog.start();
        toolLogger().addAppender(this.toolLog);
    }

    @AfterEach
    void detachToolLog() {
        toolLogger().detachAppender(this.toolLog);
        this.toolLog.stop();
    }

    // ---------- 1. 畸形报文：固定、安全的协议错误 ----------

    @Test
    void aMalformedBodyGetsAFixedParseErrorWithoutAnyInternalDetail() throws Exception {
        MonitoringMcpRawClient.BoundedResponse response = new MonitoringMcpRawClient(this.port)
                .postBounded("{\"assetId\":\"AST-900001\",", null, EOF_WINDOW);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.contentType()).startsWith("application/json");
        assertThat(response.ended()).as("错误响应必须是有界的（普通 JSON，不是流）").isTrue();

        JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.path("error").path("code").asInt()).isEqualTo(-32700);
        assertThat(body.path("error").path("message").asText()).isEqualTo("Parse error");
        assertThat(body.path("id").isNull()).as("解析失败时标识不可知").isTrue();

        assertNoInternalDetail(response.body());
        assertThat(response.body())
                .as("拒绝时不得回显请求内容")
                .doesNotContain("AST-900001");
    }

    @Test
    void theParseErrorBodyIsStableAcrossCalls() throws Exception {
        MonitoringMcpRawClient raw = new MonitoringMcpRawClient(this.port);

        String first = raw.post("{not json at all", null).body();
        String second = raw.post("{not json at all", null).body();

        assertThat(first).as("同样的输入必须得到逐字节相同的响应").isEqualTo(second);
    }

    @Test
    void aValidJsonBodyThatIsNotAJsonRpcRequestGetsInvalidRequest() throws Exception {
        MonitoringMcpRawClient raw = new MonitoringMcpRawClient(this.port);

        List<String> bodies = List.of(
                "[{\"jsonrpc\":\"2.0\",\"id\":31,\"method\":\"ping\"}]",
                "\"AST-900001\"",
                "12345",
                "null",
                "{\"jsonrpc\":\"2.0\",\"id\":32}",
                "{\"jsonrpc\":\"1.0\",\"id\":33,\"method\":\"ping\"}",
                "{\"id\":34,\"method\":42}");

        for (String body : bodies) {
            MonitoringMcpRawClient.RawResponse response = raw.post(body, null);

            assertThat(response.status()).as("body=%s", body).isEqualTo(400);
            JsonNode payload = MAPPER.readTree(response.body());
            assertThat(payload.path("error").path("code").asInt()).as("body=%s", body).isEqualTo(-32600);
            assertThat(payload.path("error").path("message").asText())
                    .as("body=%s", body)
                    .isEqualTo("Invalid request");
            assertNoInternalDetail(response.body());
        }
    }

    @Test
    void trailingContentAfterTheRequestBodyIsAParseError() throws Exception {
        MonitoringMcpRawClient raw = new MonitoringMcpRawClient(this.port);

        MonitoringMcpRawClient.RawResponse response = raw.post(
                "{\"jsonrpc\":\"2.0\",\"id\":35,\"method\":\"ping\"}{\"jsonrpc\":\"2.0\"}", null);

        assertThat(response.status()).isEqualTo(400);
        JsonNode payload = MAPPER.readTree(response.body());
        assertThat(payload.path("error").path("code").asInt())
                .as("两段 JSON 不是一个合法报文，按解析失败处理")
                .isEqualTo(-32700);
        assertNoInternalDetail(response.body());
    }

    @Test
    void anErrorFromTheTransportItselfStaysSafe() throws Exception {
        // 没有会话标识的请求由 SDK 传输层自己拒绝（不经过闸门），此前这个 400 会带整段堆栈
        MonitoringMcpRawClient.RawResponse missingSession = new MonitoringMcpRawClient(this.port)
                .post("{\"jsonrpc\":\"2.0\",\"id\":36,\"method\":\"tools/list\"}", null);
        MonitoringMcpRawClient.RawResponse unknownSession = new MonitoringMcpRawClient(this.port)
                .post("{\"jsonrpc\":\"2.0\",\"id\":37,\"method\":\"tools/list\"}", "no-such-session");

        for (MonitoringMcpRawClient.RawResponse response : List.of(missingSession, unknownSession)) {
            assertThat(response.status()).isIn(400, 404);
            assertThat(response.body()).isNotBlank();
            assertNoInternalDetail(response.body());

            JsonNode payload = MAPPER.readTree(response.body());
            assertThat(payload.path("message").isTextual())
                    .as("响应仍是可解析的固定错误对象")
                    .isTrue();
            assertThat(payload.path("message").asText()).isEqualTo("Invalid request");
            assertThat(payload.has("stackTrace")).isFalse();
        }
    }

    // ---------- 2. tools/call 的 arguments 不是对象 ----------

    @Test
    void aNonObjectArgumentsPayloadIsRejectedAsInvalidParamsWithoutTouchingTheTool() throws Exception {
        MonitoringMcpRawClient raw = new MonitoringMcpRawClient(this.port);
        String sessionId = raw.initialize().sessionId();

        List<String> argumentValues = List.of(
                "\"AST-900001\"",
                "[\"AST-900001\"]",
                "123456",
                "true",
                "null");

        for (String arguments : argumentValues) {
            MonitoringMcpRawClient.BoundedResponse response = raw.postBounded("""
                    {"jsonrpc":"2.0","id":41,"method":"tools/call",\
                    "params":{"name":"monitoring_snapshot_get","arguments":%s}}""".formatted(arguments),
                    sessionId, EOF_WINDOW);

            assertThat(response.status()).as("arguments=%s：不得再是 500", arguments).isEqualTo(200);
            assertThat(response.ended()).as("arguments=%s：响应必须有界", arguments).isTrue();

            JsonNode payload = MAPPER.readTree(response.body());
            assertThat(payload.path("error").path("code").asInt()).as("arguments=%s", arguments).isEqualTo(-32602);
            assertThat(payload.path("error").path("message").asText())
                    .as("arguments=%s", arguments)
                    .isEqualTo("Invalid params: arguments must be a JSON object");
            assertThat(payload.path("id").asInt()).as("标识必须被回显").isEqualTo(41);
            assertNoInternalDetail(response.body());
        }

        assertThat(this.snapshotSource.invocations()).as("监控数据源一次都不能被调用").isZero();
        assertThat(loggedToolCalls()).as("工具一次都不能被调用（连一次日志都不该有）").isEmpty();
    }

    @Test
    void malformedToolCallParamsAreRejectedAsInvalidParams() throws Exception {
        MonitoringMcpRawClient raw = new MonitoringMcpRawClient(this.port);
        String sessionId = raw.initialize().sessionId();

        List<String> params = List.of(
                "\"monitoring_snapshot_get\"",
                "[]",
                "{}",
                "{\"arguments\":{\"assetId\":\"AST-900001\"}}",
                "{\"name\":123,\"arguments\":{\"assetId\":\"AST-900001\"}}");

        for (String param : params) {
            MonitoringMcpRawClient.BoundedResponse response = raw.postBounded("""
                    {"jsonrpc":"2.0","id":42,"method":"tools/call","params":%s}""".formatted(param),
                    sessionId, EOF_WINDOW);

            assertThat(response.status()).as("params=%s", param).isEqualTo(200);
            assertThat(response.ended()).isTrue();
            assertThat(MAPPER.readTree(response.body()).path("error").path("code").asInt())
                    .as("params=%s", param)
                    .isEqualTo(-32602);
            assertNoInternalDetail(response.body());
        }

        assertThat(this.snapshotSource.invocations()).isZero();
        assertThat(loggedToolCalls()).isEmpty();

        // 反向对照：形状合法（params 是对象、有 name、没有 arguments）就交给工具层，
        // 由工具按自己的契约回答 —— 闸门只负责形状，不替工具判业务
        MonitoringMcpRawClient.BoundedResponse noArguments = raw.postBounded("""
                {"jsonrpc":"2.0","id":44,"method":"tools/call","params":{"name":"monitoring_snapshot_get"}}""",
                sessionId, EOF_WINDOW);
        assertThat(noArguments.body()).contains("INVALID_ASSET_ID").contains("\"isError\":true");
    }

    @Test
    void theExistingToolLevelErrorContractIsUntouched() throws Exception {
        MonitoringMcpRawClient raw = new MonitoringMcpRawClient(this.port);
        String sessionId = raw.initialize().sessionId();

        // 工具层错误（合法 JSON 对象入参，但 assetId 非法 / 有多余字段）必须仍然走工具，
        // 仍然是 isError=true + 固定内容 + 200 的 SSE
        for (String arguments : List.of("{\"assetId\":\"AST-1\"}", "{\"assetId\":\"AST-900001\",\"extra\":\"x\"}")) {
            MonitoringMcpRawClient.BoundedResponse response = raw.postBounded("""
                    {"jsonrpc":"2.0","id":43,"method":"tools/call",\
                    "params":{"name":"monitoring_snapshot_get","arguments":%s}}""".formatted(arguments),
                    sessionId, EOF_WINDOW);

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.contentType()).startsWith("text/event-stream");
            assertThat(response.ended()).isTrue();
            assertThat(response.body())
                    .as("工具层错误内容不变：isError=true + INVALID_ASSET_ID")
                    .contains("\"isError\":true")
                    .contains("INVALID_ASSET_ID");
        }

        assertThat(this.snapshotSource.invocations())
                .as("两次都止步于工具的输入校验，因此一次都没有查数据源")
                .isZero();
        assertThat(loggedToolCalls())
                .as("两处工具层错误说明工具确实被调用了（只是没走到数据源）")
                .hasSize(2);
    }

    // ---------- 3. 未实现的方法：明确的 Method not found，且流有界 ----------

    @Test
    void unimplementedMethodsAnswerMethodNotFoundWithABoundedResponse() throws Exception {
        MonitoringMcpRawClient raw = new MonitoringMcpRawClient(this.port);
        String sessionId = raw.initialize().sessionId();

        List<String> methods = List.of("resources/list", "resources/templates/list", "prompts/list",
                "completion/complete", "foo/bar", "tools/delete", "resources/read");

        for (String method : methods) {
            MonitoringMcpRawClient.BoundedResponse response = raw.postBounded("""
                    {"jsonrpc":"2.0","id":51,"method":"%s"}""".formatted(method), sessionId, EOF_WINDOW);

            assertThat(response.status()).as("method=%s", method).isEqualTo(200);
            assertThat(response.ended())
                    .as("method=%s：响应流必须在有界时间内结束，不能留下活跃请求", method)
                    .isTrue();
            assertThat(response.readFailure()).as("method=%s", method).isNull();

            JsonNode payload = MAPPER.readTree(response.body());
            assertThat(payload.path("error").path("code").asInt()).as("method=%s", method).isEqualTo(-32601);
            assertThat(payload.path("error").path("message").asText())
                    .as("错误必须明确")
                    .startsWith("Method not found")
                    .contains(method);
            assertNoInternalDetail(response.body());
        }
    }

    @Test
    void anUnknownMethodNameIsNotEchoedWhenItsShapeIsUnusual() throws Exception {
        MonitoringMcpRawClient raw = new MonitoringMcpRawClient(this.port);
        String sessionId = raw.initialize().sessionId();

        String weirdName = "resources/list?<script>alert(1)</script>" + "x".repeat(120);
        MonitoringMcpRawClient.RawResponse response = raw.post("""
                {"jsonrpc":"2.0","id":52,"method":"%s"}""".formatted(weirdName), sessionId);

        JsonNode payload = MAPPER.readTree(response.body());
        assertThat(payload.path("error").path("code").asInt()).isEqualTo(-32601);
        assertThat(payload.path("error").path("message").asText())
                .as("形状异常的方法名不回显，只给固定文案")
                .isEqualTo("Method not found");
        assertThat(response.body()).doesNotContain("script").doesNotContain("xxxx");
    }

    @Test
    void theSessionAndTheToolStillWorkAfterARejectedMethod() throws Exception {
        MonitoringMcpRawClient raw = new MonitoringMcpRawClient(this.port);
        String sessionId = raw.initialize().sessionId();

        assertThat(raw.post("""
                {"jsonrpc":"2.0","id":53,"method":"resources/list"}""", sessionId).status())
                .isEqualTo(200);

        // 同一个会话上的正常调用必须照旧：错误响应没有破坏会话状态
        MonitoringMcpRawClient.BoundedResponse call = raw.postBounded("""
                {"jsonrpc":"2.0","id":54,"method":"tools/call",\
                "params":{"name":"monitoring_snapshot_get","arguments":{"assetId":"AST-900002"}}}""",
                sessionId, EOF_WINDOW);

        assertThat(call.status()).isEqualTo(200);
        assertThat(call.ended()).isTrue();
        assertThat(call.body()).contains("AST-900002").contains("HEALTHY");
        assertThat(this.snapshotSource.invocations())
                .as("正向对照：合法调用确实查了一次数据源（说明计数数据源是有效的）")
                .isEqualTo(1);

        // 会话仍然可以被正常结束（清理路径不受影响）
        assertThat(raw.delete(sessionId).status()).isEqualTo(200);
    }

    @Test
    void aRealSdkClientCannotAskForAnUndeclaredCapabilityAndItsSessionStaysUsable() {
        try (McpSyncClient client = MonitoringMcpTestClient.connect(this.port)) {
            client.initialize();

            Throwable rejected = org.assertj.core.api.Assertions.catchThrowable(client::listResources);

            // 规范客户端甚至不会把这个请求发出去：它先检查服务端声明的能力。
            // 也就是说 resources/prompts/completions 的三重防线是：
            // 不声明（能力）→ 不注册（处理器）→ 传输层给出明确的 Method not found（针对不走客户端库的调用方）
            assertThat(rejected)
                    .as("服务端没有声明 resources 能力时，SDK 客户端必须拒绝发送请求")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("resources capability");

            // 同一个会话继续可用：工具仍然能列出来、能调用
            assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name).containsExactly("monitoring_snapshot_get");
            McpSchema.CallToolResult result =
                    MonitoringMcpTestClient.call(client, "monitoring_snapshot_get", Map.of("assetId", "AST-900001"));
            assertThat(result.isError()).isFalse();
            assertThat(MonitoringMcpTestClient.text(result)).contains("AST-900001").contains("DEGRADED");
        }
    }

    // ---------- 4. 回归：既有能力一个都不能坏 ----------

    @Test
    void theExistingProtocolSurfaceIsUnchanged() throws Exception {
        MonitoringMcpRawClient raw = new MonitoringMcpRawClient(this.port);

        MonitoringMcpRawClient.RawResponse initialized = raw.initialize();
        assertThat(initialized.status()).isEqualTo(200);
        assertThat(initialized.sessionId()).isNotBlank();

        String sessionId = initialized.sessionId();
        assertThat(raw.post(MonitoringMcpRawClient.INITIALIZED_NOTIFICATION, sessionId).status())
                .as("握手通知仍然被接受：闸门不得吞掉通知")
                .isEqualTo(202);

        assertThat(raw.post("{\"jsonrpc\":\"2.0\",\"id\":61,\"method\":\"ping\"}", sessionId).status())
                .isEqualTo(200);
        assertThat(raw.post("{\"jsonrpc\":\"2.0\",\"id\":62,\"method\":\"tools/list\"}", sessionId).body())
                .contains("monitoring_snapshot_get");

        MonitoringMcpRawClient.RawResponse call = raw.post("""
                {"jsonrpc":"2.0","id":63,"method":"tools/call",\
                "params":{"name":"monitoring_snapshot_get","arguments":{"assetId":"AST-900001"}}}""", sessionId);
        assertThat(call.body()).contains("DEMO").contains("\"isError\":false");

        // 未知工具名仍由传输层回答（不是闸门拦的），且响应有界
        MonitoringMcpRawClient.BoundedResponse unknownTool = raw.postBounded("""
                {"jsonrpc":"2.0","id":64,"method":"tools/call",\
                "params":{"name":"asset_delete","arguments":{"assetId":"AST-900001"}}}""",
                sessionId, EOF_WINDOW);
        assertThat(unknownTool.ended()).isTrue();
        assertThat(unknownTool.body()).contains("-32602");

        assertThat(raw.delete(sessionId).status()).isEqualTo(200);
    }

    @Test
    void anEmptyOrOversizedBodyIsRejectedSafelyAsWell() throws Exception {
        MonitoringMcpRawClient raw = new MonitoringMcpRawClient(this.port);

        MonitoringMcpRawClient.RawResponse empty = raw.post("", null);
        assertThat(empty.status()).isEqualTo(400);
        assertThat(MAPPER.readTree(empty.body()).path("error").path("code").asInt())
                .as("空报文按解析失败或无效请求处理，两者都是固定错误")
                .isIn(-32700, -32600);
        assertNoInternalDetail(empty.body());

        // 合法请求只含一个短标识：超过上限的请求体一律拒绝（读不完的报文无法判定）
        MonitoringMcpRawClient.RawResponse oversized =
                raw.post("{\"jsonrpc\":\"2.0\",\"id\":71,\"method\":\"tools/call\",\"params\":{\"name\":\"monitoring_snapshot_get\","
                        + "\"arguments\":{\"assetId\":\"" + "A".repeat(McpRequestGateFilter.MAX_BODY_BYTES + 1) + "\"}}}",
                        null);

        assertThat(oversized.status()).isEqualTo(413);
        JsonNode payload = MAPPER.readTree(oversized.body());
        assertThat(payload.path("error").path("code").asInt()).isEqualTo(-32600);
        assertThat(payload.path("error").path("message").asText()).isEqualTo("Request body too large");
        assertThat(oversized.body()).doesNotContain("AAAA");
        assertNoInternalDetail(oversized.body());
        assertThat(this.snapshotSource.invocations()).isZero();
    }

    // ---------- 辅助 ----------

    private static Logger toolLogger() {
        return (Logger) LoggerFactory.getLogger(MonitoringSnapshotGetTool.class);
    }

    private List<String> loggedToolCalls() {
        return this.toolLog.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message != null && message.contains("monitoring.snapshot.get"))
                .toList();
    }

    private static void assertNoInternalDetail(String body) {
        assertThat(body)
                .as("错误响应不得包含任何服务端内部信息：%s", body)
                .doesNotContain(FORBIDDEN_FRAGMENTS.toArray(String[]::new));
        assertThat(body)
                .as("错误响应不得含堆栈帧格式的行号片段")
                .doesNotContain("lineNumber")
                .doesNotContain("nativeMethod");
    }

    /** 计数的监控数据源：用来证明「闸门拒绝时工具与数据源都没被调用」。 */
    static final class CountingSnapshotSource implements MonitoringSnapshotSource {

        private final DemoSnapshotSource delegate = new DemoSnapshotSource();

        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public Optional<MonitoringSnapshot> findSnapshotById(String assetId) {
            this.invocations.incrementAndGet();
            return this.delegate.findSnapshotById(assetId);
        }

        @Override
        public SnapshotOrigin origin() {
            this.invocations.incrementAndGet();
            return this.delegate.origin();
        }

        int invocations() {
            return this.invocations.get();
        }

        void reset() {
            this.invocations.set(0);
        }
    }

    /** 用计数数据源覆盖演示数据源（{@code @Primary}）。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class CountingSnapshotSourceConfiguration {

        @Bean
        @Primary
        CountingSnapshotSource countingSnapshotSource() {
            return new CountingSnapshotSource();
        }
    }
}
