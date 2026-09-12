package com.flowdesk.application.ai;

import java.util.List;

/**
 * 工具调用冒烟结果。
 *
 * @param requestId  服务端生成的请求标识
 * @param answer     模型最终回答
 * @param toolCalled 是否发生过成功的真实工具调用
 * @param toolCalls  本次请求内真实发生的工具调用明细
 */
public record ToolSmokeResult(String requestId,
                              String answer,
                              boolean toolCalled,
                              List<ToolCallOutcome> toolCalls) {

    public ToolSmokeResult {
        toolCalls = List.copyOf(toolCalls);
    }
}
