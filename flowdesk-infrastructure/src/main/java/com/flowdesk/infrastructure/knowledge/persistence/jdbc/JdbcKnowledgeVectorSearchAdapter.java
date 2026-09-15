package com.flowdesk.infrastructure.knowledge.persistence.jdbc;

import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.application.knowledge.port.out.KnowledgeVectorMatch;
import com.flowdesk.application.knowledge.port.out.KnowledgeVectorSearchPort;
import com.flowdesk.domain.knowledge.EmbeddingDescriptor;
import com.flowdesk.domain.knowledge.KnowledgeDocumentId;
import com.flowdesk.domain.knowledge.KnowledgeQueryEmbedding;
import com.flowdesk.domain.knowledge.Sha256Digest;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * pgvector 相似度检索适配器（RAG 4/6）。
 *
 * <h2>为什么不用 Spring AI 的 PgVectorStore</h2>
 * <p>与本项目写入路径同样的理由（见 ADR 0007/0008）：通用 store 的表结构里没有
 * 「文档状态、切片摘要、描述符」这些过滤维度，而本项目的检索必须同时满足
 * 「只检索已索引文档」「描述符精确匹配」「向量与切片摘要一致」三条约束 ——
 * 这些只能用业务表之间的连接表达，不能靠应用层事后过滤。</p>
 *
 * <h2>只读与事务边界</h2>
 * <p>本适配器只执行一条 {@code SELECT}（{@link KnowledgeRetrievalSql#SELECT_MATCHES}），
 * 没有 {@code FOR UPDATE}、没有任何写语句，也<b>不</b>开启事务：单条语句本身就是一个原子快照，
 * 而模型调用发生在它之前、且不持有数据库连接。见 ADR 0008。</p>
 *
 * <h2>失败映射</h2>
 * <p>数据库异常统一映射为 {@code KNOWLEDGE_RETRIEVAL_FAILURE}（HTTP 500），
 * 原始异常只作为 cause 保留在服务端：消息与日志里不会出现 SQL、连接串、用户名、密码、
 * query 或向量 —— 这些都是构造响应时绝不使用的信息。</p>
 */
public final class JdbcKnowledgeVectorSearchAdapter implements KnowledgeVectorSearchPort {

    private final JdbcClient jdbcClient;

    /**
     * 行映射：保持数据库返回的行顺序（排序由 SQL 负责，这里<b>不</b>重排）。
     *
     * <p>包内可见是为了让单元测试能直接用替身 {@code ResultSet} 驱动它：
     * 真实的 {@code <=>} 只有 PostgreSQL 能执行（见 Testcontainers 集成测试），
     * 但「列名 → 字段、类型、Unicode 文本、顺序保持」这些映射语义在 H2 上也能验证。</p>
     */
    static final RowMapper<KnowledgeVectorMatch> MATCH_ROW_MAPPER = (resultSet, rowNum) ->
            new KnowledgeVectorMatch(
                    KnowledgeDocumentId.of(resultSet.getObject("document_id", java.util.UUID.class)),
                    resultSet.getLong("document_version"),
                    resultSet.getString("document_title"),
                    resultSet.getInt("chunk_index"),
                    Sha256Digest.of(resultSet.getString("chunk_sha256").strip()),
                    resultSet.getString("content"),
                    resultSet.getDouble("score"));

    /**
     * @param jdbcClient JDBC 客户端（只用于只读查询）
     */
    public JdbcKnowledgeVectorSearchAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient 不能为 null");
    }

    @Override
    public List<KnowledgeVectorMatch> search(KnowledgeQueryEmbedding queryEmbedding, double minScore, int topK) {
        Objects.requireNonNull(queryEmbedding, "queryEmbedding 不能为 null");
        if (!Double.isFinite(minScore) || minScore < 0.0 || minScore > 1.0) {
            throw new IllegalArgumentException("minScore 必须是 0.0..1.0 之间的有限数值");
        }
        if (topK < 1) {
            throw new IllegalArgumentException("topK 必须大于 0");
        }

        EmbeddingDescriptor descriptor = queryEmbedding.descriptor();
        // 查询向量只以 ?::vector 绑定，从不拼进 SQL；序列化与写入路径共用同一个工具
        String literal = PgVectorLiteral.serialize(queryEmbedding.vector());

        try {
            return this.jdbcClient.sql(KnowledgeRetrievalSql.SELECT_MATCHES)
                    .param(1, literal)
                    .param(2, descriptor.provider())
                    .param(3, descriptor.model())
                    .param(4, descriptor.dimensions())
                    .param(5, descriptor.provider())
                    .param(6, descriptor.model())
                    .param(7, descriptor.dimensions())
                    .param(8, literal)
                    .param(9, minScore)
                    .param(10, literal)
                    .param(11, topK)
                    .query(MATCH_ROW_MAPPER)
                    .list();
        }
        catch (DataAccessException ex) {
            // 细节（SQL、连接串、驱动信息）只留在 cause，绝不进入消息
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.KNOWLEDGE_RETRIEVAL_FAILURE,
                    "向量检索失败", ex);
        }
    }
}
