package com.flowdesk.agent.ai;

/**
 * 一次真实发生的工具调用记录。
 *
 * @param name    工具名
 * @param success 是否执行成功
 */
public record ToolInvocation(String name, boolean success) {
}
