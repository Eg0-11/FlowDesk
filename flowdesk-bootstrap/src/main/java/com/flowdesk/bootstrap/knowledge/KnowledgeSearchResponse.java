package com.flowdesk.bootstrap.knowledge;

import com.flowdesk.application.knowledge.view.KnowledgeRetrievalView;
import java.util.List;

/**
 * 知识检索响应体（RAG 4/6）。
 *
 * <p>回显本次生效的检索参数（provider / model / dimensions / topK / minScore），
 * 但<b>不回显 query</b>，也不含任何向量数值。</p>
 *
 * <p>无命中时 {@code citations} 是空列表，仍然是 200。</p>
 *
 * @param provider   向量服务提供方
 * @param model      向量模型标识
 * @param dimensions 向量维度
 * @param topK       本次生效的条数上限
 * @param minScore   本次生效的相似度下限
 * @param citations  引用结果（K1、K2……）
 */
public record KnowledgeSearchResponse(String provider,
                                      String model,
                                      int dimensions,
                                      int topK,
                                      double minScore,
                                      List<KnowledgeCitationResponse> citations) {

    /**
     * @param view 检索视图
     * @return 响应体
     */
    public static KnowledgeSearchResponse from(KnowledgeRetrievalView view) {
        return new KnowledgeSearchResponse(view.provider(), view.model(), view.dimensions(), view.topK(),
                view.minScore(), view.citations().stream().map(KnowledgeCitationResponse::from).toList());
    }
}
