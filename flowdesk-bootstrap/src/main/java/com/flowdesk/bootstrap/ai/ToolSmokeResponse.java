package com.flowdesk.bootstrap.ai;

import java.util.List;

/**
 * 工具调用冒烟响应体。
 *
 * @param requestId  服务端生成的请求标识
 * @param answer     模型基于工具结果生成的最终回答
 * @param toolCalled 是否发生过成功的真实工具调用
 * @param toolCalls  真实工具调用明细
 */
public record ToolSmokeResponse(String requestId,
                                String answer,
                                boolean toolCalled,
                                List<ToolCallView> toolCalls) {

    public ToolSmokeResponse {
        toolCalls = List.copyOf(toolCalls);
    }
}
