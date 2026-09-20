package com.flowdesk.mcp.monitoring.snapshot;

/**
 * 监控健康状态（FD-0015）。
 *
 * <p>只允许四个取值，且与工具输出里的字符串<b>完全一致</b>：{@code HEALTHY}、{@code DEGRADED}、
 * {@code CRITICAL}、{@code UNKNOWN}。枚举而不是自由字符串的理由很简单：
 * 「健康度」这种字段一旦允许任意取值，调用方就没法可靠地做判断 ——
 * 未知取值必须在构造快照时就被拒绝，而不是让调用方去猜。</p>
 */
public enum HealthState {

    /** 指标正常。 */
    HEALTHY,

    /** 指标偏离正常范围，但仍在服务。 */
    DEGRADED,

    /** 已不可用或需要立即处置。 */
    CRITICAL,

    /** 采集不到足够信息，无法判断。 */
    UNKNOWN
}
