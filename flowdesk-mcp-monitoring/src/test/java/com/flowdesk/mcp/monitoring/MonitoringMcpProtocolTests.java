package com.flowdesk.mcp.monitoring;

import static com.flowdesk.mcp.monitoring.MonitoringMcpTestClient.call;
import static com.flowdesk.mcp.monitoring.MonitoringMcpTestClient.payload;
import static com.flowdesk.mcp.monitoring.MonitoringMcpTestClient.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 监控 MCP 服务的<b>真实协议</b>验收测试（FD-0015）。
 *
 * <p>用官方 MCP Java SDK 客户端通过 Streamable HTTP 连接一个真实启动、随机端口的服务，
 * 完成 {@code initialize} → {@code tools/list} → {@code tools/call} 的完整握手与调用。
 * 这<b>不是</b> {@code contextLoads}，也不是直接调用 Java 方法：请求走真实 HTTP + JSON-RPC。</p>
 *
 * <p>演示模式在这里被显式打开：只有存在数据源，才能真实验证「命中」与「未找到」两条路径。
 * 所有结果的 {@code source} 都必须是 {@code DEMO} —— 演示数据不得冒充真实监控。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "flowdesk.monitoring.source.mode=demo"
})
class MonitoringMcpProtocolTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @LocalServerPort
    private int port;

    private McpSyncClient client;

    @BeforeEach
    void connect() {
        this.client = MonitoringMcpTestClient.connect(this.port);
    }

    @AfterEach
    void disconnect() {
        if (this.client != null) {
            this.client.close();
        }
    }

    // ---------- initialize / tools/list ----------

    @Test
    void initializeSucceedsAndPublishesTheConfiguredServerIdentity() {
        McpSchema.InitializeResult result = this.client.initialize();

        assertThat(this.client.isInitialized()).isTrue();
        assertThat(result.serverInfo().name()).isEqualTo("flowdesk-mcp-monitoring");
        assertThat(result.protocolVersion()).isNotBlank();
        assertThat(result.capabilities().tools())
                .as("必须声明 tools 能力，否则客户端不会列出工具")
                .isNotNull();
        assertThat(result.capabilities().resources())
                .as("resources 未实现，不得声明")
                .isNull();
        assertThat(result.capabilities().prompts())
                .as("prompts 未实现，不得声明")
                .isNull();
        assertThat(result.capabilities().completions())
                .as("completions 未实现，不得声明")
                .isNull();
    }

    @Test
    void toolsListContainsExactlyTheReadOnlySnapshotToolWithARequiredAssetId() {
        this.client.initialize();

        List<McpSchema.Tool> tools = this.client.listTools().tools();

        assertThat(tools).extracting(McpSchema.Tool::name).containsExactly("monitoring_snapshot_get");

        McpSchema.Tool tool = tools.get(0);
        assertThat(tool.description())
                .as("描述必须说明它是只读的，并说明 DEMO 的含义")
                .contains("只读")
                .contains("DEMO");

        McpSchema.JsonSchema schema = tool.inputSchema();
        assertThat(schema.type()).isEqualTo("object");
        assertThat(schema.properties()).containsOnlyKeys("assetId");
        assertThat(schema.required()).as("assetId 必须是必填").containsExactly("assetId");
        assertThat(schema.additionalProperties()).isFalse();
        assertThat(((Map<?, ?>) schema.properties().get("assetId")).get("type")).isEqualTo("string");
        assertThat(((Map<?, ?>) schema.properties().get("assetId")).get("pattern"))
                .as("schema 的 pattern 必须锚定，且与执行校验一致")
                .isEqualTo("^AST-[0-9]{6}$");
        assertThat(((Map<?, ?>) schema.properties().get("assetId")).get("maxLength")).isEqualTo(10);

        assertThat(tools).extracting(McpSchema.Tool::name)
                .as("不得出现任何写工具（工具名以写动词结尾）")
                .noneMatch(name -> name.matches(".*[_-](add|create|update|set|put|patch|delete|remove|write|report)$"));
    }

    // ---------- tools/call：两条命中 ----------

    @Test
    void theDegradedDemoAssetIsReturnedWithAFixedFieldOrder() throws Exception {
        this.client.initialize();

        McpSchema.CallToolResult result = call(this.client, "monitoring_snapshot_get", Map.of("assetId", "AST-900001"));

        assertThat(result.isError()).as("命中是成功的调用").isFalse();
        String body = text(result);
        JsonNode payload = payload(result);

        assertThat(payload.path("assetId").asText()).isEqualTo("AST-900001");
        assertThat(payload.path("observedAt").asText()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(payload.path("health").asText()).isEqualTo("DEGRADED");
        assertThat(payload.path("cpuUtilizationPercent").asInt()).isEqualTo(92);
        assertThat(payload.path("memoryUtilizationPercent").asInt()).isEqualTo(68);
        assertThat(payload.path("activeAlertCount").asInt()).isEqualTo(1);
        assertThat(payload.path("source").asText()).as("演示数据必须明确标识来源").isEqualTo("DEMO");

        assertThat(payload.fieldNames()).toIterable()
                .as("命中结果的字段顺序必须确定")
                .containsExactly("assetId", "observedAt", "health", "cpuUtilizationPercent",
                        "memoryUtilizationPercent", "activeAlertCount", "source");
        assertThat(body)
                .as("不得出现 IP、主机名、内部地址、凭证或异常信息")
                .doesNotContain("host", "hostname", "ip", "token", "password", "Exception");
    }

    @Test
    void theHealthyDemoAssetIsReturnedAsWell() throws Exception {
        this.client.initialize();

        JsonNode payload = payload(call(this.client, "monitoring_snapshot_get", Map.of("assetId", "AST-900002")));

        assertThat(payload.path("assetId").asText()).isEqualTo("AST-900002");
        assertThat(payload.path("health").asText()).isEqualTo("HEALTHY");
        assertThat(payload.path("cpuUtilizationPercent").asInt()).isEqualTo(18);
        assertThat(payload.path("memoryUtilizationPercent").asInt()).isEqualTo(35);
        assertThat(payload.path("activeAlertCount").asInt()).isZero();
        assertThat(payload.path("source").asText()).isEqualTo("DEMO");
    }

    @Test
    void theSameCallReturnsByteIdenticalContent() throws Exception {
        this.client.initialize();

        String first = text(call(this.client, "monitoring_snapshot_get", Map.of("assetId", "AST-900001")));
        String second = text(call(this.client, "monitoring_snapshot_get", Map.of("assetId", "AST-900001")));

        assertThat(first)
                .as("固定演示数据 + 固定时间：同样输入必须逐字节相同")
                .isEqualTo(second);
    }

    // ---------- tools/call：合法但没有快照 ----------

    @Test
    void anAssetWithoutASnapshotReturnsNotFoundWithoutBeingAToolError() throws Exception {
        this.client.initialize();

        McpSchema.CallToolResult result =
                call(this.client, "monitoring_snapshot_get", Map.of("assetId", "AST-900003"));

        assertThat(result.isError())
                .as("「没有快照」是查询正常给出的答案，不是工具执行失败")
                .isFalse();
        JsonNode payload = payload(result);
        assertThat(payload.path("assetId").asText()).isEqualTo("AST-900003");
        assertThat(payload.path("found").asBoolean()).isFalse();
        assertThat(payload.path("error").asText()).isEqualTo("MONITORING_SNAPSHOT_NOT_FOUND");
        assertThat(payload.path("message").asText()).isEqualTo("未找到该资产的监控快照");
        assertThat(payload.path("source").asText()).as("未找到也要带血缘").isEqualTo("DEMO");
        assertThat(payload.fieldNames()).toIterable()
                .containsExactly("assetId", "found", "error", "message", "source");
    }

    // ---------- tools/call：非法输入 ----------

    @Test
    void invalidAssetIdsAreRejectedAsToolErrors() throws Exception {
        this.client.initialize();

        for (String invalid : List.of("", "   ", "AST-1", "AST-12345", "AST-1234567", "ast-900001",
                "AST-90000A", "900001", "AST-900001 ", "AST_900001", "AST 900001", "AST-900 001",
                "A".repeat(200))) {

            McpSchema.CallToolResult result = call(this.client, "monitoring_snapshot_get", Map.of("assetId", invalid));

            assertThat(result.isError()).as("assetId=[%s]", invalid).isTrue();
            assertThat(payload(result).path("error").asText())
                    .as("assetId=[%s]", invalid)
                    .isEqualTo("INVALID_ASSET_ID");
            assertThat(text(result))
                    .as("assetId=[%s]：错误内容必须逐字固定，从而同时排除「回显输入」", invalid)
                    .isEqualTo("{\"error\":\"INVALID_ASSET_ID\","
                            + "\"message\":\"assetId 必须形如 AST-000001（AST- 加 6 位数字）\"}");
        }
    }

    @Test
    void extraFieldsAndWrongTypesAreRejectedAsToolErrors() throws Exception {
        this.client.initialize();

        McpSchema.CallToolResult extra = call(this.client, "monitoring_snapshot_get",
                Map.of("assetId", "AST-900001", "extra", "sentinel-extra-argument"));
        assertThat(extra.isError()).as("additionalProperties=false：多传字段必须失败").isTrue();
        assertThat(payload(extra).path("error").asText()).isEqualTo("INVALID_ASSET_ID");
        assertThat(text(extra))
                .doesNotContain("sentinel-extra-argument")
                .doesNotContain("DEGRADED")
                .doesNotContain("DEMO");

        McpSchema.CallToolResult missing = call(this.client, "monitoring_snapshot_get", Map.of());
        assertThat(missing.isError()).isTrue();
        assertThat(payload(missing).path("error").asText()).isEqualTo("INVALID_ASSET_ID");

        McpSchema.CallToolResult wrongType = call(this.client, "monitoring_snapshot_get", Map.of("assetId", 900001));
        assertThat(wrongType.isError()).isTrue();
        assertThat(payload(wrongType).path("error").asText()).isEqualTo("INVALID_ASSET_ID");

        // 字段名写错与多传字段等价：都必须在协议层被拒绝（additionalProperties=false 的另一面）
        McpSchema.CallToolResult wrongName = call(this.client, "monitoring_snapshot_get",
                Map.of("asset_id", "AST-900001"));
        assertThat(wrongName.isError()).as("asset_id ≠ assetId：字段名写错必须失败").isTrue();
        assertThat(payload(wrongName).path("error").asText()).isEqualTo("INVALID_ASSET_ID");
    }

    @Test
    void aWriteLookingToolNameIsRejectedByTheServer() {
        this.client.initialize();

        Throwable thrown = catchThrowable(() -> MonitoringMcpTestClient.call(this.client,
                "monitoring_snapshot_report", Map.of("assetId", "AST-900001")));

        assertThat(thrown)
                .as("未注册的工具名必须被服务端拒绝（这同时证明本服务没有写工具）")
                .isNotNull();
    }
}
