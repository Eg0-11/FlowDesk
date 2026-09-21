package com.flowdesk.application.integration;

/**
 * 数据来源标识（FD-0016）。
 *
 * <p>两个 MCP 服务的结果里都带 {@code source}，取值只有这两个：</p>
 * <ul>
 *   <li>{@link #DEMO}：内置<b>虚构</b>演示数据，不是真实企业资产或真实监控系统；</li>
 *   <li>{@link #REAL}：真实数据源（本阶段两个服务都还没有真实数据源）。</li>
 * </ul>
 *
 * <p>本枚举是「如实转述」而不是「推断」：远端说什么就保留什么，客户端<b>不得</b>把
 * {@code DEMO} 提升成 {@code REAL}，也不得在缺失时补一个默认值 —— 那样就等于伪造血缘。
 * 无法解析的取值按 {@link QueryFailure#INVALID_RESPONSE} 处理。</p>
 */
public enum SourceOrigin {

    /** 内置虚构演示数据。 */
    DEMO,

    /** 真实数据源。 */
    REAL
}
