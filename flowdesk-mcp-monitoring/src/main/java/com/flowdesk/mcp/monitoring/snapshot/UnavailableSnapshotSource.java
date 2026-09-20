package com.flowdesk.mcp.monitoring.snapshot;

import java.util.Optional;

/**
 * 没有数据源时的实现（FD-0015 的<b>默认</b>模式）。
 *
 * <p>每次查询都以稳定的 {@link SnapshotSourceUnavailableException} 失败：本阶段没有接入任何
 * 真实监控系统，这是诚实的运行状态。这里刻意<b>不</b>返回 {@code Optional.empty()} ——
 * 「查过了，没有这个资产」和「我们根本没有数据」是两件事，混淆它们会把能力缺失说成业务结论。</p>
 */
public final class UnavailableSnapshotSource implements MonitoringSnapshotSource {

    @Override
    public Optional<MonitoringSnapshot> findSnapshotById(String assetId) {
        throw new SnapshotSourceUnavailableException();
    }

    @Override
    public SnapshotOrigin origin() {
        throw new SnapshotSourceUnavailableException();
    }
}
