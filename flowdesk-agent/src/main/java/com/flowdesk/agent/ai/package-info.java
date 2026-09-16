/**
 * FlowDesk AI 编排包：application 层 AI 用例的 Spring AI 实现。
 *
 * <p>本包负责用 {@code ChatClient} 编排提示词与本地工具，并维护请求级的工具调用记录。
 * 它只依赖 application 的抽象与一个按名称注入的 {@code ChatClient}，
 * 不直接依赖 infrastructure 模块。</p>
 *
 * <p>包内的三条链路彼此独立：</p>
 * <ul>
 *   <li><b>普通聊天</b>（{@code ChatClientAiService}）：不注册任何工具；</li>
 *   <li><b>工具冒烟</b>（{@code ChatClientAiService}）：单次请求内注册本地只读工具，
 *       验证「模型请求工具 → 真实执行 → 结果回传 → 汇总」闭环；</li>
 *   <li><b>知识库问答</b>（{@code GroundedKnowledgeAnswerService}，RAG 5/6）：
 *       先经 application 的检索用例取得证据，再由 {@code KnowledgeAnswerPromptBuilder}
 *       构造受约束的提示词，调用模型一次，最后用 {@code GroundedCitationValidator}
 *       校验答案里的引用是否全部落在本次证据内。<b>没有检索命中就不调用模型</b>，
 *       校验失败即失败（不修正、不重试）。</li>
 * </ul>
 *
 * <p>问答链路被刻意限制在「读证据 → 写答案」：不注册工具、不启用会话记忆、不做流式输出，
 * 因此提示词注入的最坏后果是一次不可用的回答，而不是被执行的指令。</p>
 */
package com.flowdesk.agent.ai;
