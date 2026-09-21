package com.flowdesk.application.integration.port.out;

import com.flowdesk.application.integration.MonitoringSnapshotQueryResult;

/**
 * 监控快照查询输出端口（FD-0016）。
 *
 * <p>只读：只有一个查询方法，没有写方法。与 {@link AssetQueryPort} 相互独立 ——
 * 一个服务不可达不允许影响另一个的查询结果。</p>
 */
public interface MonitoringSnapshotQueryPort {

    /**
     * 按资产标识查询一条监控快照。
     *
     * @param assetId 资产标识（{@code AST-123456}）
     * @return 三态结果，永不为 {@code null}
     */
    MonitoringSnapshotQueryResult findLatestSnapshot(String assetId);
}
