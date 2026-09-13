package com.flowdesk.infrastructure.knowledge.persistence.jdbc;

/**
 * 知识文档元数据 SQL 常量。
 *
 * <p>全部为静态常量，参数一律用占位符绑定，不存在字符串拼接 SQL 的路径。
 * 列顺序与 {@link #INSERT} 的取值顺序、{@link KnowledgeDocumentRowMapper} 的读取顺序保持一致。</p>
 */
final class KnowledgeDocumentSql {

    static final String COLUMNS = "id, title, original_filename, format, media_type, size_bytes, sha256, "
            + "content_key, status, version, created_at, updated_at";

    static final String SELECT_BY_ID = "SELECT " + COLUMNS + " FROM knowledge_documents WHERE id = ?";

    /** 插入时版本固定写 0，不接受调用方传入。 */
    static final String INSERT = "INSERT INTO knowledge_documents (" + COLUMNS + ") "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)";

    /** 已有内容键时的存在性检查（当前未使用，保留给后续清理任务）。 */
    static final String COUNT_BY_CONTENT_KEY = "SELECT COUNT(*) FROM knowledge_documents WHERE content_key = ?";

    private KnowledgeDocumentSql() {
    }
}
