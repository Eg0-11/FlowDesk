package com.flowdesk.application.ai;

/**
 * 工具调用冒烟用例：单轮请求内注册一个本地只读工具，并验证
 * “模型调用工具 → 工具执行 → 模型基于工具结果再次生成最终回答”的完整闭环。
 *
 * @see AiToolSmokeUseCase#toolSmoke(ToolSmokeCommand)
 */
public interface AiToolSmokeUseCase {

    /**
     * 执行一次工具调用冒烟。
     *
     * @param command 冒烟命令，指定工单类型
     * @return 结果，其中 {@code toolCalled} 必须来自真实的 Java 工具执行记录
     * @throws AiRequestException  工单类型不合法
     * @throws AiProviderException 上游模型服务调用失败
     */
    ToolSmokeResult toolSmoke(ToolSmokeCommand command);
}
