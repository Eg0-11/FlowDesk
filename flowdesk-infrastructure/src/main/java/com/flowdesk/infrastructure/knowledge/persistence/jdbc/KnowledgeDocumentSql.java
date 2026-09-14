package com.flowdesk.infrastructure.knowledge.persistence.jdbc;

import com.flowdesk.domain.knowledge.KnowledgeDocument;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.sql.Types;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 知识文档元数据 SQL 常量。
 *
 * <p>全部为静态常量，参数一律用占位符绑定，不存在字符串拼接 SQL 的路径。</p>
 *
 * <p>只有<b>状态与生命周期字段</b>可变：标题、文件名、格式、内容键在上传时就已定型，
 * 因此 {@link #CAS_UPDATE_STATUS} 只更新这些列 —— 更新语句触及的列越少，
 * 「解析/索引过程中内容被改写」这类问题就越不可能发生。</p>
 */
final class KnowledgeDocumentSql {

    static final String COLUMNS = "id, title, original_filename, format, media_type, size_bytes, sha256, "
            + "content_key, status, version, created_at, updated_at, parsed_at, parse_failed_at, "
            + "parse_failure_code, index_started_at, indexed_at, index_failed_at, index_failure_code, "
            + "embedding_provider, embedding_model, embedding_dimensions";

    static final String SELECT_BY_ID = "SELECT " + COLUMNS + " FROM knowledge_documents WHERE id = ?";

    /** 插入时版本固定写 0，解析与索引字段全部为空。 */
    static final String INSERT = "INSERT INTO knowledge_documents (" + COLUMNS + ") "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, "
            + "NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)";

    /** 在事务内锁定目标行并读取当前版本与状态。 */
    static final String SELECT_VERSION_FOR_UPDATE =
            "SELECT version, status FROM knowledge_documents WHERE id = ? FOR UPDATE";

    /**
     * compare-and-set：只有 id 与 version 同时匹配才写入，并把版本加 1。
     *
     * <p>一次写入整条生命周期（解析 + 索引字段）：状态与字段必须同时更新，
     * 否则会短暂出现「状态是 INDEXED 但向量元数据还没写」这种自相矛盾的行 ——
     * 而数据库 CHECK 会直接拒绝这种行，因此这里必须整体写。</p>
     */
    static final String CAS_UPDATE_STATUS = "UPDATE knowledge_documents "
            + "SET status = ?, updated_at = ?, parsed_at = ?, parse_failed_at = ?, parse_failure_code = ?, "
            + "index_started_at = ?, indexed_at = ?, index_failed_at = ?, index_failure_code = ?, "
            + "embedding_provider = ?, embedding_model = ?, embedding_dimensions = ?, "
            + "version = version + 1 WHERE id = ? AND version = ?";

    private KnowledgeDocumentSql() {
    }

    /**
     * 绑定生命周期字段（占位符 1~12）：解析与索引两条生命周期的所有可变列。
     *
     * <p>{@link #CAS_UPDATE_STATUS} 被「完成解析」与「完成索引」两个适配器共用，
     * 绑定逻辑因此只写一份：任何一个适配器漏绑一列，都会在数据库 CHECK 上直接失败，
     * 而不是静默写入半条状态。</p>
     *
     * @param spec     JDBC 语句规格
     * @param document 文档聚合
     * @return 绑定完 1~12 号参数的语句规格
     */
    static JdbcClient.StatementSpec bindLifecycleFields(JdbcClient.StatementSpec spec,
            KnowledgeDocument document) {

        return spec
                .param(1, document.status().name())
                .param(2, toOffsetDateTime(document.updatedAt()))
                .param(3, toOffsetDateTime(document.parsedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param(4, toOffsetDateTime(document.parseFailedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param(5, document.parseFailureCode() == null ? null : document.parseFailureCode().name(),
                        Types.VARCHAR)
                .param(6, toOffsetDateTime(document.indexStartedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param(7, toOffsetDateTime(document.indexedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param(8, toOffsetDateTime(document.indexFailedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param(9, document.indexFailureCode() == null ? null : document.indexFailureCode().name(),
                        Types.VARCHAR)
                .param(10, document.embeddingProvider(), Types.VARCHAR)
                .param(11, document.embeddingModel(), Types.VARCHAR)
                .param(12, document.embeddingDimensions(), Types.INTEGER);
    }

    static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
