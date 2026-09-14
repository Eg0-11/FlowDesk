package com.flowdesk.bootstrap.knowledge;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.flowdesk.application.knowledge.view.ParsedDocumentView;
import com.flowdesk.domain.knowledge.KnowledgeDocumentStatus;
import com.flowdesk.domain.knowledge.KnowledgeParseFailureCode;
import java.time.Instant;
import java.util.UUID;

/**
 * 解析结果响应体。
 *
 * <p><b>刻意不包含</b>提取出的原文、任何切片内容、内容键、存储路径或解析器名称：
 * 解析接口只回报「这件事做完了没有、产生了多少切片、现在是哪个版本」。
 * 切片内容的读取属于下一阶段（向量化）的内部输入，不通过本接口暴露。</p>
 *
 * <p>{@code version} 与响应头 {@code ETag} 始终一致：调用方拿它作为下一次
 * 状态变更请求的 {@code If-Match}。</p>
 *
 * @param documentId  文档标识
 * @param title       标题
 * @param status      解析后的状态（成功时恒为 {@code PARSED}）
 * @param version     最新版本号（等于响应头 ETag）
 * @param chunkCount  切片数量
 * @param parsedAt    解析完成时间（ISO-8601）
 * @param failureCode 失败码；成功时字段不出现（失败走错误契约，不会返回 200）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ParsedDocumentResponse(UUID documentId,
                                     String title,
                                     KnowledgeDocumentStatus status,
                                     long version,
                                     long chunkCount,
                                     Instant parsedAt,
                                     KnowledgeParseFailureCode failureCode) {

    /**
     * @param view 解析结果视图
     * @return 响应体
     */
    public static ParsedDocumentResponse from(ParsedDocumentView view) {
        return new ParsedDocumentResponse(
                view.documentId(),
                view.title(),
                view.status(),
                view.version(),
                view.chunkCount(),
                view.parsedAt(),
                view.failureCode());
    }
}
