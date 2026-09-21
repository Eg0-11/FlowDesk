package com.flowdesk.infrastructure.mcp.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowdesk.application.integration.AssetQueryResult;
import com.flowdesk.application.integration.HealthState;
import com.flowdesk.application.integration.MonitoringSnapshotQueryResult;
import com.flowdesk.application.integration.QueryFailure;
import com.flowdesk.application.integration.QueryOutcome;
import com.flowdesk.application.integration.SourceOrigin;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 监控快照查询适配器的协议与载荷验收（FD-0016）。
 *
 * <p>与资产侧同一套链路（真实 SDK + 真实 HTTP），另外覆盖监控特有的封闭枚举、
 * 时刻格式与数值范围校验，以及「一个服务不可用不影响另一个」。</p>
 */
class McpMonitoringSnapshotAdapterTests {

    private static final String HIT = "{\"assetId\":\"AST-900001\",\"observedAt\":\"2026-01-01T00:00:00Z\","
            + "\"health\":\"DEGRADED\",\"cpuUtilizationPercent\":92,\"memoryUtilizationPercent\":68,"
            + "\"activeAlertCount\":1,\"source\":\"DEMO\"}";

    private static final String NOT_FOUND = "{\"assetId\":\"AST-900001\",\"found\":false,"
            + "\"error\":\"MONITORING_SNAPSHOT_NOT_FOUND\",\"message\":\"未找到该资产的监控快照\",\"source\":\"DEMO\"}";

    private ControllableMcpEndpoint endpoint;

    private McpMonitoringSnapshotQueryAdapter adapter;

    @BeforeEach
    void startEndpoint() throws Exception {
        this.endpoint = ControllableMcpEndpoint.start();
        this.adapter = new McpMonitoringSnapshotQueryAdapter(new McpToolClient(Duration.ofSeconds(2)),
                McpServerEndpoint.of(this.endpoint.baseUrl()));
    }

    @AfterEach
    void stopEndpoint() {
        this.endpoint.close();
    }

