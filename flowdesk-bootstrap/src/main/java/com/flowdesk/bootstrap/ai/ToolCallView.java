package com.flowdesk.bootstrap.ai;

/**
 * 单次工具调用视图。
 *
 * @param name    工具名
 * @param success 是否执行成功
 */
public record ToolCallView(String name, boolean success) {
}
