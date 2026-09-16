package com.flowdesk.application.knowledge.view;

import java.util.List;
import java.util.Objects;

/**
 * 检索结果视图：检索接口的返回形态（RAG 4/6）。
 *
 * <p>回显本次检索<b>使用的参数</b>（provider / model / dimensions / topK / minScore），
 * 这样调用方与排障者都能确认「这次用的是什么模型、什么阈值」——
 * 但<b>不回显 query 本身</b>（用户问题不需要、也不应该被服务端回抄一遍）。</p>
 *
 * <p>无命中时 {@code citations} 是空列表，仍然是成功响应（不是 404）：
 * 「检索没有结果」是正常结果，不是错误。</p>
 *
 * <h2>证据是一份不可变快照（FD-0012-R1）</h2>
 * <p>{@code citations} 在构造期被 {@link List#copyOf} 固定下来：视图一旦创建，
 * 证据集合就与调用方手里的原列表<b>再无关系</b>。这一点对问答链路尤其重要 ——
 * 审计信息（{@code usedCitationIds} 必须是本次证据的子集）如果建立在一个外部可变列表上，
 * 「证据」就可能在校验之后被改动，审计也就随之失效。在应用层视图里统一保证，
 * 比在每个 HTTP DTO 里各复制一次更可靠。</p>
 *
 * @param provider   向量服务提供方（与文档侧一致）
 * @param model      向量模型标识
 * @param dimensions 向量维度
 * @param topK       本次生效的返回条数上限
 * @param minScore   本次生效的相似度下限
 * @param citations  引用结果，按相似度降序、编号 K1、K2……（不可变，非 {@code null}）
 */
public record KnowledgeRetrievalView(String provider,
                                     String model,
                                     int dimensions,
                                     int topK,
                                     double minScore,
                                     List<KnowledgeCitationView> citations) {

    /**
     * 紧凑构造器：把证据固定成不可变列表。
     *
     * @throws NullPointerException {@code citations} 为 {@code null}，或其中含 {@code null} 元素
     */
    public KnowledgeRetrievalView {
        citations = List.copyOf(Objects.requireNonNull(citations, "citations 不能为 null"));
    }
}
