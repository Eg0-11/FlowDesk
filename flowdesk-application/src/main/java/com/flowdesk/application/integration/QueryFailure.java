package com.flowdesk.application.integration;

/**
 * 查询失败的应用层分类（FD-0016）。
 *
 * <p>这些分类是<b>稳定契约</b>：调用方（后续的 Agent 或编排逻辑）按分类决定「要不要重试、
 * 要不要降级、要不要把它当成数据缺失」，因此它们不随传输实现变化，也不携带任何远端原文。</p>
 *
 * <p>与 {@link QueryOutcome#NOT_FOUND} 的边界：本枚举里的每一项都是「这次查询没有成功」，
 * 任何一种都<b>不得</b>被当作「没有这条数据」。</p>
 */
public enum QueryFailure {

    /** 输入不合法（例如 assetId 不符合 {@code AST-} 加六位数字）：在发出任何请求之前就已拒绝。 */
    INVALID_INPUT,

    /** 该集成未启用（{@code flowdesk.mcp.client.enabled=false}）：没有创建客户端，也没有发出请求。 */
    DISABLED,

    /** 初始化或调用在配置的上界内没有拿到响应。 */
    TIMEOUT,

    /** 服务不可达、连接被拒、传输层错误，或远端声明自己的数据源不可用。 */
    UNAVAILABLE,

    /** 远端回答了，但内容不符合已公布的载荷契约（缺字段、类型错、枚举未知、编号错配、{@code source} 缺失等）。 */
    INVALID_RESPONSE,

    /** 远端明确拒绝了这次工具调用（JSON-RPC 错误，或工具结果声明失败且错误码不属于已知的「数据源不可用」）。 */
    REMOTE_TOOL_ERROR
}
