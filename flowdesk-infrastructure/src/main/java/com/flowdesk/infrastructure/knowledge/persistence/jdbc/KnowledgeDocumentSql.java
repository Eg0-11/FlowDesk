package com.flowdesk.infrastructure.knowledge.persistence.jdbc;

/**
 * 知识文档元数据 SQL 常量。
 *
 * <p>全部为静态常量，参数一律用占位符绑定，不存在字符串拼接 SQL 的路径。</p>
 *
 * <p>本阶段只有<b>状态与解析字段</b>可变：标题、文件名、格式、内容键等在 FD-0008 就已经定型，
 * 因此 {@link #CAS_UPDATE_STATUS} 只更新这几列 —— 更新语句触及的列越少，
 * 「解析过程中内容被改写」这类问题就越不可能发生。</p>
 */
final class KnowledgeDocumentSql {

    static final String COLUMNS = "id, title, original_filename, format, media_type, size_bytes, sha256, "
            + "content_key, status, version, created_at, updated_at, parsed_at, parse_failed_at, "
            + "parse_failure_code";

    static final String SELECT_BY_ID = "SELECT " + COLUMNS + " FROM knowledge_documents WHERE id = ?";

    /** 插入时版本固定写 0，解析字段为空。 */
    static final String INSERT = "INSERT INTO knowledge_documents (" + COLUMNS + ") "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, NULL, NULL, NULL)";

    /** 在事务内锁定目标行并读取当前版本与状态。 */
    static final String SELECT_VERSION_FOR_UPDATE =
            "SELECT version, status FROM knowledge_documents WHERE id = ? FOR UPDATE";

    /**
     * compare-and-set：只有 id 与 version 同时匹配才写入，并把版本加 1。
     */
    static final String CAS_UPDATE_STATUS = "UPDATE knowledge_documents "
            + "SET status = ?, updated_at = ?, parsed_at = ?, parse_failed_at = ?, parse_failure_code = ?, "
            + "version = version + 1 WHERE id = ? AND version = ?";

    private KnowledgeDocumentSql() {
    }
}
