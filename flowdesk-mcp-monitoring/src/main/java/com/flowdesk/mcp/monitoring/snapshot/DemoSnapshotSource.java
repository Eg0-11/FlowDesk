package com.flowdesk.mcp.monitoring.snapshot;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * 内置虚构演示数据源（FD-0015）。
 *
 * <p>数据是<b>固定</b>的：{@code observedAt} 也是固定时刻，工具不读取系统当前时间，
 * 因此同一次输入永远得到逐字节相同的输出，测试是确定性的，演示也是可复现的。</p>
 *
 * <table border="1">
 *   <caption>固定演示记录</caption>
 *   <tr><th>assetId</th><th>health</th><th>CPU</th><th>内存</th><th>告警数</th><th>observedAt</th></tr>
 *   <tr><td>{@code AST-900001}</td><td>DEGRADED</td><td>92</td><td>68</td><td>1</td>
 *       <td>2026-01-01T00:00:00Z</td></tr>
 *   <tr><td>{@code AST-900002}</td><td>HEALTHY</td><td>18</td><td>35</td><td>0</td>
 *       <td>2026-01-01T00:05:00Z</td></tr>
 *   <tr><td>{@code AST-900003}</td><td colspan="5">没有快照：用于验证「合法但未找到」这条路径</td></tr>
 * </table>
 *
 * <p>{@code AST-9xxxxx} 是明显的保留段，演示数据不会冒充真实监控；所有结果都带
 * {@code source=DEMO}，而且演示模式必须<b>显式</b>打开。</p>
 */
public final class DemoSnapshotSource implements MonitoringSnapshotSource {

    /** 演示数据：assetId → 快照。 */
    private static final Map<String, MonitoringSnapshot> SNAPSHOTS = Map.of(
            "AST-900001", new MonitoringSnapshot("AST-900001", Instant.parse("2026-01-01T00:00:00Z"),
                    HealthState.DEGRADED, 92, 68, 1, SnapshotOrigin.DEMO),
            "AST-900002", new MonitoringSnapshot("AST-900002", Instant.parse("2026-01-01T00:05:00Z"),
                    HealthState.HEALTHY, 18, 35, 0, SnapshotOrigin.DEMO));

    @Override
    public Optional<MonitoringSnapshot> findSnapshotById(String assetId) {
        return Optional.ofNullable(SNAPSHOTS.get(assetId));
    }

    @Override
    public SnapshotOrigin origin() {
        return SnapshotOrigin.DEMO;
    }
}
