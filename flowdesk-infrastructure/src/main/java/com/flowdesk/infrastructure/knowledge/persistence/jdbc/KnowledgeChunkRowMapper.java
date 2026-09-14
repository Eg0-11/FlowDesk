package com.flowdesk.infrastructure.knowledge.persistence.jdbc;

import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.domain.knowledge.KnowledgeDocumentChunk;
import com.flowdesk.domain.knowledge.KnowledgeDocumentId;
import com.flowdesk.domain.knowledge.Sha256Digest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * 数据库行到切片的映射。
 *
 * <p>与文档一样走领域中构造器：库里的非法切片（空内容、计数与内容不符、摘要格式错）
 * 会在映射时暴露为 {@code INVALID_PERSISTED_DOCUMENT}，而不是被当作正常数据使用。</p>
 */
final class KnowledgeChunkRowMapper {

    private KnowledgeChunkRowMapper() {
    }

    /**
     * 标准 {@code RowMapper} 签名（保留 {@code rowNum}，避免被解析成 {@code ResultSetExtractor}）。
     *
     * @param resultSet 已定位到一行的结果集
     * @param rowNum    行号，未使用
     * @return 切片
     * @throws SQLException 读取失败
     */
    static KnowledgeDocumentChunk mapRow(ResultSet resultSet, int rowNum) throws SQLException {
        KnowledgeDocumentId documentId = KnowledgeDocumentId.of(resultSet.getObject("document_id",
                java.util.UUID.class));
        int chunkIndex = resultSet.getInt("chunk_index");
        String content = resultSet.getString("content");
        int codePointCount = resultSet.getInt("code_point_count");
        Sha256Digest sha256 = Sha256Digest.of(resultSet.getString("sha256"));
        Instant createdAt = toInstant(resultSet.getObject("created_at", OffsetDateTime.class));

        try {
            return new KnowledgeDocumentChunk(documentId, chunkIndex, content, codePointCount, sha256,
                    createdAt);
        }
        catch (RuntimeException ex) {
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.INVALID_PERSISTED_DOCUMENT,
                    "持久化的切片快照不自洽", ex);
        }
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
