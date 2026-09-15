package com.flowdesk.bootstrap.knowledge;

import com.flowdesk.application.knowledge.view.KnowledgeCitationView;
import java.util.UUID;

/**
 * 单条引用结果的响应体（RAG 4/6）。
 *
 * <p>{@code citationId}（{@code K1}、{@code K2}……）与 {@code rank} 由服务端按最终顺序确定，
 * 调用方可以在答案里引用它并回到具体文档与切片。</p>
 *
 * <p><b>不含向量</b>：向量对调用方没有意义，也不会出现在响应里。</p>
 *
 * @param citationId      引用编号
 * @param rank            名次，从 1 连续递增
 * @param documentId      文档标识
 * @param documentVersion 文档当前版本
 * @param documentTitle   文档标题
 * @param chunkIndex      切片序号
 * @param chunkSha256     切片内容摘要
 * @param content         切片正文
 * @param score           余弦相似度
 */
public record KnowledgeCitationResponse(String citationId,
                                        int rank,
                                        UUID documentId,
                                        long documentVersion,
                                        String documentTitle,
                                        int chunkIndex,
                                        String chunkSha256,
                                        String content,
                                        double score) {

    /**
     * @param view 引用视图
     * @return 响应体
     */
    public static KnowledgeCitationResponse from(KnowledgeCitationView view) {
        return new KnowledgeCitationResponse(view.citationId(), view.rank(), view.documentId(),
                view.documentVersion(), view.documentTitle(), view.chunkIndex(), view.chunkSha256(),
                view.content(), view.score());
    }
}
