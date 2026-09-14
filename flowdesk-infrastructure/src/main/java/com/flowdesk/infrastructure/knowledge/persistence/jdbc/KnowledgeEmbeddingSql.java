package com.flowdesk.infrastructure.knowledge.persistence.jdbc;

/**
 * 切片向量 SQL 常量（PostgreSQL + pgvector）。
 *
 * <p>参数一律用占位符绑定；唯一的「拼接」是把向量序列化成 pgvector 字面量
 * （{@code [0.1,0.2,...]}），而那是<b>数值</b>序列化，不涉及任何用户输入 ——
 * 数值在序列化前还会再校验一次有限性与维度。</p>
 */
final class KnowledgeEmbeddingSql {

    /**
     * 写入一条向量。
     *
     * <p>{@code ?::vector} 显式转换：pgvector 接受文本形式的字面量，
     * 但只有显式转换才不会依赖驱动的类型推断。</p>
     */
    static final String INSERT = "INSERT INTO knowledge_document_chunk_embeddings "
            + "(document_id, chunk_index, chunk_sha256, embedding, provider, model, embedding_dimensions, "
            + "created_at) VALUES (?, ?, ?, ?::vector, ?, ?, ?, ?)";

    /** 删除某文档的全部向量（重新索引时不留残余）。 */
    static final String DELETE_BY_DOCUMENT =
            "DELETE FROM knowledge_document_chunk_embeddings WHERE document_id = ?";

    /** 读取某文档全部切片的 (序号, 摘要)，用于在写入前二次证明切片没有被换掉。 */
    static final String SELECT_CHUNK_PROOFS =
            "SELECT chunk_index, sha256 FROM knowledge_document_chunks WHERE document_id = ? "
                    + "ORDER BY chunk_index ASC";

    /** 统计某文档已写入的向量数（供测试与诊断使用）。 */
    static final String COUNT_BY_DOCUMENT =
            "SELECT COUNT(*) FROM knowledge_document_chunk_embeddings WHERE document_id = ?";

    private KnowledgeEmbeddingSql() {
    }
}
