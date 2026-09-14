package com.flowdesk.infrastructure.knowledge.persistence.jdbc;

import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.application.knowledge.port.out.VersionedKnowledgeDocument;
import com.flowdesk.domain.knowledge.DocumentFormat;
import com.flowdesk.domain.knowledge.EmbeddingDescriptor;
import com.flowdesk.domain.knowledge.KnowledgeDocument;
import com.flowdesk.domain.knowledge.KnowledgeDocumentId;
import com.flowdesk.domain.knowledge.KnowledgeDocumentStatus;
import com.flowdesk.domain.knowledge.KnowledgeDomainException;
import com.flowdesk.domain.knowledge.KnowledgeIndexFailureCode;
import com.flowdesk.domain.knowledge.KnowledgeParseFailureCode;
import com.flowdesk.domain.knowledge.Sha256Digest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 数据库行到知识文档聚合的映射。
 *
 * <p><b>不信任数据库内容</b>：每次映射都通过 {@link KnowledgeDocument#restore} 走一遍完整的
 * 领域校验并生成全新聚合，因此：</p>
 * <ul>
 *   <li>库里的非法快照（被外部脚本改坏、被旧版本写歪）会在读取时立刻暴露，
 *       映射为 {@link KnowledgeApplicationErrorCode#INVALID_PERSISTED_DOCUMENT}（服务端内部错误），
 *       而不是被当作正常数据继续使用；</li>
 *   <li>读取结果永远是独立对象，不与数据库驱动或其它调用方共享。</li>
 * </ul>
 */
final class KnowledgeDocumentRowMapper {

    private KnowledgeDocumentRowMapper() {
    }

    /**
     * 标准 {@link org.springframework.jdbc.core.RowMapper} 签名。
     *
     * <p>刻意保留 {@code rowNum} 参数：只带 {@link ResultSet} 的方法引用会被编译器解析成
     * {@code ResultSetExtractor}，从而走上完全不同的查询路径。</p>
     *
     * @param resultSet 已定位到一行的结果集
     * @param rowNum    行号，未使用
     * @return 独立恢复的文档及其版本
     * @throws SQLException            读取失败
     * @throws KnowledgeApplicationException 快照不自洽
     */
    static VersionedKnowledgeDocument mapRow(ResultSet resultSet, int rowNum) throws SQLException {
        UUID id = resultSet.getObject("id", UUID.class);
        String title = resultSet.getString("title");
        String originalFilename = resultSet.getString("original_filename");
        DocumentFormat format = parseFormat(resultSet.getString("format"));
        String mediaType = resultSet.getString("media_type");
        long sizeBytes = resultSet.getLong("size_bytes");
        Sha256Digest sha256 = parseDigest(resultSet.getString("sha256"));
        String contentKey = resultSet.getString("content_key");
        KnowledgeDocumentStatus status = parseStatus(resultSet.getString("status"));
        Instant createdAt = toInstant(resultSet.getObject("created_at", OffsetDateTime.class));
        Instant updatedAt = toInstant(resultSet.getObject("updated_at", OffsetDateTime.class));
        Instant parsedAt = toInstant(resultSet.getObject("parsed_at", OffsetDateTime.class));
        Instant parseFailedAt = toInstant(resultSet.getObject("parse_failed_at", OffsetDateTime.class));
        KnowledgeParseFailureCode parseFailureCode = parseFailureCode(resultSet.getString("parse_failure_code"));
        Instant indexStartedAt = toInstant(resultSet.getObject("index_started_at", OffsetDateTime.class));
        Instant indexedAt = toInstant(resultSet.getObject("indexed_at", OffsetDateTime.class));
        Instant indexFailedAt = toInstant(resultSet.getObject("index_failed_at", OffsetDateTime.class));
        KnowledgeIndexFailureCode indexFailureCode = parseIndexFailureCode(
                resultSet.getString("index_failure_code"));
        EmbeddingDescriptor embedding = embeddingDescriptor(resultSet.getString("embedding_provider"),
                resultSet.getString("embedding_model"),
                resultSet.getObject("embedding_dimensions", Integer.class));
        long version = resultSet.getLong("version");

        try {
            KnowledgeDocument document = KnowledgeDocument.restore(KnowledgeDocumentId.of(id), title,
                    originalFilename, format, mediaType, sizeBytes, sha256, contentKey, status, createdAt,
                    updatedAt, parsedAt, parseFailedAt, parseFailureCode, indexStartedAt, indexedAt,
                    indexFailedAt, indexFailureCode, embedding);
            return new VersionedKnowledgeDocument(document, version);
        }
        catch (KnowledgeDomainException ex) {
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.INVALID_PERSISTED_DOCUMENT,
                    "持久化的知识文档快照不自洽：" + ex.errorCode(), ex);
        }
        catch (RuntimeException ex) {
            // 数字/时间列被改成 NULL 之类的情况也要归为内部错误，不能让 NPE 变成 500 之外的意外
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.INVALID_PERSISTED_DOCUMENT,
                    "持久化的知识文档快照无法恢复", ex);
        }
    }

    private static DocumentFormat parseFormat(String value) {
        try {
            return DocumentFormat.valueOf(value);
        }
        catch (IllegalArgumentException | NullPointerException ex) {
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.INVALID_PERSISTED_DOCUMENT,
                    "持久化的知识文档格式不受支持", ex);
        }
    }

    private static KnowledgeDocumentStatus parseStatus(String value) {
        try {
            return KnowledgeDocumentStatus.valueOf(value);
        }
        catch (IllegalArgumentException | NullPointerException ex) {
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.INVALID_PERSISTED_DOCUMENT,
                    "持久化的知识文档状态不受支持", ex);
        }
    }

    /**
     * 解析失败码：{@code null} 是合法值（非失败状态），非空时必须是已知枚举常量。
     *
     * <p>库里出现未知取值说明数据被外部改坏或来自更新的版本，属于内部错误，
     * 必须显式暴露而不是静默当成 {@code null}。</p>
     */
    private static KnowledgeParseFailureCode parseFailureCode(String value) {
        if (value == null) {
            return null;
        }
        try {
            return KnowledgeParseFailureCode.valueOf(value);
        }
        catch (IllegalArgumentException ex) {
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.INVALID_PERSISTED_DOCUMENT,
                    "持久化的解析失败码不受支持", ex);
        }
    }

    private static Sha256Digest parseDigest(String value) {
        try {
            return Sha256Digest.of(value);
        }
        catch (KnowledgeDomainException ex) {
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.INVALID_PERSISTED_DOCUMENT,
                    "持久化的知识文档摘要不合法", ex);
        }
    }

    /**
     * 索引失败码：{@code null} 是合法值（非失败状态），非空时必须是已知枚举常量。
     *
     * @param value 数据库列值
     * @return 失败码或 {@code null}
     */
    private static KnowledgeIndexFailureCode parseIndexFailureCode(String value) {
        if (value == null) {
            return null;
        }
        try {
            return KnowledgeIndexFailureCode.valueOf(value);
        }
        catch (IllegalArgumentException ex) {
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.INVALID_PERSISTED_DOCUMENT,
                    "持久化的索引失败码不受支持", ex);
        }
    }

    /**
     * 向量描述符：三列要么全空、要么全非空。
     *
     * <p>「部分为空」说明行被外部改坏或来自更旧的版本，属于内部错误；
     * 维度不是本项目固定值时，领域值对象会拒绝，这里统一映射为
     * {@code INVALID_PERSISTED_DOCUMENT}。</p>
     *
     * @param provider   提供方列
     * @param model      模型列
     * @param dimensions 维度列
     * @return 描述符或 {@code null}
     */
    private static EmbeddingDescriptor embeddingDescriptor(String provider, String model, Integer dimensions) {
        if (provider == null && model == null && dimensions == null) {
            return null;
        }
        if (provider == null || model == null || dimensions == null) {
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.INVALID_PERSISTED_DOCUMENT,
                    "持久化的向量描述符不完整");
        }
        try {
            return new EmbeddingDescriptor(provider, model, dimensions);
        }
        catch (KnowledgeDomainException ex) {
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.INVALID_PERSISTED_DOCUMENT,
                    "持久化的向量描述符不合法", ex);
        }
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
