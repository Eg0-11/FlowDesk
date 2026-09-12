package com.flowdesk.application.ai;

/**
 * 普通聊天结果。
 *
 * @param requestId 服务端生成的请求标识
 * @param answer    模型最终回答
 */
public record ChatResult(String requestId, String answer) {
}
