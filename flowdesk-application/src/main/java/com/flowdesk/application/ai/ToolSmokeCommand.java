package com.flowdesk.application.ai;

/**
 * 工具调用冒烟命令。
 *
 * @param issueType 工单类型原始文本，合法值见 {@link IssueType}
 */
public record ToolSmokeCommand(String issueType) {
}
