package com.flowdesk.agent.ai;

/**
 * 一次事件研判的提示词（FD-0018-A）。
 *
 * @param systemPrompt 系统消息（只有规则）
 * @param userPrompt   用户消息（边界标记 + 结构化证据 JSON）
 */
public record IncidentTriagePrompt(String systemPrompt, String userPrompt) {
}
