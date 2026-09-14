package com.flowdesk.application.knowledge.view;

import com.flowdesk.domain.knowledge.KnowledgeDocumentStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * 索引结果视图：索引接口的返回形态。
 *
 * <p>只包含客户端需要的元数据：<b>没有</b>切片正文、向量数组、内容键或任何存储路径。
 * 向量本身对调用方没有任何意义（那是检索阶段的内部数据），因此绝不对外暴露。</p>
 *
 * @param documentId         文档标识
 * @param title              标题
 * @param status             索引后的状态（成功时恒为 {@code INDEXED}）
 * @param version            最新版本号（与响应头 ETag 一致）
 * @param chunkCount         已索引的切片数量
 * @param embeddingProvider  向量服务提供方
 * @param embeddingModel     向量模型标识
 * @param embeddingDimensions 向量维度
 * @param indexedAt          索引完成时间
 */
public record IndexedDocumentView(UUID documentId,
                                  String title,
                                  KnowledgeDocumentStatus status,
                                  long version,
                                  long chunkCount,
                                  String embeddingProvider,
                                  String embeddingModel,
                                  int embeddingDimensions,
                                  Instant indexedAt) {
}