    @Test
    void aHitIsParsedWithItsObservationTimeAndSource() {
        this.endpoint.respondWithToolText(HIT);

        MonitoringSnapshotQueryResult result = this.adapter.findLatestSnapshot("AST-900001");

        assertThat(result.outcome()).isEqualTo(QueryOutcome.FOUND);
        assertThat(result.requireSnapshot().assetId()).isEqualTo("AST-900001");
        assertThat(result.requireSnapshot().observedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(result.requireSnapshot().health()).isEqualTo(HealthState.DEGRADED);
        assertThat(result.requireSnapshot().cpuUtilizationPercent()).isEqualTo(92);
        assertThat(result.requireSnapshot().memoryUtilizationPercent()).isEqualTo(68);
        assertThat(result.requireSnapshot().activeAlertCount()).isEqualTo(1);
        assertThat(result.requireSnapshot().source()).isEqualTo(SourceOrigin.DEMO);
    }

    @Test
    void aValidButAbsentSnapshotIsNotFoundWithItsOrigin() {
        this.endpoint.respondWithToolText(NOT_FOUND);

        MonitoringSnapshotQueryResult result = this.adapter.findLatestSnapshot("AST-900001");

        assertThat(result.outcome()).isEqualTo(QueryOutcome.NOT_FOUND);
        assertThat(result.assetId()).isEqualTo("AST-900001");
        assertThat(result.source()).isEqualTo(SourceOrigin.DEMO);
        assertThat(result.isFailed()).isFalse();
    }

    @Test
    void aRemoteSourceUnavailableBecomesUnavailable() {
        this.endpoint.respondWithToolError("{\"error\":\"MONITORING_SOURCE_UNAVAILABLE\","
                + "\"message\":\"监控数据源当前不可用\"}");

        assertThat(this.adapter.findLatestSnapshot("AST-900001").failure()).isEqualTo(QueryFailure.UNAVAILABLE);
    }

    @Test
    void aRemoteSnapshotNotFoundErrorPayloadIsNeverTreatedAsNotFound() {
        // isError=true 的失败载荷即使文案里写了「未找到」，也只是远端失败
        this.endpoint.respondWithToolError("{\"error\":\"MONITORING_SNAPSHOT_NOT_FOUND\","
                + "\"message\":\"未找到该资产的监控快照\"}");

        MonitoringSnapshotQueryResult result = this.adapter.findLatestSnapshot("AST-900001");

        assertThat(result.isNotFound()).isFalse();
        assertThat(result.failure()).isEqualTo(QueryFailure.REMOTE_TOOL_ERROR);
    }

    @Test
    void aJsonRpcErrorBecomesRemoteToolError() {
        this.endpoint.respondWithJsonRpcError(-32602, "Invalid params");

        assertThat(this.adapter.findLatestSnapshot("AST-900001").failure())
                .isEqualTo(QueryFailure.REMOTE_TOOL_ERROR);
    }

    @ParameterizedTest
    @MethodSource("illegalSnapshotPayloads")
    void anIllegalSnapshotResponseIsRejected(String payload) {
        this.endpoint.respondWithToolText(payload);

        MonitoringSnapshotQueryResult result = this.adapter.findLatestSnapshot("AST-900001");

        assertThat(result.outcome()).as("payload=%s", payload).isEqualTo(QueryOutcome.FAILED);
        assertThat(result.failure()).as("payload=%s", payload).isEqualTo(QueryFailure.INVALID_RESPONSE);
    }

    static Stream<String> illegalSnapshotPayloads() {
        return Stream.of(
                // 缺字段
                "{\"assetId\":\"AST-900001\",\"health\":\"DEGRADED\",\"cpuUtilizationPercent\":92,"
                        + "\"memoryUtilizationPercent\":68,\"activeAlertCount\":1,\"source\":\"DEMO\"}",
                // 多字段
                "{\"assetId\":\"AST-900001\",\"observedAt\":\"2026-01-01T00:00:00Z\",\"health\":\"DEGRADED\","
                        + "\"cpuUtilizationPercent\":92,\"memoryUtilizationPercent\":68,\"activeAlertCount\":1,"
                        + "\"source\":\"DEMO\",\"host\":\"db-1\"}",
                // 未知枚举
                "{\"assetId\":\"AST-900001\",\"observedAt\":\"2026-01-01T00:00:00Z\",\"health\":\"WARM\","
                        + "\"cpuUtilizationPercent\":92,\"memoryUtilizationPercent\":68,\"activeAlertCount\":1,"
                        + "\"source\":\"DEMO\"}",
                "{\"assetId\":\"AST-900001\",\"observedAt\":\"2026-01-01T00:00:00Z\",\"health\":\"degraded\","
                        + "\"cpuUtilizationPercent\":92,\"memoryUtilizationPercent\":68,\"activeAlertCount\":1,"
                        + "\"source\":\"DEMO\"}",
                // 数值越界 / 类型不对
                "{\"assetId\":\"AST-900001\",\"observedAt\":\"2026-01-01T00:00:00Z\",\"health\":\"DEGRADED\","
                        + "\"cpuUtilizationPercent\":101,\"memoryUtilizationPercent\":68,\"activeAlertCount\":1,"
                        + "\"source\":\"DEMO\"}",
                "{\"assetId\":\"AST-900001\",\"observedAt\":\"2026-01-01T00:00:00Z\",\"health\":\"DEGRADED\","
                        + "\"cpuUtilizationPercent\":-1,\"memoryUtilizationPercent\":68,\"activeAlertCount\":1,"
                        + "\"source\":\"DEMO\"}",
                "{\"assetId\":\"AST-900001\",\"observedAt\":\"2026-01-01T00:00:00Z\",\"health\":\"DEGRADED\","
                        + "\"cpuUtilizationPercent\":92.5,\"memoryUtilizationPercent\":68,\"activeAlertCount\":1,"
                        + "\"source\":\"DEMO\"}",
                "{\"assetId\":\"AST-900001\",\"observedAt\":\"2026-01-01T00:00:00Z\",\"health\":\"DEGRADED\","
                        + "\"cpuUtilizationPercent\":\"92\",\"memoryUtilizationPercent\":68,\"activeAlertCount\":1,"
                        + "\"source\":\"DEMO\"}",
                "{\"assetId\":\"AST-900001\",\"observedAt\":\"2026-01-01T00:00:00Z\",\"health\":\"DEGRADED\","
                        + "\"cpuUtilizationPercent\":92,\"memoryUtilizationPercent\":68,\"activeAlertCount\":-1,"
                        + "\"source\":\"DEMO\"}",
                // 时刻不合法
                "{\"assetId\":\"AST-900001\",\"observedAt\":\"yesterday\",\"health\":\"DEGRADED\","
                        + "\"cpuUtilizationPercent\":92,\"memoryUtilizationPercent\":68,\"activeAlertCount\":1,"
                        + "\"source\":\"DEMO\"}",
                "{\"assetId\":\"AST-900001\",\"observedAt\":null,\"health\":\"DEGRADED\","
                        + "\"cpuUtilizationPercent\":92,\"memoryUtilizationPercent\":68,\"activeAlertCount\":1,"
                        + "\"source\":\"DEMO\"}",
                // 编号错配 / source 缺失或 null
                "{\"assetId\":\"AST-900002\",\"observedAt\":\"2026-01-01T00:00:00Z\",\"health\":\"DEGRADED\","
                        + "\"cpuUtilizationPercent\":92,\"memoryUtilizationPercent\":68,\"activeAlertCount\":1,"
                        + "\"source\":\"DEMO\"}",
                "{\"assetId\":\"AST-900001\",\"observedAt\":\"2026-01-01T00:00:00Z\",\"health\":\"DEGRADED\","
                        + "\"cpuUtilizationPercent\":92,\"memoryUtilizationPercent\":68,\"activeAlertCount\":1}",
                "{\"assetId\":\"AST-900001\",\"observedAt\":\"2026-01-01T00:00:00Z\",\"health\":\"DEGRADED\","
                        + "\"cpuUtilizationPercent\":92,\"memoryUtilizationPercent\":68,\"activeAlertCount\":1,"
                        + "\"source\":null}",
                // 未找到形状不合法
                "{\"assetId\":\"AST-900001\",\"found\":false,\"error\":\"MONITORING_SNAPSHOT_FOUND\","
                        + "\"message\":\"x\",\"source\":\"DEMO\"}",
                // 不是对象
                "[1,2,3]");
    }

    @ParameterizedTest
    @ValueSource(strings = { "", " ", "AST-1", "ast-900001", "AST-900001 ", "AST 900001", "AST-9000011" })
    void invalidInputSendsNoRequestAtAll(String assetId) {
        int before = this.endpoint.postCount();

        assertThat(this.adapter.findLatestSnapshot(assetId).failure()).isEqualTo(QueryFailure.INVALID_INPUT);
        assertThat(this.endpoint.postCount()).as("assetId=[%s]", assetId).isEqualTo(before);
    }

    @Test
    void everyCallUsesTheFixedToolAndReleasesItsSession() {
        this.endpoint.respondWithToolText(HIT);
        assertThat(this.adapter.findLatestSnapshot("AST-900001").isFound()).isTrue();
        this.endpoint.respondWithToolText(NOT_FOUND);
        assertThat(this.adapter.findLatestSnapshot("AST-900001").isNotFound()).isTrue();

        assertThat(this.endpoint.calledTools()).containsOnly("monitoring_snapshot_get").hasSize(2);
        assertThat(this.endpoint.deleteCount()).as("每条路径都释放会话").isEqualTo(2);
        assertThat(this.endpoint.liveSessions()).as("不留悬挂会话").isEmpty();
    }

    @Test
    void oneUnreachableServiceDoesNotAffectTheOther() throws Exception {
        ControllableMcpEndpoint assetEndpoint = ControllableMcpEndpoint.start();
        assetEndpoint.respondWithToolText("{\"assetId\":\"AST-900001\",\"assetType\":\"SERVER\","
                + "\"status\":\"IN_SERVICE\",\"source\":\"DEMO\"}");
        try {
            McpAssetQueryAdapter assetAdapter = new McpAssetQueryAdapter(new McpToolClient(Duration.ofSeconds(2)),
                    McpServerEndpoint.of(assetEndpoint.baseUrl()));
            this.endpoint.respondWithToolText(HIT);

            // 资产服务先正常，然后停掉；监控服务全程可用
            assertThat(assetAdapter.findAsset("AST-900001").isFound()).isTrue();
            assetEndpoint.close();
            assertThat(assetAdapter.findAsset("AST-900001").failure()).isEqualTo(QueryFailure.UNAVAILABLE);

            assertThat(this.adapter.findLatestSnapshot("AST-900001").isFound())
                    .as("一个服务不可达不得影响另一个")
                    .isTrue();
        }
        finally {
            assetEndpoint.close();
        }
    }

    @Test
    void aTimeoutIsClassifiedAsTimeoutAndNotAsNotFound() {
        this.endpoint.hang();

        MonitoringSnapshotQueryResult result = this.adapter.findLatestSnapshot("AST-900001");

        assertThat(result.failure()).isEqualTo(QueryFailure.TIMEOUT);
        assertThat(result.isNotFound()).isFalse();
    }

    @Test
    void theAssetAdapterIsUnaffectedWhenOnlyMonitoringTimesOut() throws Exception {
        this.endpoint.hang();
        assertThat(this.adapter.findLatestSnapshot("AST-900001").failure()).isEqualTo(QueryFailure.TIMEOUT);

        ControllableMcpEndpoint assetEndpoint = ControllableMcpEndpoint.start();
        assetEndpoint.respondWithToolText("{\"assetId\":\"AST-900001\",\"assetType\":\"SERVER\","
                + "\"status\":\"IN_SERVICE\",\"source\":\"DEMO\"}");
        try {
            McpAssetQueryAdapter assetAdapter = new McpAssetQueryAdapter(new McpToolClient(Duration.ofSeconds(2)),
                    McpServerEndpoint.of(assetEndpoint.baseUrl()));
            AssetQueryResult result = assetAdapter.findAsset("AST-900001");
            assertThat(result.isFound()).isTrue();
        }
        finally {
            assetEndpoint.close();
        }
    }
}
