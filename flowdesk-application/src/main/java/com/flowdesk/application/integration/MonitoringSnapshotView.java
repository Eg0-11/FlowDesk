package com.flowdesk.application.integration;

import java.time.Instant;

/**
 * 一条监控快照（FD-0016）：{@code monitoring_snapshot_get} 命中结果的框架无关表示。
 *
 * <p>字段与远端载荷一一对应，并保留远端给出的 {@code observedAt}（观测时刻）：
 * 调用方需要知道「这是什么时候的数据」，而不是把这个信息丢在解析层。</p>
 *
 * <p>不变量在构造期校验，与监控服务自己的模型保持一致：百分比 {@code 0..100}、
 * 告警数非负、{@code health} 是封闭枚举、{@code source} 必须存在。
 * 类型上<b>没有</b> IP、主机名、凭证等字段，因此它们不可能被顺手带进后续链路。</p>
 *
 * @param assetId                  资产标识
 * @param observedAt               观测时刻（远端给定，UTC）
 * @param health                   健康状态
 * @param cpuUtilizationPercent    CPU 使用率（0..100）
 * @param memoryUtilizationPercent 内存使用率（0..100）
 * @param activeAlertCount         活跃告警数（&gt;= 0）
 * @param source                   这条记录来自哪里（必须存在）
 */
public record MonitoringSnapshotView(
        String assetId,
        Instant observedAt,
        HealthState health,
        int cpuUtilizationPercent,
        int memoryUtilizationPercent,
        int activeAlertCount,
        SourceOrigin source) {

    /**
     * @throws IllegalArgumentException 任一不变量被破坏
     */
    public MonitoringSnapshotView {
        if (!AssetIdentifier.isValid(assetId)) {
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
        if (source == null) {
            throw new IllegalArgumentException("source 不能为空");
        }
    }

    private static void requirePercent(int value, String field) {
        if (value < 0 || value > 100) {
            throw new IllegalArgumentException(field + " 必须在 0..100 之间");
        }
    }
}
