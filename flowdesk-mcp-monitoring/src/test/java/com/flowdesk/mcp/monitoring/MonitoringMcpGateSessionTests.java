package com.flowdesk.mcp.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 闸门的会话与协议版本校验（FD-0015）。
 *
 * <p>FD-0015 的闸门会在请求进入传输实现之前回答「未实现的方法」与「非法 {@code tools/call} 参数」，
 * 但它当时<b>没有</b>校验会话：没有会话标识、伪造的、甚至已经被 {@code DELETE} 结束的标识，
 * 都能拿到一个 {@code 200} 的 {@code -32601} / {@code -32602}。而传输层对同样的请求会回
 * {@code 400}（缺少会话标识）或 {@code 404}（会话不存在）—— 也就是说闸门<b>绕过了传输层校验</b>。</p>
 *
 * <p>现在闸门只在「传输层本来也会接受这个请求」时提前回答：</p>
 * <ul>
 *   <li>非 {@code initialize} 的请求必须带<b>活跃</b>会话（真的在传输层会话表里），
 *       否则原样放行，让传输层给出它自己的 400 / 404（响应同样已脱敏并有界）；</li>
 *   <li>会话有效之后才校验 {@code MCP-Protocol-Version}：必须是传输层公布的受支持版本，
 *       否则回固定的 400（传输层自己不校验这个头，见 ADR 0012）；</li>
 *   <li>解析层面的错误（畸形 JSON、不是 JSON-RPC 请求）不要求会话 —— 传输层同样是先解析后查会话。</li>
 * </ul>
 *
 * <p>本类用真实 HTTP 覆盖缺失 / 伪造 / 已删除的会话标识，以及不受支持与受支持的协议版本，
 * 并且两种「提前回答路径」各自都覆盖；同时确认正常握手、工具调用与 {@code DELETE} 不受影响。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "flowdesk.monitoring.source.mode=demo"
})
class MonitoringMcpGateSessionTests {

    /** 修复后这些响应都是普通 JSON，2 秒窗口足够判断「是否结束」。 */
    private static final Duration EOF_WINDOW = Duration.ofSeconds(2);

    private static final String UNKNOWN_METHOD_BODY = """
            {"jsonrpc":"2.0","id":81,"method":"resources/list"}""";

    private static final String INVALID_PARAMS_BODY = """
            {"jsonrpc":"2.0","id":82,"method":"tools/call",\
            "params":{"name":"monitoring_snapshot_get","arguments":"AST-900001"}}""";

    private static final String VALID_CALL_BODY = """
            {"jsonrpc":"2.0","id":83,"method":"tools/call",\
            "params":{"name":"monitoring_snapshot_get","arguments":{"assetId":"AST-900001"}}}""";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private WebMvcStreamableServerTransportProvider transportProvider;

    // ---------- 1. 会话无效时：闸门不得提前回答 ----------

    @Test
    void withoutASessionHeaderTheGateLeavesBothEarlyAnswerPathsToTheTransport() throws Exception {
        // 两个请求都不带 Mcp-Session-Id：闸门此前会回 200 + -32601 / -32602
        assertTransportAnswered("missing session", postBothEarlyPaths(null));
    }

    @Test
    void withAForgedSessionBothEarlyAnswerPathsAreStillRejectedByTheTransport() throws Exception {
        assertTransportAnswered("forged session", postBothEarlyPaths("forged-session-id"));
    }

    @Test
    void withAnEmptySessionHeaderBothEarlyAnswerPathsAreStillRejectedByTheTransport() throws Exception {
        assertTransportAnswered("empty session", postBothEarlyPaths(""));
    }

    @Test
    void withADeletedSessionBothEarlyAnswerPathsAreStillRejectedByTheTransport() throws Exception {
        MonitoringMcpRawClient raw = new MonitoringMcpRawClient(this.port);
        String sessionId = raw.initialize().sessionId();

        // 先证明这个会话确实是活的（否则本用例证明不了「删除之后才被拒」）
        assertThat(raw.postBounded(UNKNOWN_METHOD_BODY, sessionId, EOF_WINDOW).body())
                .as("活跃会话下闸门才会提前回答")
                .contains("-32601");

        assertThat(raw.delete(sessionId).status()).isEqualTo(200);

        assertTransportAnswered("deleted session", postBothEarlyPaths(sessionId));
    }

