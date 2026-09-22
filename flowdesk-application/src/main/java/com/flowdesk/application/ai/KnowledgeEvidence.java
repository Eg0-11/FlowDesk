package com.flowdesk.application.ai;

import com.flowdesk.application.knowledge.view.KnowledgeCitationView;
import com.flowdesk.application.knowledge.view.KnowledgeRetrievalView;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 知识证据分支的三态结果（FD-0018-A）。
 *
 * <table border="1">
 *   <caption>三态</caption>
 *   <tr><th>状态</th><th>含义</th><th>携带</th></tr>
 *   <tr><td>{@link Status#FOUND}</td><td>检索成功且命中至少一条切片</td>
 *       <td>不可变的 {@link KnowledgeRetrievalView}（{@code citations} 非空）</td></tr>
 *   <tr><td>{@link Status#NOT_FOUND}</td><td>检索成功但一条都没命中</td>
 *       <td>检索视图（{@code citations} 为空）——「查过了没有」也要能审计</td></tr>
 *   <tr><td>{@link Status#FAILED}</td><td>检索没有完成</td>
 *       <td>稳定的 {@link KnowledgeFailure}；<b>没有</b>检索视图</td></tr>
 * </table>
 *
 * <p>三态互斥、且每种状态携带的数据形状固定：{@code FAILED} 不可能携带视图（否则「没查成」
 * 就有了看起来正常的证据），{@code FOUND} 不可能没有切片。</p>
 *
 * @param status    三态
 * @param retrieval 检索视图；{@code FOUND}/{@code NOT_FOUND} 时非 null
 * @param failure   失败分类；仅 {@code FAILED} 时非 null
 */
public record KnowledgeEvidence(Status status, KnowledgeRetrievalView retrieval, KnowledgeFailure failure) {

    /** 知识证据分支的三态。 */
    public enum Status {

        /** 检索成功且命中。 */
        FOUND,

        /** 检索成功但没有命中。 */
        NOT_FOUND,

        /** 检索没有完成。 */
        FAILED
    }

    /**
     * @throws NullPointerException     必需字段为 {@code null}
     * @throws IllegalArgumentException 状态与携带的数据自相矛盾
     */
    public KnowledgeEvidence {
        Objects.requireNonNull(status, "status 不能为 null");
        switch (status) {
            case FOUND -> {
                if (retrieval == null || retrieval.citations().isEmpty() || failure != null) {
                    throw new IllegalArgumentException("FOUND 必须携带非空的检索视图，且不得携带失败分类");
                }
            }
            case NOT_FOUND -> {
                if (retrieval == null || !retrieval.citations().isEmpty() || failure != null) {
                    throw new IllegalArgumentException("NOT_FOUND 必须携带空的检索视图，且不得携带失败分类");
                }
            }
            case FAILED -> {
                if (retrieval != null || failure == null) {
                    throw new IllegalArgumentException("FAILED 必须携带失败分类，且不得携带检索视图");
                }
            }
        }
    }

    /**
     * @param retrieval 命中的检索视图（{@code citations} 非空）
     * @return 命中状态
     */
    public static KnowledgeEvidence found(KnowledgeRetrievalView retrieval) {
        return new KnowledgeEvidence(Status.FOUND, retrieval, null);
    }

    /**
     * @param retrieval 检索视图（{@code citations} 必须为空）
     * @return 未命中状态
     */
    public static KnowledgeEvidence notFound(KnowledgeRetrievalView retrieval) {
        return new KnowledgeEvidence(Status.NOT_FOUND, retrieval, null);
    }

    /**
     * @param failure 稳定失败分类
     * @return 失败状态
     */
    public static KnowledgeEvidence failed(KnowledgeFailure failure) {
        return new KnowledgeEvidence(Status.FAILED, null, failure);
    }

    /** @return 是否命中 */
    public boolean isFound() {
        return this.status == Status.FOUND;
    }

    /** @return 是否「检索成功但没有命中」 */
    public boolean isNotFound() {
        return this.status == Status.NOT_FOUND;
    }

    /** @return 是否失败（不可用或出错） */
    public boolean isFailed() {
        return this.status == Status.FAILED;
    }

    /**
     * 本次可引用的知识证据（失败或未命中时为空列表）。
     *
     * @return 引用视图列表（顺序即检索给出的最终顺序）
     */
    public List<KnowledgeCitationView> citations() {
        return this.retrieval == null ? List.of() : this.retrieval.citations();
    }

    /**
     * 本次可引用的知识编号（{@code K1}…{@code Kn}）。
     *
     * @return 编号列表
     */
    public List<String> citationIds() {
        List<String> ids = new ArrayList<>();
        for (KnowledgeCitationView citation : citations()) {
            ids.add(citation.citationId());
        }
        return List.copyOf(ids);
    }
}
