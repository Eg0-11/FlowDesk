package com.flowdesk.application.ai;

/**
 * 基于检索证据的知识库问答用例输入端口（RAG 5/6）。
 *
 * <p>语义：把「用户问题」变成「有引用、可审计的答案」——
 * 检索证据 → 防注入的提示词 → 一次模型调用 → 引用校验 → 答案 + 实际引用 + 完整证据。</p>
 *
 * <h2>实现必须遵守的顺序与约束</h2>
 * <ol>
 *   <li>先调用 {@link com.flowdesk.application.knowledge.port.in.RetrieveKnowledgeUseCase#retrieve}，
 *       检索失败<b>原样传播</b>（{@code KnowledgeApplicationException}），且<b>不调用模型</b>；</li>
 *   <li>没有命中时<b>不调用模型</b>，返回固定降级答案、{@code grounded=false}、空引用列表与空证据；</li>
 *   <li>有命中时生成 {@code requestId}，只调用模型一次（<b>不重试</b>、不注册工具、不使用会话记忆）；</li>
 *   <li>模型答案必须通过引用校验（非空、至少一个规范引用、引用必须在本次证据内），
 *       否则抛 {@link AiProviderException}（HTTP 502 + {@code requestId}）；</li>
 *   <li>答案只能引用本次检索给出的 {@code citationId}，禁止编造、漏写或改写引用。</li>
 * </ol>
 *
 * <p>输入的规范化与边界校验<b>不在实现里重复</b>：{@link KnowledgeAnswerCommand} 被原样交给检索用例，
 * 保证 {@code /ai/knowledge-answer} 与 {@code /knowledge/search} 的输入语义完全一致。</p>
 */
public interface KnowledgeAnswerUseCase {

    /**
     * 依据知识库检索结果回答问题。
     *
     * @param command 问答命令（query / topK / minScore）
     * @return 答案、实际引用编号与完整检索证据
     * @throws AiProviderException                                 模型调用失败，或答案未通过引用校验
     * @throws com.flowdesk.application.knowledge.KnowledgeApplicationException 检索阶段的失败（原样传播）
     */
    KnowledgeAnswerResult answer(KnowledgeAnswerCommand command);
}
