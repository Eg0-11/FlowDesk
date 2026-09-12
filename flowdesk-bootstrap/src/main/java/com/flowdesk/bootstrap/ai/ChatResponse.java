package com.flowdesk.bootstrap.ai;

/**
 * 普通聊天响应体。
 *
 * @param requestId 服务端生成的请求标识
 * @param answer    模型回答
 */
public record ChatResponse(String requestId, String answer) {
}
