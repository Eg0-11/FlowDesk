package com.flowdesk.infrastructure.mcp.client;

import com.flowdesk.application.integration.AssetIdentifier;
import com.flowdesk.application.integration.MonitoringSnapshotQueryResult;
import com.flowdesk.application.integration.QueryFailure;
import com.flowdesk.application.integration.port.out.MonitoringSnapshotQueryPort;

/**
 * 监控快照查询的 MCP 适配器（FD-0016）。
 *
 * <p>固定工具 {@code monitoring_snapshot_get}，固定入参名 {@code assetId}。
 * 与资产适配器<b>完全独立</b>：各自持有自己的端点与客户端，一个服务不可达不会让另一个查询失败。</p>
 */
public final class McpMonitoringSnapshotQueryAdapter implements MonitoringSnapshotQueryPort {

    /** 日志里的服务别名。 */
    static final String ALIAS = "monitoring";

    /** 固定工具名。 */
    static final String TOOL_NAME = "monitoring_snapshot_get";

    private final McpToolClient client;

    private final McpServerEndpoint endpoint;

    /**
     * @param client   MCP 工具客户端
     * @param endpoint 已校验的监控服务端点
     */
    public McpMonitoringSnapshotQueryAdapter(McpToolClient client, McpServerEndpoint endpoint) {
        this.client = client;
        this.endpoint = endpoint;
    }

    @Override
    public MonitoringSnapshotQueryResult findLatestSnapshot(String assetId) {
        long startedAt = System.nanoTime();

        if (!AssetIdentifier.isValid(assetId)) {
            return log(startedAt, MonitoringSnapshotQueryResult.failed(QueryFailure.INVALID_INPUT));
        }

        try {
            McpToolResponse response = this.client.call(this.endpoint, TOOL_NAME, assetId);
            return log(startedAt, SnapshotPayloadParser.parse(assetId, response));
        }
        catch (McpClientFailureException ex) {
            return log(startedAt, MonitoringSnapshotQueryResult.failed(ex.failure()));
        }
    }

    private static MonitoringSnapshotQueryResult log(long startedAt, MonitoringSnapshotQueryResult result) {
        McpQueryLogger.completed(ALIAS, TOOL_NAME, label(result), startedAt);
        return result;
    }

    private static String label(MonitoringSnapshotQueryResult result) {
        return result.isFailed() ? result.failure().name() : result.outcome().name();
    }
}
