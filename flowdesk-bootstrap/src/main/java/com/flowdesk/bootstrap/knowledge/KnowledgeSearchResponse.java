package com.flowdesk.bootstrap.knowledge;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.flowdesk.application.knowledge.view.KnowledgeRetrievalView;
import java.util.List;

/**
 * 知识检索响应体（RAG 4/6，RAG 6/6 增加排序模式）。
 *
 * <p>回显本次生效的检索参数（provider / model / dimensions / topK / minScore）与
 * <b>排序信息</b>（{@code rankingMode} / {@code rerankModel}），
 * 但<b>不回显 query</b>，也不含任何向量数值。</p>
 *
 * <p>{@code rankingMode} 说明这份顺序由什么决定：{@code VECTOR_SIMILARITY}（向量相似度，
 * 含「重排开启但候选不足」的情形）或 {@code RERANK}（重排分）。{@code rerankModel} 只在
 * 真正使用重排时出现（{@link JsonInclude.Include#NON_NULL}）。</p>
 *
 * <p>无命中时 {@code citations} 是空列表，仍然是 200。</p>
 *
 * @param provider    向量服务提供方
 * @param model       向量模型标识
 * @param dimensions  向量维度
 * @param topK        本次生效的条数上限
 * @param minScore    本次生效的相似度下限
 * @param rankingMode 本次证据顺序由什么决定
 * @param rerankModel 实际使用的重排模型；未使用重排时不输出
 * @param citations   引用结果（K1、K2……）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KnowledgeSearchResponse(String provider,
                                      String model,
                                      int dimensions,
                                      int topK,
                                      double minScore,
                                      String rankingMode,
                                      String rerankModel,
                                      List<KnowledgeCitationResponse> citations) {

    /**
     * @param view 检索视图
     * @return 响应体
     */
    public static KnowledgeSearchResponse from(KnowledgeRetrievalView view) {
        return new KnowledgeSearchResponse(view.provider(), view.model(), view.dimensions(), view.topK(),
                view.minScore(), view.rankingMode().name(), view.rerankModel(),
                view.citations().stream().map(KnowledgeCitationResponse::from).toList());
    }
}
