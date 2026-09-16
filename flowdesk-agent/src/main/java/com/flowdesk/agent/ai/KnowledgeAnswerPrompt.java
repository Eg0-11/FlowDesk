package com.flowdesk.agent.ai;

/**
 * 一次问答请求的提示词：<b>系统指令</b>与<b>用户数据</b>分别承载。
 *
 * <p>两条消息必须分开发送（{@code ChatClient.prompt().system(...).user(...)}）：
 * 系统消息只放规则，用户消息只放「本轮的不可信数据」（问题 + 证据切片）。
 * 把知识正文拼进系统消息，等于让被检索到的文档内容和系统规则处在同一个信任层级上 ——
 * 那是提示词注入最容易得手的位置。</p>
 *
 * @param systemPrompt 系统指令（规则，不含任何被检索内容）
 * @param userPrompt   用户消息（问题、允许的引用清单、证据切片、上下文边界）
 */
public record KnowledgeAnswerPrompt(String systemPrompt, String userPrompt) {
}
