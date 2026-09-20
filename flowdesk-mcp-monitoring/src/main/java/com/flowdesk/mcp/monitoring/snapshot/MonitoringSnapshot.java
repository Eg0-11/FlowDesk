package com.flowdesk.mcp.monitoring.snapshot;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * 一条监控快照（FD-0015）。
 *
 * <p>这是<b>只读</b>数据：record 不可变，且不变量在构造时就校验，因此「百分比 120%」或
 * 「告警数 -3」这类数据根本进不了系统 —— 而不是等到工具输出时才发现。</p>
 *
 * <h2>不变量（构造期强制）</h2>
 * <ul>
 *   <li>{@code assetId}：非空且形如 {@code AST-123456}（复用 {@link AssetId} 的判定）；</li>
 *   <li>{@code observedAt}：非空时刻（必须来自数据源，而不是工具层临时取当前时间）；</li>
 *   <li>{@code health}：非空，取值受 {@link HealthState} 限制；</li>
 *   <li>{@code cpuUtilizationPercent} / {@code memoryUtilizationPercent}：{@code 0..100}；</li>
 *   <li>{@code activeAlertCount}：{@code >= 0}；</li>
 *   <li>{@code origin}：非空，说明这条记录来自哪里。</li>
 * </ul>
 *
 * <p><b>不包含</b> IP、主机名、内部地址、凭证或异常信息 —— 这些字段在这个类型上根本不存在，
 * 因此不可能被工具「顺手」带出去。</p>
 *
 * @param assetId               资产标识
 * @param observedAt            观测时刻（UTC；由数据源给定）
 * @param health                健康状态
 * @param cpuUtilizationPercent CPU 使用率（0..100）
 * @param memoryUtilizationPercent 内存使用率（0..100）
 * @param activeAlertCount      活跃告警数量（>= 0）
 * @param origin                来源标识
 */
public record MonitoringSnapshot(
        String assetId,
        Instant observedAt,
        HealthState health,
        int cpuUtilizationPercent,
        int memoryUtilizationPercent,
        int activeAlertCount,
        SnapshotOrigin origin) {

    /**
     * 校验不变量。
     *
     * @throws IllegalArgumentException 任一不变量被破坏
     */
    public MonitoringSnapshot {
        if (!AssetId.isValid(assetId)) {
            throw new IllegalArgumentException("assetId 必须形如 AST-123456");
        }
        if (observedAt == null) {
            throw new IllegalArgumentException("observedAt 不能为空");
        }
        if (health == null) {
            throw new IllegalArgumentException("health 不能为空");
        }
        requirePercent(cpuUtilizationPercent, "cpuUtilizationPercent");
        requirePercent(memoryUtilizationPercent, "memoryUtilizationPercent");
        if (activeAlertCount < 0) {
            throw new IllegalArgumentException("activeAlertCount 不能为负数");
        }
        if (origin == null) {
            throw new IllegalArgumentException("origin 不能为空");
        }
    }

    /**
     * 观测时刻的 ISO-8601 文本（工具输出里就是这个形状）。
     *
     * @return 例如 {@code 2026-01-01T00:00:00Z}
     */
    public String observedAtIso() {
        return DateTimeFormatter.ISO_INSTANT.format(this.observedAt);
    }

    private static void requirePercent(int value, String field) {
        if (value < 0 || value > 100) {
            throw new IllegalArgumentException(field + " 必须在 0..100 之间");
        }
    }
}
