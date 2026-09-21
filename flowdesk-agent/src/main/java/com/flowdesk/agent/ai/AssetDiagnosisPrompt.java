package com.flowdesk.agent.ai;

/**
 * 一次资产诊断的提示词（FD-0017-A）。
 *
 * <p>系统消息只放规则，用户消息放结构化数据 —— 两者分开是因为「规则」与「数据」一旦混在一起，
 * 数据里的内容就有机会在文本上冒充规则。</p>
 *
 * @param systemPrompt 系统消息（规则）
 * @param userPrompt   用户消息（结构化证据）
 */
public record AssetDiagnosisPrompt(String systemPrompt, String userPrompt) {
}
