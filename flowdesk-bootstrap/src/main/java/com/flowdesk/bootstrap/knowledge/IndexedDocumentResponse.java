package com.flowdesk.bootstrap.knowledge;

import com.flowdesk.application.knowledge.view.IndexedDocumentView;
import com.flowdesk.domain.knowledge.KnowledgeDocumentStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * 索引结果响应体。
 *
 * <p><b>刻意不包含</b>切片正文、向量数组、内容键、存储路径或上游响应：
 * 调用方只需要知道「这件事做完了没有、用的哪个模型、产生多少向量、现在是哪个版本」。
 * 向量本身是检索阶段的内部数据，对客户端没有任何意义。</p>
 *
 * <p>{@code version} 与响应头 {@code ETag} 始终一致。</p>
 *
 * @param documentId          文档标识
 * @param title               标题
 * @param status              索引后的状态（成功时恒为 {@code INDEXED}）
 * @param version             最新版本号（等于响应头 ETag）
 * @param chunkCount          已索引的切片数量
 * @param embeddingProvider   向量服务提供方
 * @param embeddingModel      向量模型标识
 * @param embeddingDimensions 向量维度
 * @param indexedAt           索引完成时间（ISO-8601）
 */
public record IndexedDocumentResponse(UUID documentId,
                                      String title,
                                      KnowledgeDocumentStatus status,
                                      long version,
                                      long chunkCount,
                                      String embeddingProvider,
                                      String embeddingModel,
                                      int embeddingDimensions,
                                      Instant indexedAt) {

    /**
     * @param view 索引结果视图
     * @return 响应体
     */
    public static IndexedDocumentResponse from(IndexedDocumentView view) {
        return new IndexedDocumentResponse(
                view.documentId(),
                view.title(),
                view.status(),
                view.version(),
                view.chunkCount(),
                view.embeddingProvider(),
                view.embeddingModel(),
                view.embeddingDimensions(),
                view.indexedAt());
    }
}
