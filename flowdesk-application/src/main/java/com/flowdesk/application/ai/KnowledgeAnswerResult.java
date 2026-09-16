package com.flowdesk.application.ai;

import com.flowdesk.application.knowledge.view.KnowledgeCitationView;
import com.flowdesk.application.knowledge.view.KnowledgeRetrievalView;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 知识库问答结果（RAG 5/6）。
 *
 * <p>{@code answer} 是模型依据本次检索证据生成的答案，其中每个结论都带 {@code [K1]} 形式的引用；
 * {@code usedCitationIds} 是答案<b>实际引用</b>的证据编号（按首次出现顺序、已去重），
 * {@code retrieval} 是本次检索的<b>完整证据</b>（含未被引用的切片）。两者语义不同：
 * 「检索到了什么」与「答案用了什么」必须能被分开审计。</p>
 *
 * <p>无检索证据时：{@code grounded=false}、{@code answer} 是固定降级文案、
 * {@code usedCitationIds} 与 {@code retrieval.citations()} 均为空 —— 此时<b>不会调用模型</b>。</p>
 *
 * <h2>不变量（构造期强制）</h2>
 * <ul>
 *   <li>{@code requestId}、{@code answer}、{@code usedCitationIds}、{@code retrieval} 都不得为 {@code null}；</li>
 *   <li>{@code usedCitationIds} 被<b>防御性复制</b>为不可变列表（外部持有原列表也无法修改结果）；</li>
 *   <li>{@code usedCitationIds} 必须是 {@code retrieval.citations()} 中出现的编号的<b>子集</b> ——
 *       「引用了本次没有给出的证据」在类型层面就不可表达。</li>
 * </ul>
 *
 * @param requestId       服务端生成的请求标识（便于与日志、错误响应对齐）
 * @param answer          模型答案（无证据时为固定降级文案）
 * @param grounded        是否基于检索证据作答
 * @param usedCitationIds 答案实际引用的证据编号，按首次出现顺序去重
 * @param retrieval       本次检索的完整证据（可能没有命中）
 */
public record KnowledgeAnswerResult(String requestId,
                                    String answer,
                                    boolean grounded,
                                    List<String> usedCitationIds,
                                    KnowledgeRetrievalView retrieval) {

    /**
     * 紧凑构造器：防御性复制集合，并强制「引用必须是本次证据的子集」。
     *
     * @throws NullPointerException     任一必需字段为 {@code null}
     * @throws IllegalArgumentException {@code usedCitationIds} 出现本次证据之外的编号
     */
    public KnowledgeAnswerResult {
        Objects.requireNonNull(requestId, "requestId 不能为 null");
        Objects.requireNonNull(answer, "answer 不能为 null");
        Objects.requireNonNull(retrieval, "retrieval 不能为 null");
        usedCitationIds = List.copyOf(Objects.requireNonNull(usedCitationIds, "usedCitationIds 不能为 null"));

        Set<String> available = new HashSet<>();
        for (KnowledgeCitationView citation : retrieval.citations()) {
            available.add(citation.citationId());
        }
        for (String used : usedCitationIds) {
            if (!available.contains(used)) {
                throw new IllegalArgumentException("usedCitationIds 必须是本次检索证据的子集");
            }
        }
    }

    /**
     * @return 本次检索命中的切片数量
     */
    public int retrievedCitationCount() {
        return this.retrieval.citations().size();
    }

    /**
     * @return 答案实际引用的证据数量
     */
    public int usedCitationCount() {
        return this.usedCitationIds.size();
    }
}