    // ---------- 2. 协议版本 ----------

    @Test
    void anUnsupportedProtocolVersionIsRejectedOnBothEarlyAnswerPaths() throws Exception {
        MonitoringMcpRawClient raw = new MonitoringMcpRawClient(this.port);
        String sessionId = raw.initialize().sessionId();

        for (String body : List.of(UNKNOWN_METHOD_BODY, INVALID_PARAMS_BODY)) {
            MonitoringMcpRawClient.BoundedResponse response =
                    raw.postBounded(body, sessionId, "1999-01-01", EOF_WINDOW);

            assertThat(response.status()).as("body=%s", body).isEqualTo(400);
            assertThat(response.ended()).as("响应必须有界").isTrue();
            JsonNode payload = MAPPER.readTree(response.body());
            assertThat(payload.path("error").path("code").asInt()).isEqualTo(-32600);
            assertThat(payload.path("error").path("message").asText())
                    .as("固定文案：既不是 -32601 也不是 -32602")
                    .isEqualTo("Unsupported protocol version");
            assertNoInternalDetail(response.body());
            assertThat(response.body())
                    .as("版本不受支持时不应走到方法分派")
                    .doesNotContain("Method not found")
                    .doesNotContain("Invalid params");
        }

        // 受支持的版本照旧走闸门（会话有效）
        assertThat(MAPPER.readTree(raw.postBounded(UNKNOWN_METHOD_BODY, sessionId, "2025-06-18", EOF_WINDOW).body())
                .path("error").path("code").asInt())
                .as("传输层公布的版本必须被接受")
                .isEqualTo(-32601);
    }

    @Test
    void everyProtocolVersionTheTransportAdvertisesIsAccepted() throws Exception {
        MonitoringMcpRawClient raw = new MonitoringMcpRawClient(this.port);
        String sessionId = raw.initialize().sessionId();

        List<String> supported = List.copyOf(this.transportProvider.protocolVersions());
        assertThat(supported).as("传输层必须公布它支持的协议版本").isNotEmpty();

        for (String version : supported) {
            assertThat(MAPPER.readTree(raw.postBounded(UNKNOWN_METHOD_BODY, sessionId, version, EOF_WINDOW).body())
                    .path("error").path("code").asInt())
                    .as("version=%s", version)
                    .isEqualTo(-32601);
            assertThat(MAPPER.readTree(raw.postBounded(INVALID_PARAMS_BODY, sessionId, version, EOF_WINDOW).body())
                    .path("error").path("code").asInt())
                    .as("version=%s", version)
                    .isEqualTo(-32602);
        }

        // 缺省版本头同样受支持（传输层对缺省本就不作要求）
        assertThat(MAPPER.readTree(raw.postBounded(UNKNOWN_METHOD_BODY, sessionId, EOF_WINDOW).body())
                .path("error").path("code").asInt())
                .isEqualTo(-32601);
    }

    @Test
    void aVersionHeaderOnAHandshakeRequestIsNotRejected() throws Exception {
        // 握手请求不校验版本（版本是在会话里协商的），也不要求会话
        MonitoringMcpRawClient.BoundedResponse response = new MonitoringMcpRawClient(this.port)
                .postBounded(MonitoringMcpRawClient.INITIALIZE_BODY, null, "1999-01-01", EOF_WINDOW);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("flowdesk-mcp-monitoring");
    }

    // ---------- 3. 正常链路不受影响 ----------

