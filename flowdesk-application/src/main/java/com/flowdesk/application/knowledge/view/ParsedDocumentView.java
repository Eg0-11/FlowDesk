package com.flowdesk.application.knowledge.view;

import com.flowdesk.domain.knowledge.KnowledgeDocumentStatus;
import com.flowdesk.domain.knowledge.KnowledgeParseFailureCode;
import java.time.Instant;
import java.util.UUID;

/**
 * 解析结果视图：解析接口的返回形态。
 *
 * <p>只包含客户端需要的元数据：<b>没有</b>原文、完整切片、内容键或任何存储路径。</p>
 *
 * @param documentId 文档标识
 * @param title      标题
 * @param status     解析后的状态（成功时为 {@code PARSED}）
 * @param version    最新版本号（与响应头 ETag 一致）
 * @param chunkCount 切片数量
 * @param parsedAt   解析完成时间；失败时为 {@code null}
 * @param failureCode 失败码；成功时为 {@code null}
 */
public record ParsedDocumentView(UUID documentId,
                                 String title,
                                 KnowledgeDocumentStatus status,
                                 long version,
                                 long chunkCount,
                                 Instant parsedAt,
                                 KnowledgeParseFailureCode failureCode) {
}
