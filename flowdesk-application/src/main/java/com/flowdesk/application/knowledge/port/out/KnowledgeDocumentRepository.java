package com.flowdesk.application.knowledge.port.out;

import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.domain.knowledge.KnowledgeDocument;
import com.flowdesk.domain.knowledge.KnowledgeDocumentId;
import java.util.Optional;

/**
 * 知识文档元数据存储输出端口。
 *
 * <h2>契约</h2>
 * <ul>
 *   <li>{@link #insert(KnowledgeDocument)} 必须<b>原子地</b>拒绝重复标识：并发的两次插入
 *       最多只有一个成功，另一个必须以 {@link KnowledgeApplicationErrorCode#KNOWLEDGE_DOCUMENT_ALREADY_EXISTS}
 *       失败；</li>
 *   <li>{@link #findById(KnowledgeDocumentId)} 必须返回<b>独立恢复</b>的聚合：
 *       适配器不得交出内部可变存储引用，也不得跳过领域校验 ——
 *       库里的非法快照必须以 {@link KnowledgeApplicationErrorCode#INVALID_PERSISTED_DOCUMENT}
 *       暴露出来，而不是被当作正常数据继续使用；</li>
 *   <li>不存在时返回 {@link Optional#empty()}，<b>绝不返回 {@code null}</b>；</li>
 *   <li>失败一律以 {@link KnowledgeApplicationException} 抛出，并使用应用层错误码，
 *       不泄漏 SQL、表结构或驱动信息。</li>
 * </ul>
 */
public interface KnowledgeDocumentRepository {

    /**
     * 插入新文档元数据。
     *
     * @param document 待插入的文档
     * @return 已存储的文档及其版本，版本恒为 {@code 0}
     * @throws KnowledgeApplicationException 标识已存在时抛出
     *                                       {@link KnowledgeApplicationErrorCode#KNOWLEDGE_DOCUMENT_ALREADY_EXISTS}
     */
    VersionedKnowledgeDocument insert(KnowledgeDocument document);

    /**
     * 按标识读取文档元数据。
     *
     * @param documentId 文档标识
     * @return 文档及其版本；不存在时返回 {@link Optional#empty()}
     * @throws KnowledgeApplicationException 快照不自洽时抛出
     *                                       {@link KnowledgeApplicationErrorCode#INVALID_PERSISTED_DOCUMENT}
     */
    Optional<VersionedKnowledgeDocument> findById(KnowledgeDocumentId documentId);
}
