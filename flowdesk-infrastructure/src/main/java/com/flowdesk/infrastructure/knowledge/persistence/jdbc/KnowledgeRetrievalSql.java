package com.flowdesk.infrastructure.knowledge.persistence.jdbc;

/**
 * 向量相似度检索 SQL（RAG 4/6）。
 *
 * <h2>这条 SQL 的十个约束（逐条对应任务契约）</h2>
 * <ol>
 *   <li><b>只检索已索引文档</b>：{@code d.status = 'INDEXED'}；
 *       {@code PARSED}/{@code INDEXING}/{@code INDEX_FAILED} 的文档即使有历史向量也不参与检索；</li>
 *   <li><b>描述符精确匹配</b>：文档上的 {@code embedding_provider/model/dimensions} 与向量行上的
 *       {@code provider/model/embedding_dimensions} 必须同时等于当前配置 ——
 *       换了模型之后，旧模型的向量不会被误用（它的相似度与当前查询向量不在同一空间）；</li>
 *   <li><b>连接切片表</b>：{@code document_id} 与 {@code chunk_index} 相等，
 *       且 {@code embedding.chunk_sha256 = chunk.sha256} ——
 *       摘要不符说明切片在写入向量之后被换过，此时引用出去的内容与向量描述的不是同一段文本；</li>
 *   <li><b>相似度 = 1 - 余弦距离</b>：{@code 1 - (e.embedding <=> ?::vector)}；</li>
 *   <li><b>阈值含边界</b>：{@code >= ?}；</li>
 *   <li><b>稳定排序</b>：第一排序键是<b>距离升序</b>（{@code embedding <=> ?::vector ASC}），
 *       然后 {@code document_id ASC}、{@code chunk_index ASC}。
 *       tie-break 必须存在：距离完全相同的两行在两次检索里可能以任意顺序返回，
 *       而引用编号（K1、K2……）是按最终顺序确定的；</li>
 *   <li><b>LIMIT 用经过校验的 topK</b>：调用方（用例服务）已经把它限制在配置范围内；</li>
 *   <li><b>全部参数绑定</b>：查询向量、描述符三项、阈值与 limit 都是 {@code ?}，
 *       没有任何字符串拼接；向量只以 {@code ?::vector} 的形式出现；</li>
 *   <li><b>只读</b>：没有 {@code FOR UPDATE}，没有 {@code INSERT}/{@code UPDATE}/{@code DELETE}，
 *       也不参与任何跨模型调用的事务；</li>
 *   <li><b>保留距离操作符排序</b>：<b>不</b>写成 {@code ORDER BY (1 - distance) DESC} ——
 *       那种写法会让 pgvector 无法使用 V6 的 HNSW {@code vector_cosine_ops} 索引，
 *       退化成全表扫描加排序。</li>
 * </ol>
 *
 * <h2>参数顺序（与适配器的绑定顺序一一对应）</h2>
 * <ol>
 *   <li>查询向量字面量（SELECT 里的相似度计算）；</li>
 *   <li>文档上的 provider；</li>
 *   <li>文档上的 model；</li>
 *   <li>文档上的 dimensions；</li>
 *   <li>向量行上的 provider；</li>
 *   <li>向量行上的 model；</li>
 *   <li>向量行上的 dimensions；</li>
 *   <li>查询向量字面量（WHERE 里的阈值过滤）；</li>
 *   <li>minScore；</li>
 *   <li>查询向量字面量（ORDER BY 里的距离排序）；</li>
 *   <li>topK。</li>
 * </ol>
 * <p>同一个查询向量绑定三次是刻意的：{@code ORDER BY} 里的距离表达式必须与索引表达式一致，
 * pgvector 才会选择 HNSW 索引扫描；把它换成子查询里的别名会让计划退化。</p>
 */
final class KnowledgeRetrievalSql {

    /** 检索命中的列：文档元数据 + 切片 + 相似度。 */
    static final String MATCH_COLUMNS = "d.id AS document_id, d.version AS document_version, "
            + "d.title AS document_title, e.chunk_index AS chunk_index, e.chunk_sha256 AS chunk_sha256, "
            + "c.content AS content, 1 - (e.embedding <=> ?::vector) AS score";

    /** 参数绑定的总个数（供测试与适配器共享，避免两处各写一遍数字）。 */
    static final int PARAMETER_COUNT = 11;

    /**
     * 相似度检索：距离升序（等价于相似度降序），带确定性 tie-break。
     */
    static final String SELECT_MATCHES = "SELECT " + MATCH_COLUMNS + " "
            + "FROM knowledge_document_chunk_embeddings e "
            + "JOIN knowledge_documents d ON d.id = e.document_id "
            + "JOIN knowledge_document_chunks c ON c.document_id = e.document_id "
            + "AND c.chunk_index = e.chunk_index "
            + "WHERE d.status = 'INDEXED' "
            + "AND d.embedding_provider = ? AND d.embedding_model = ? AND d.embedding_dimensions = ? "
            + "AND e.provider = ? AND e.model = ? AND e.embedding_dimensions = ? "
            + "AND c.sha256 = e.chunk_sha256 "
            + "AND 1 - (e.embedding <=> ?::vector) >= ? "
            + "ORDER BY e.embedding <=> ?::vector ASC, e.document_id ASC, e.chunk_index ASC "
            + "LIMIT ?";

    private KnowledgeRetrievalSql() {
    }
}
