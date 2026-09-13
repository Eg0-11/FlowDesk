package com.flowdesk.bootstrap.knowledge;

import com.flowdesk.application.knowledge.view.KnowledgeDocumentView;
import com.flowdesk.domain.knowledge.DocumentFormat;
import com.flowdesk.domain.knowledge.KnowledgeDocumentStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * 知识文档响应体。
 *
 * <p><b>刻意不包含 {@code contentKey}</b>：它是内容存储的内部坐标，一旦出现在对外契约里，
 * 调用方就可能开始依赖它（甚至拿它拼路径），将来把本地文件系统换成对象存储就成了破坏性变更。
 * 同理，这里不会出现任何磁盘路径、临时文件路径或存储根目录。</p>
 *
 * @param id               文档标识（UUID 字符串）
 * @param title            标题
 * @param originalFilename 原始文件名（仅元数据，已收敛为纯文件名）
 * @param format           文档格式
 * @param mediaType        媒体类型（规范值，不采用客户端声明）
 * @param sizeBytes        实际内容字节数
 * @param sha256           内容摘要（64 位小写十六进制）
 * @param status           状态
 * @param version          版本号；新上传为 0
 * @param createdAt        创建时间（ISO-8601）
 * @param updatedAt        更新时间
 */
public record KnowledgeDocumentResponse(UUID id,
                                        String title,
                                        String originalFilename,
                                        DocumentFormat format,
                                        String mediaType,
                                        long sizeBytes,
                                        String sha256,
                                        KnowledgeDocumentStatus status,
                                        long version,
                                        Instant createdAt,
                                        Instant updatedAt) {

    /**
     * @param view 应用层只读视图
     * @return 响应体
     */
    public static KnowledgeDocumentResponse from(KnowledgeDocumentView view) {
        return new KnowledgeDocumentResponse(
                view.id(),
                view.title(),
                view.originalFilename(),
                view.format(),
                view.mediaType(),
                view.sizeBytes(),
                view.sha256().value(),
                view.status(),
                view.version(),
                view.createdAt(),
                view.updatedAt());
    }
}
