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
 * 通知与请求遵守同一条协议版本规则（FD-0015）。
 *
 * <p>FD-0015 把 {@code MCP-Protocol-Version} 校验放在了「请求/通知分流<b>之后</b>」，
 * 于是通知完全绕过了版本校验：活跃会话 + 不受支持的版本，请求被拒（400），
 * 而 {@code notifications/initialized} 之类的通知照旧被接受 —— 同一条连接上的两个请求遵守了两套规则。</p>
 *
 * <p>现在校验位于「会话有效性校验之后、请求/通知分流之前」：</p>
 * <ul>
 *   <li>活跃会话 + 不受支持的版本 → <b>请求与通知都返回 400</b>，错误体固定
 *       （{@code -32600} + {@code Unsupported protocol version}），通知的 {@code id} 为 {@code null}；</li>
 *   <li>缺少版本头继续兼容（按受支持处理），支持版本同理；</li>
 *   <li><b>存在但为空白</b>的版本头按无效版本处理（空串不是合法版本值），不当作缺失；</li>
 *   <li>缺失 / 伪造 / 已删除会话的 400 / 404 优先级不变：会话先判，仍由传输层回答。</li>
 * </ul>
 *
 * <p>全部断言走真实 HTTP（原始 JSON-RPC 报文 + 有界读取），而不仅是查配置。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "flowdesk.monitoring.source.mode=demo"
})
class MonitoringMcpNotificationVersionTests {

    private static final Duration EOF_WINDOW = Duration.ofSeconds(2);

    private static final String INITIALIZED_NOTIFICATION = MonitoringMcpRawClient.INITIALIZED_NOTIFICATION;

    private static final String PLAIN_NOTIFICATION = """
            {"jsonrpc":"2.0","method":"foo/notify"}""";

    private static final String TOOLS_LIST = """
            {"jsonrpc":"2.0","id":91,"method":"tools/list"}""";

    private static final String VALID_CALL = """
            {"jsonrpc":"2.0","id":92,"method":"tools/call",\
            "params":{"name":"monitoring_snapshot_get","arguments":{"assetId":"AST-900002"}}}""";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private WebMvcStreamableServerTransportProvider transportProvider;

    // ---------- 1. 不受支持的版本：通知与请求一样被拒 ----------

    @Test
    void anUnsupportedProtocolVersionIsRejectedForNotificationsToo() throws Exception {
        MonitoringMcpRawClient raw = new MonitoringMcpRawClient(this.port);
        String sessionId = raw.initialize().sessionId();

        for (String notification : List.of(INITIALIZED_NOTIFICATION, PLAIN_NOTIFICATION)) {
            MonitoringMcpRawClient.BoundedResponse response =
                    raw.postBounded(notification, sessionId, "1999-01-01", EOF_WINDOW);

            assertThat(response.status()).as("notification=%s：通知也必须被拒", notification).isEqualTo(400);
            assertThat(response.ended()).as("响应必须有界").isTrue();

            JsonNode payload = MAPPER.readTree(response.body());
            assertThat(payload.path("jsonrpc").asText()).isEqualTo("2.0");
            assertThat(payload.path("id").isNull())
                    .as("通知没有标识：错误体里必须是 id:null")
                    .isTrue();
            assertThat(payload.path("error").path("code").asInt()).isEqualTo(-32600);
            assertThat(payload.path("error").path("message").asText())
                    .isEqualTo("Unsupported protocol version");

            assertThat(response.body())
                    .as("固定错误体：不回显版本值、不夹带内部信息")
                    .doesNotContain("1999-01-01");
            assertNoInternalDetail(response.body());
        }
    }

    @Test
    void anUnsupportedProtocolVersionIsRejectedForRequestsAsWell() throws Exception {
        MonitoringMcpRawClient raw = new MonitoringMcpRawClient(this.port);
        String sessionId = raw.initialize().sessionId();

        MonitoringMcpRawClient.BoundedResponse response = raw.postBounded(TOOLS_LIST, sessionId, "1999-01-01", EOF_WINDOW);

        assertThat(response.status()).isEqualTo(400);
        JsonNode payload = MAPPER.readTree(response.body());
        assertThat(payload.path("id").asInt()).as("请求必须回显标识").isEqualTo(91);
        assertThat(payload.path("error").path("code").asInt()).isEqualTo(-32600);
        assertThat(payload.path("error").path("message").asText()).isEqualTo("Unsupported protocol version");
        assertNoInternalDetail(response.body());
    }

    @Test
    void aPresentButBlankProtocolVersionIsInvalidRatherThanMissing() throws Exception {
        MonitoringMcpRawClient raw = new MonitoringMcpRawClient(this.port);
        String sessionId = raw.initialize().sessionId();

        // Servlet 容器会把这个头（值空串/纯空白）照原样交给应用，因此它必须按「无效版本」处理，
        // 而不是被当成「客户端没给版本」
        for (String blank : List.of("", " ", "   ")) {
            MonitoringMcpRawClient.BoundedResponse notification =
                    raw.postBounded(INITIALIZED_NOTIFICATION, sessionId, blank, EOF_WINDOW);
            MonitoringMcpRawClient.BoundedResponse request =
                    raw.postBounded(TOOLS_LIST, sessionId, blank, EOF_WINDOW);

            assertThat(notification.status()).as("blank=[%s] 通知", blank).isEqualTo(400);
            assertThat(request.status()).as("blank=[%s] 请求", blank).isEqualTo(400);
            assertThat(MAPPER.readTree(notification.body()).path("error").path("message").asText())
                    .as("blank=[%s]", blank)
                    .isEqualTo("Unsupported protocol version");
            assertThat(MAPPER.readTree(request.body()).path("error").path("message").asText())
                    .as("blank=[%s]", blank)
                    .isEqualTo("Unsupported protocol version");
            assertNoInternalDetail(notification.body());
            assertNoInternalDetail(request.body());
        }
    }

