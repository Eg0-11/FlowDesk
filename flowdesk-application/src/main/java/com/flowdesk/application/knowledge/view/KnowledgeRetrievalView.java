package com.flowdesk.application.knowledge.view;

import java.util.List;
import java.util.Objects;

/**
 * 检索结果视图：检索接口的返回形态（RAG 4/6，RAG 6/6 增加排序模式）。
 *
 * <p>回显本次检索<b>使用的参数</b>（provider / model / dimensions / topK / minScore），
 * 这样调用方与排障者都能确认「这次用的是什么模型、什么阈值」——
 * 但<b>不回显 query 本身</b>（用户问题不需要、也不应该被服务端回抄一遍）。</p>
 *
 * <p>无命中时 {@code citations} 是空列表，仍然是成功响应（不是 404）：
 * 「检索没有结果」是正常结果，不是错误。</p>
 *
 * <h2>排序模式与重排模型（RAG 6/6）</h2>
 * <p>{@code rankingMode} 说明这份证据顺序<b>由什么决定</b>（向量相似度还是重排分），
 * {@code rerankModel} 说明实际使用的重排模型；两者一起构成「这个顺序可以被复核」的最小信息。
 * 重排未生效时（开关关闭，或候选只有 0/1 条而无需调用）模式为
 * {@link KnowledgeRankingMode#VECTOR_SIMILARITY} 且 {@code rerankModel} 为 {@code null} ——
 * 报告的是<b>事实</b>，不是开关状态。</p>
 *
 * <h2>证据是一份不可变快照（FD-0012-R1）</h2>
 * <p>{@code citations} 在构造期被 {@link List#copyOf} 固定下来：视图一旦创建，
 * 证据集合就与调用方手里的原列表<b>再无关系</b>。这一点对问答链路尤其重要 ——
 * 审计信息（{@code usedCitationIds} 必须是本次证据的子集）如果建立在一个外部可变列表上，
 * 「证据」就可能在校验之后被改动，审计也就随之失效。在应用层视图里统一保证，
 * 比在每个 HTTP DTO 里各复制一次更可靠。</p>
 *
 * <h2>构造期强制的不变量</h2>
 * <ul>
 *   <li>{@code citations} 非 {@code null} 且不可变；{@code rankingMode} 非 {@code null}；</li>
 *   <li>模式为 {@link KnowledgeRankingMode#RERANK} 时：{@code rerankModel} 必须有文本，
 *       且每条引用都必须带 {@code rerankScore}；</li>
 *   <li>模式为 {@link KnowledgeRankingMode#VECTOR_SIMILARITY} 时：{@code rerankModel} 必须为
 *       {@code null}，且每条引用的 {@code rerankScore} 也必须为 {@code null}。</li>
 * </ul>
 * <p>这样「声称经过重排却没有重排分」「没有重排却带着重排分」在类型层面就构造不出来。</p>
 *
 * @param provider    向量服务提供方（与文档侧一致）
 * @param model       向量模型标识
 * @param dimensions  向量维度
 * @param topK        本次生效的返回条数上限
 * @param minScore    本次生效的相似度下限
 * @param rankingMode 本次证据顺序由什么决定
 * @param rerankModel 实际使用的重排模型；未使用重排时为 {@code null}
 * @param citations   引用结果，按最终顺序、编号 K1、K2……（不可变，非 {@code null}）
 */
public record KnowledgeRetrievalView(String provider,
                                     String model,
                                     int dimensions,
                                     int topK,
                                     double minScore,
                                     KnowledgeRankingMode rankingMode,
                                     String rerankModel,
                                     List<KnowledgeCitationView> citations) {

    /**
     * 紧凑构造器：固定证据快照，并强制「排序模式与重排分必须自洽」。
     *
     * @throws NullPointerException     必需字段为 {@code null}，或证据里含 {@code null} 元素
     * @throws IllegalArgumentException 排序模式与重排模型/重排分不自洽
     */
    public KnowledgeRetrievalView {
        citations = List.copyOf(Objects.requireNonNull(citations, "citations 不能为 null"));
        Objects.requireNonNull(rankingMode, "rankingMode 不能为 null");

        if (rankingMode == KnowledgeRankingMode.RERANK) {
            if (rerankModel == null || rerankModel.isBlank()) {
                throw new IllegalArgumentException("排序模式为 RERANK 时必须给出重排模型标识");
            }
        }
        else if (rerankModel != null) {
            throw new IllegalArgumentException("未使用重排时不得给出重排模型标识");
        }

        for (KnowledgeCitationView citation : citations) {
            boolean hasRerankScore = citation.rerankScore() != null;
            if (rankingMode == KnowledgeRankingMode.RERANK && !hasRerankScore) {
                throw new IllegalArgumentException("排序模式为 RERANK 时每条引用都必须带重排分");
            }
            if (rankingMode == KnowledgeRankingMode.VECTOR_SIMILARITY && hasRerankScore) {
                throw new IllegalArgumentException("未使用重排时引用不得带重排分");
            }
        }
    }

    /**
     * 造一份「按向量相似度排序」的视图（未使用重排）。
     *
     * @param provider   向量服务提供方
     * @param model      向量模型标识
     * @param dimensions 向量维度
     * @param topK       本次生效的条数上限
     * @param minScore   本次生效的相似度下限
     * @param citations  引用结果（不得带重排分）
     * @return 检索视图
     */
    public static KnowledgeRetrievalView vectorOrdered(String provider, String model, int dimensions,
            int topK, double minScore, List<KnowledgeCitationView> citations) {

        return new KnowledgeRetrievalView(provider, model, dimensions, topK, minScore,
                KnowledgeRankingMode.VECTOR_SIMILARITY, null, citations);
    }
}
