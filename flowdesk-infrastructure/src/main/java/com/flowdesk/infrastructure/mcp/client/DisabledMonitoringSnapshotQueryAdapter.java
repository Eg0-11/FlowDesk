package com.flowdesk.infrastructure.mcp.client;

import com.flowdesk.application.integration.MonitoringSnapshotQueryResult;
import com.flowdesk.application.integration.QueryFailure;
import com.flowdesk.application.integration.port.out.MonitoringSnapshotQueryPort;

/**
 * 未启用 MCP 客户端时的监控快照查询端口（FD-0016）。
 *
 * <p>与 {@link DisabledAssetQueryAdapter} 同样的策略：不连接、不请求，明确回答
 * {@link QueryFailure#DISABLED}，绝不伪装成「没有这条快照」。</p>
 */
public final class DisabledMonitoringSnapshotQueryAdapter implements MonitoringSnapshotQueryPort {

    @Override
    public MonitoringSnapshotQueryResult findLatestSnapshot(String assetId) {
        long startedAt = System.nanoTime();
        McpQueryLogger.completed(McpMonitoringSnapshotQueryAdapter.ALIAS,
                McpMonitoringSnapshotQueryAdapter.TOOL_NAME, QueryFailure.DISABLED.name(), startedAt);
        return MonitoringSnapshotQueryResult.failed(QueryFailure.DISABLED);
    }
}
