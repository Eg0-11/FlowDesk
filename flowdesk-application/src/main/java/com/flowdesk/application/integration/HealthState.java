package com.flowdesk.application.integration;

/**
 * 监控健康状态（FD-0016）。
 *
 * <p>取值与监控 MCP 服务已公布的契约<b>完全一致</b>，而且那边是封闭枚举
 * （{@code HealthState} 只有这四个常量），因此这里可以也应该按封闭枚举校验：
 * 出现第四个以外的取值说明契约变了，按 {@link QueryFailure#INVALID_RESPONSE} 拒绝。</p>
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