    @Test
    void theNormalHandshakeToolsAndSessionTerminationAreUnaffected() throws Exception {
        MonitoringMcpRawClient raw = new MonitoringMcpRawClient(this.port);

        MonitoringMcpRawClient.RawResponse initialized = raw.initialize();
        assertThat(initialized.status()).isEqualTo(200);
        String sessionId = initialized.sessionId();
        assertThat(sessionId).isNotBlank();

        assertThat(raw.post(MonitoringMcpRawClient.INITIALIZED_NOTIFICATION, sessionId).status())
                .as("握手通知仍然被接受（它没有 id，闸门不得吞掉）")
                .isEqualTo(202);
        assertThat(raw.post(UNKNOWN_METHOD_BODY, sessionId).status()).isEqualTo(200);
        assertThat(raw.post("{\"jsonrpc\":\"2.0\",\"id\":84,\"method\":\"ping\"}", sessionId).status())
                .isEqualTo(200);
        assertThat(raw.post("{\"jsonrpc\":\"2.0\",\"id\":85,\"method\":\"tools/list\"}", sessionId).body())
                .contains("monitoring_snapshot_get");

        MonitoringMcpRawClient.BoundedResponse call = raw.postBounded(VALID_CALL_BODY, sessionId, EOF_WINDOW);
        assertThat(call.ended()).isTrue();
        assertThat(call.body()).contains("\"isError\":false").contains("AST-900001");

        // 工具层错误契约不变
        assertThat(raw.postBounded(INVALID_PARAMS_BODY, sessionId, EOF_WINDOW).body())
                .contains("-32602");

        assertThat(raw.delete(sessionId).status()).isEqualTo(200);
    }

    // ---------- 辅助 ----------

    /**
     * 在给定会话标识下分别发两条「闸门本来会提前回答」的请求。
     *
     * @param sessionId 会话标识；{@code null} 表示不带该头
     * @return 两次响应
     * @throws Exception 请求失败
     */
    private List<MonitoringMcpRawClient.BoundedResponse> postBothEarlyPaths(String sessionId) throws Exception {
        MonitoringMcpRawClient raw = new MonitoringMcpRawClient(this.port);
        return List.of(
                raw.postBounded(UNKNOWN_METHOD_BODY, sessionId, EOF_WINDOW),
                raw.postBounded(INVALID_PARAMS_BODY, sessionId, EOF_WINDOW));
    }

    /**
     * 断言这两条响应来自传输层（400/404 + 固定脱敏错误），而不是闸门的 200 + JSON-RPC 错误。
     *
     * @param label 断言信息里的场景名
     * @param responses 两条响应
     * @throws Exception 响应体不是合法 JSON
     */
    private static void assertTransportAnswered(String label, List<MonitoringMcpRawClient.BoundedResponse> responses)
            throws Exception {
        for (MonitoringMcpRawClient.BoundedResponse response : responses) {
            assertThat(response.status())
                    .as("%s：会话无效时必须由传输层拒绝（400 缺少会话标识 / 404 会话不存在）", label)
                    .isIn(400, 404);
            assertThat(response.ended()).as("%s：响应必须有界", label).isTrue();
            assertThat(response.readFailure()).as("%s", label).isNull();

            JsonNode payload = MAPPER.readTree(response.body());
            assertThat(payload.path("message").asText())
                    .as("%s：传输层的固定脱敏文案", label)
                    .isEqualTo("Invalid request");
            assertThat(payload.has("stackTrace")).as("%s", label).isFalse();

            assertThat(response.body())
                    .as("%s：闸门不得提前回答（不能出现 -32601 / -32602 与它的固定文案）", label)
                    .doesNotContain("Method not found")
                    .doesNotContain("Invalid params")
                    .doesNotContain("Unsupported protocol version");
            assertNoInternalDetail(response.body());
        }
    }

    private static void assertNoInternalDetail(String body) {
        assertThat(body)
                .as("错误响应不得包含任何服务端内部信息：%s", body)
                .doesNotContain("stackTrace")
                .doesNotContain("cause")
                .doesNotContain("lineNumber")
                .doesNotContain("nativeMethod")
                .doesNotContain("java.lang.")
                .doesNotContain("io.modelcontextprotocol")
                .doesNotContain("org.springframework")
                .doesNotContain("com.flowdesk")
                .doesNotContain(".java")
                .doesNotContain("Exception");
    }
}
