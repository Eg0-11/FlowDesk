package com.flowdesk.infrastructure.knowledge.persistence.jdbc;

import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentRepository;
import com.flowdesk.application.knowledge.port.out.VersionedKnowledgeDocument;
import com.flowdesk.domain.knowledge.KnowledgeDocument;
import com.flowdesk.domain.knowledge.KnowledgeDocumentId;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 基于 Spring JDBC {@link JdbcClient} 的知识文档元数据适配器。
 *
 * <p>与工单持久化同样的风格：显式 SQL、参数绑定、不用 JPA/Hibernate。</p>
 *
 * <h2>插入</h2>
 * <p>版本固定写 0，单条语句即可完成，因此<b>不需要显式事务</b> —— 这一点很重要：
 * 上传流程只在文件已经落盘之后做这一次短写入，慢速上传期间不持有任何数据库事务。</p>
 *
 * <h2>读取</h2>
 * <p>每次都用 {@link KnowledgeDocument#restore} 构造新聚合（见
 * {@link KnowledgeDocumentRowMapper}），不缓存也不返回共享对象。</p>
 *
 * <p>本类不记录日志，因此不会把标题、文件名或本地路径写进日志。</p>
 */
public final class JdbcKnowledgeDocumentRepository implements KnowledgeDocumentRepository {

    private final JdbcClient jdbcClient;

    /**
     * @param jdbcClient Spring JDBC 客户端
     */
    public JdbcKnowledgeDocumentRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient 不能为 null");
    }

    @Override
    public VersionedKnowledgeDocument insert(KnowledgeDocument document) {
        Objects.requireNonNull(document, "document 不能为 null");

        try {
            this.jdbcClient.sql(KnowledgeDocumentSql.INSERT)
                    .param(1, document.id().value())
                    .param(2, document.title().value())
                    .param(3, document.originalFilename().value())
                    .param(4, document.format().name())
                    .param(5, document.mediaType())
                    .param(6, document.sizeBytes())
                    .param(7, document.sha256().value())
                    .param(8, document.contentKey())
                    .param(9, document.status().name())
                    .param(10, toOffsetDateTime(document.createdAt()))
                    .param(11, toOffsetDateTime(document.updatedAt()))
                    .update();
        }
        catch (DuplicateKeyException ex) {
            // 主键或内容键重复：两者都意味着服务端生成逻辑出了问题，绝不吞掉当成成功
            throw new KnowledgeApplicationException(
                    KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_ALREADY_EXISTS,
                    "文档标识或内容键已存在", ex);
        }
        return new VersionedKnowledgeDocument(document, 0L);
    }

    @Override
    public Optional<VersionedKnowledgeDocument> findById(KnowledgeDocumentId documentId) {
        Objects.requireNonNull(documentId, "documentId 不能为 null");

        return this.jdbcClient.sql(KnowledgeDocumentSql.SELECT_BY_ID)
                .param(1, documentId.value())
                .query(KnowledgeDocumentRowMapper::mapRow)
                .optional();
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
