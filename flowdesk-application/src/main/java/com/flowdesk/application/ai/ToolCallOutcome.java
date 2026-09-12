package com.flowdesk.application.ai;

/**
 * 单次工具调用的执行结果。
 *
 * <p>本对象只能由真实的工具执行路径产生，不得根据模型回答文本推断。</p>
 *
 * @param name    工具名
 * @param success 是否执行成功
 */
public record ToolCallOutcome(String name, boolean success) {
}
