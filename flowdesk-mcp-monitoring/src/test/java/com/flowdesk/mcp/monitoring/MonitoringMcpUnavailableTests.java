package com.flowdesk.mcp.monitoring;

import static com.flowdesk.mcp.monitoring.MonitoringMcpTestClient.call;
import static com.flowdesk.mcp.monitoring.MonitoringMcpTestClient.payload;
import static com.flowdesk.mcp.monitoring.MonitoringMcpTestClient.text;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 默认模式（未配置真实监控数据源）的协议契约测试（FD-0015）。
 *
 * <p>本类<b>不</b>设置 {@code flowdesk.monitoring.source.mode}，因此走的是默认值
 * {@code unavailable}。这是本阶段最诚实的运行状态：没有真实监控系统，
 * 工具必须明确失败并给出稳定错误码，<b>绝不</b>拿演示数据冒充真实监控。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MonitoringMcpUnavailableTests {

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

    @Test
    void theToolIsStillListedSoTheCapabilityIsDiscoverable() {
        this.client.initialize();

        assertThat(this.client.listTools().tools()).extracting(McpSchema.Tool::name)
                .as("数据源不可用不影响工具发现：客户端仍能看到 monitoring_snapshot_get")
                .containsExactly("monitoring_snapshot_get");
    }

    @Test
    void aWellFormedAssetIdFailsWithTheStableSourceUnavailableCode() throws Exception {
        this.client.initialize();

        McpSchema.CallToolResult result =
                call(this.client, "monitoring_snapshot_get", Map.of("assetId", "AST-900001"));

        assertThat(result.isError())
                .as("没有数据源属于「工具执行失败」，不是「没有这个快照」")
                .isTrue();
        JsonNode payload = payload(result);
        assertThat(payload.path("error").asText()).isEqualTo("MONITORING_SOURCE_UNAVAILABLE");
        assertThat(payload.path("message").asText()).isEqualTo("监控数据源当前不可用");
        assertThat(payload.has("found")).as("不得给出「未找到」这种业务结论").isFalse();
        assertThat(payload.has("source")).as("没有数据源就没有来源可声明").isFalse();
    }

    @Test
    void noDemoDataEverLeaksThroughTheDefaultMode() {
        this.client.initialize();

        for (String assetId : new String[] { "AST-900001", "AST-900002", "AST-900003" }) {
            McpSchema.CallToolResult result = call(this.client, "monitoring_snapshot_get", Map.of("assetId", assetId));

            assertThat(text(result))
                    .as("assetId=%s：默认模式绝不能返回演示数据", assetId)
                    .doesNotContain("DEMO")
                    .doesNotContain("DEGRADED")
                    .doesNotContain("HEALTHY")
                    .doesNotContain("cpuUtilizationPercent");
        }
    }

    @Test
    void invalidInputIsStillRejectedBeforeAnyDataSourceIsConsulted() throws Exception {
        this.client.initialize();

        McpSchema.CallToolResult result =
                call(this.client, "monitoring_snapshot_get", Map.of("assetId", "not-an-asset-id"));

        assertThat(result.isError()).isTrue();
        assertThat(payload(result).path("error").asText())
                .as("输入非法与数据源不可用是两件事，前者优先")
                .isEqualTo("INVALID_ASSET_ID");
    }
}