    // ---------- 2. 正向对照：支持版本与缺省版本 ----------

    @Test
    void supportedAndMissingProtocolVersionsKeepNotificationsWorking() throws Exception {
        MonitoringMcpRawClient raw = new MonitoringMcpRawClient(this.port);
        String sessionId = raw.initialize().sessionId();

        List<String> supported = List.copyOf(this.transportProvider.protocolVersions());
        assertThat(supported).as("传输层必须公布它支持的协议版本").isNotEmpty();

        for (String version : supported) {
            for (String notification : List.of(INITIALIZED_NOTIFICATION, PLAIN_NOTIFICATION)) {
                assertThat(raw.postBounded(notification, sessionId, version, EOF_WINDOW).status())
                        .as("version=%s notification=%s", version, notification)
                        .isEqualTo(202);
            }
            MonitoringMcpRawClient.BoundedResponse toolsList =
                    raw.postBounded(TOOLS_LIST, sessionId, version, EOF_WINDOW);
            assertThat(toolsList.status()).as("version=%s：工具列表照常", version).isEqualTo(200);
            assertThat(toolsList.body()).as("version=%s", version).contains("monitoring_snapshot_get");
        }

        // 缺省版本头（老客户端）：通知 202、请求照常
        assertThat(raw.postBounded(INITIALIZED_NOTIFICATION, sessionId, EOF_WINDOW).status()).isEqualTo(202);
        assertThat(raw.postBounded(PLAIN_NOTIFICATION, sessionId, EOF_WINDOW).status()).isEqualTo(202);
        assertThat(raw.post(TOOLS_LIST, sessionId).body()).contains("monitoring_snapshot_get");
    }

    @Test
    void aRejectedNotificationDoesNotBreakTheSession() throws Exception {
        MonitoringMcpRawClient raw = new MonitoringMcpRawClient(this.port);
        String sessionId = raw.initialize().sessionId();

        assertThat(raw.postBounded(INITIALIZED_NOTIFICATION, sessionId, "1999-01-01", EOF_WINDOW).status())
                .isEqualTo(400);

        // 同一个会话继续可用：缺省版本的通知照旧 202，工具照旧能调用
        assertThat(raw.postBounded(INITIALIZED_NOTIFICATION, sessionId, EOF_WINDOW).status()).isEqualTo(202);

        MonitoringMcpRawClient.BoundedResponse call = raw.postBounded(VALID_CALL, sessionId, EOF_WINDOW);
        assertThat(call.ended()).isTrue();
        assertThat(call.body()).contains("AST-900002").contains("HEALTHY");

        assertThat(raw.delete(sessionId).status()).isEqualTo(200);
    }

    // ---------- 3. 会话校验的优先级不变 ----------

    @Test
    void sessionValidationStillComesBeforeTheProtocolVersionCheck() throws Exception {
        MonitoringMcpRawClient raw = new MonitoringMcpRawClient(this.port);

        // 无会话标识 + 不受支持的版本：由传输层回答「无效请求」，而不是版本错误
        MonitoringMcpRawClient.BoundedResponse missing = raw.postBounded(INITIALIZED_NOTIFICATION, null,
                "1999-01-01", EOF_WINDOW);
        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.body())
                .as("优先级：会话先判，版本错误不得抢先")
                .doesNotContain("Unsupported protocol version")
                .contains("Invalid request");
        assertNoInternalDetail(missing.body());

        // 伪造会话 + 不受支持的版本 → 传输层 404
        MonitoringMcpRawClient.BoundedResponse forged = raw.postBounded(INITIALIZED_NOTIFICATION, "forged-session",
                "1999-01-01", EOF_WINDOW);
        assertThat(forged.status()).isEqualTo(404);
        assertThat(forged.body()).doesNotContain("Unsupported protocol version");
        assertNoInternalDetail(forged.body());

        // 已删除会话 + 不受支持的版本 → 传输层 404
        String sessionId = raw.initialize().sessionId();
        assertThat(raw.delete(sessionId).status()).isEqualTo(200);
        MonitoringMcpRawClient.BoundedResponse deleted = raw.postBounded(INITIALIZED_NOTIFICATION, sessionId,
                "1999-01-01", EOF_WINDOW);
        assertThat(deleted.status()).isEqualTo(404);
        assertThat(deleted.body()).doesNotContain("Unsupported protocol version");
        assertNoInternalDetail(deleted.body());
    }

    @Test
    void theHandshakeItselfIsStillNotVersionChecked() throws Exception {
        MonitoringMcpRawClient.BoundedResponse response = new MonitoringMcpRawClient(this.port)
                .postBounded(MonitoringMcpRawClient.INITIALIZE_BODY, null, "1999-01-01", EOF_WINDOW);

        assertThat(response.status())
                .as("版本是在 initialize 里协商的：握手请求不参与版本校验")
                .isEqualTo(200);
        assertThat(response.body()).contains("flowdesk-mcp-monitoring");
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
