package com.flowdesk.application.knowledge.view;

import com.flowdesk.domain.knowledge.DocumentFormat;
import com.flowdesk.domain.knowledge.KnowledgeDocument;
import com.flowdesk.domain.knowledge.KnowledgeDocumentStatus;
import com.flowdesk.domain.knowledge.Sha256Digest;
import java.time.Instant;
import java.util.UUID;

/**
 * 知识文档只读视图：应用层对外的唯一输出形态。
 *
 * <p>刻意<b>不</b>包含 {@code contentKey}：内容键是存储适配器的内部标识，
 * 一旦出现在对外契据里，调用方就可能开始依赖它（甚至拼路径），
 * 将来把本地文件系统换成对象存储就变成破坏性变更。
 * 这条约束在视图层用「没有这个字段」实现，而不是靠响应组装时的纪律。</p>
 *
 * @param id               文档标识
 * @param title            标题
 * @param originalFilename 原始文件名（仅元数据）
 * @param format           文档格式
 * @param mediaType        媒体类型
 * @param sizeBytes        内容字节数
 * @param sha256           内容摘要
 * @param status           状态
 * @param version          版本号；新上传为 0
 * @param createdAt        创建时间
 * @param updatedAt        更新时间
 */
public record KnowledgeDocumentView(UUID id,
                                    String title,
                                    String originalFilename,
                                    DocumentFormat format,
                                    String mediaType,
                                    long sizeBytes,
                                    Sha256Digest sha256,
                                    KnowledgeDocumentStatus status,
                                    long version,
                                    Instant createdAt,
                                    Instant updatedAt) {

    /**
     * @param document 文档聚合
     * @param version  该文档的当前版本
     * @return 只读视图
     */
    public static KnowledgeDocumentView from(KnowledgeDocument document, long version) {
        return new KnowledgeDocumentView(
                document.id().value(),
                document.title().value(),
                document.originalFilename().value(),
                document.format(),
                document.mediaType(),
                document.sizeBytes(),
                document.sha256(),
                document.status(),
                version,
                document.createdAt(),
                document.updatedAt());
    }
}
