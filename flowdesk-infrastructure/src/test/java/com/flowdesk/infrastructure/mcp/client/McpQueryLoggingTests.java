package com.flowdesk.infrastructure.mcp.client;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flowdesk.application.integration.AssetQueryResult;
import com.flowdesk.application.integration.MonitoringSnapshotQueryResult;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * 查询日志的形状与脱敏（FD-0016）。
 *
 * <p>日志只允许记录：<b>服务别名、固定工具名、结果分类、耗时</b>。
 * 这里用与监控服务同一套「结构化断言」办法把这件事钉死：模板必须是唯一的固定文案，
 * 而且<b>每一个参数位</b>都被占满（别名白名单、工具名白名单、结果分类白名单、
 * 唯一的数值参数耗时），因此 assetId、快照数值、完整响应与异常消息没有任何位置可放。</p>
 *
 * <p>文本层只扫不可能与数字撞车的哨兵（assetId、{@code DEGRADED}、演示记录里的其它文本），
 * 耗时具体是多少从不参与断言。</p>
 */
class McpQueryLoggingTests {

    private static final String TEMPLATE = "mcp query completed alias={} tool={} result={} durationMs={}";

    private static final String ASSET_HIT = "{\"assetId\":\"AST-900001\",\"assetType\":\"SERVER\","
            + "\"status\":\"IN_SERVICE\",\"source\":\"DEMO\"}";

    private static final String SNAPSHOT_HIT = "{\"assetId\":\"AST-900001\","
            + "\"observedAt\":\"2026-01-01T00:00:00Z\",\"health\":\"DEGRADED\","
            + "\"cpuUtilizationPercent\":92,\"memoryUtilizationPercent\":68,\"activeAlertCount\":1,"
            + "\"source\":\"DEMO\"}";

    private ControllableMcpEndpoint endpoint;

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void startEndpoint() throws Exception {
        this.endpoint = ControllableMcpEndpoint.start();
        this.appender.start();
        logger().addAppender(this.appender);
    }

    @AfterEach
    void stopEndpoint() {
        logger().detachAppender(this.appender);
        this.appender.stop();
        this.endpoint.close();
    }

    @Test
    void aSuccessfulAssetQueryLogsOnlyMetadata() {
        this.endpoint.respondWithToolText(ASSET_HIT);

        AssetQueryResult result = assetAdapter().findAsset("AST-900001");

        assertThat(result.requireAsset().status()).as("先证明真的命中了（否则下面的断言没有对象）")
                .isEqualTo("IN_SERVICE");
        assertLogIsMetadataOnly("asset", "asset_get", "FOUND");
    }

    @Test
    void aSuccessfulSnapshotQueryLogsOnlyMetadata() {
        this.endpoint.respondWithToolText(SNAPSHOT_HIT);

        MonitoringSnapshotQueryResult result = monitoringAdapter().findLatestSnapshot("AST-900001");

        assertThat(result.requireSnapshot().cpuUtilizationPercent()).isEqualTo(92);
        assertLogIsMetadataOnly("monitoring", "monitoring_snapshot_get", "FOUND");
    }

    @Test
    void everyFailureKindIsLoggedAsItsClassificationWithoutAnyDetail() {
        assetAdapter().findAsset("not-an-asset-id");                       // INVALID_INPUT（零请求）
        this.endpoint.respondWithToolError("{\"error\":\"ASSET_SOURCE_UNAVAILABLE\",\"message\":\"x\"}");
        assetAdapter().findAsset("AST-900001");                            // UNAVAILABLE
        this.endpoint.respondWithJsonRpcError(-32601, "Method not found: asset_get");
        assetAdapter().findAsset("AST-900001");                            // REMOTE_TOOL_ERROR
        this.endpoint.respondWithToolText("{\"assetId\":\"AST-900002\",\"assetType\":\"SERVER\","
                + "\"status\":\"IN_SERVICE\",\"source\":\"DEMO\"}");
        assetAdapter().findAsset("AST-900001");                            // INVALID_RESPONSE（编号错配）

        assertThat(this.appender.list).hasSize(4);
        assertThat(results()).containsExactly("INVALID_INPUT", "UNAVAILABLE", "REMOTE_TOOL_ERROR",
                "INVALID_RESPONSE");
        assertLogIsMetadataOnly("asset", "asset_get", "INVALID_INPUT", "UNAVAILABLE", "REMOTE_TOOL_ERROR",
                "INVALID_RESPONSE");
    }

    /** 结构化断言：模板 + 每个参数位都必须是固定词汇，唯一的数值参数只能是耗时。 */
    private void assertLogIsMetadataOnly(String alias, String toolName, String... allowedResults) {
        assertThat(this.appender.list).as("这次调用确实产生了日志").isNotEmpty();

        for (ILoggingEvent event : this.appender.list) {
            Object[] arguments = event.getArgumentArray();

            assertThat(event.getMessage())
                    .as("日志模板必须唯一且固定，模板本身不得携带任何动态数据")
                    .isEqualTo(TEMPLATE);
            assertThat(arguments).as("参数形状固定").hasSize(4);
            assertThat(arguments[0]).as("参数位 0 只能是服务别名").isEqualTo(alias);
            assertThat(arguments[1]).as("参数位 1 只能是固定工具名").isEqualTo(toolName);
            assertThat(arguments[2]).as("参数位 2 只能是结果分类").isInstanceOf(String.class)
                    .isIn((Object[]) allowedResults);
            assertThat(arguments[3]).as("参数位 3 是唯一的数值参数（耗时），只断言是数字")
                    .isInstanceOf(Number.class);
        }

        assertThat(messages())
                .as("日志文本里不得出现 assetId、监控数值或演示记录正文")
                .noneMatch(message -> message.contains("AST-900001")
                        || message.contains("AST-900002")
                        || message.contains("SERVER")
                        || message.contains("IN_SERVICE")
                        || message.contains("DEGRADED")
                        || message.contains("observedAt")
                        || message.contains("source"));
    }

    private List<String> messages() {
        return this.appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(java.util.Objects::nonNull).toList();
    }

    private List<String> results() {
        return this.appender.list.stream().map(event -> String.valueOf(event.getArgumentArray()[2])).toList();
    }

    private McpAssetQueryAdapter assetAdapter() {
        return new McpAssetQueryAdapter(new McpToolClient(Duration.ofSeconds(2)),
                McpServerEndpoint.of(this.endpoint.baseUrl()));
    }

    private McpMonitoringSnapshotQueryAdapter monitoringAdapter() {
        return new McpMonitoringSnapshotQueryAdapter(new McpToolClient(Duration.ofSeconds(2)),
                McpServerEndpoint.of(this.endpoint.baseUrl()));
    }

    private static Logger logger() {
        return (Logger) LoggerFactory.getLogger(McpQueryLogger.class);
    }
}
