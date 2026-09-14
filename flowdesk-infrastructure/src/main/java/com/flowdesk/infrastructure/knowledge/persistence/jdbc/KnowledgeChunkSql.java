package com.flowdesk.infrastructure.knowledge.persistence.jdbc;

/**
 * 知识文档切片 SQL 常量。
 */
final class KnowledgeChunkSql {

    static final String COLUMNS = "document_id, chunk_index, content, code_point_count, sha256, created_at";

    static final String INSERT = "INSERT INTO knowledge_document_chunks (" + COLUMNS + ") "
            + "VALUES (?, ?, ?, ?, ?, ?)";

    static final String DELETE_BY_DOCUMENT = "DELETE FROM knowledge_document_chunks WHERE document_id = ?";

    static final String COUNT_BY_DOCUMENT = "SELECT COUNT(*) FROM knowledge_document_chunks WHERE document_id = ?";

    static final String SELECT_PAGE = "SELECT " + COLUMNS + " FROM knowledge_document_chunks "
            + "WHERE document_id = ? ORDER BY chunk_index ASC LIMIT ? OFFSET ?";

    private KnowledgeChunkSql() {
    }
}
