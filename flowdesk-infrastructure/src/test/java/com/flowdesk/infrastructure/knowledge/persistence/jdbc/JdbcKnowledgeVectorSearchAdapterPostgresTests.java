package com.flowdesk.infrastructure.knowledge.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.application.knowledge.port.out.KnowledgeVectorMatch;
import com.flowdesk.domain.knowledge.EmbeddingDescriptor;
import com.flowdesk.domain.knowledge.KnowledgeQueryEmbedding;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * pgvector 相似度检索集成测试（RAG 4/6）。
 *
 * <p>只有真实 PostgreSQL + pgvector 才能验证的东西全在这里：{@code <=>} 距离算子、
 * {@code ?::vector} 参数绑定、HNSW 索引下的排名、minScore 的含边界过滤、
 * 确定性 tie-break，以及三条排除规则（非 {@code INDEXED} 文档、描述符不匹配、
 * 切片摘要不匹配）。</p>
 *
 * <p><b>没有 Docker 时整个类会跳过</b>（{@code disabledWithoutDocker = true}），
 * 交付报告中如实标注 {@code POSTGRESQL_PGVECTOR_IT=NOT_RUN}，绝不宣称已通过。
 * 镜像固定为带扩展版本的 {@code pgvector/pgvector:0.8.6-pg16}。</p>
 *
 * <h2>测试数据的构造方式</h2>
 * <p>直接写 SQL 插入「已索引」的文档、切片与向量：这样可以精确控制每个向量与查询向量的
 * 余弦相似度（用二维子空间 {@code (cos, sin)} 构造，其余维度为 0），
 * 从而对排名、阈值与 tie-break 做精确断言。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class JdbcKnowledgeVectorSearchAdapterPostgresTests {

    private static final DockerImageName PGVECTOR_IMAGE =
            DockerImageName.parse("pgvector/pgvector:0.8.6-pg16").asCompatibleSubstituteFor("postgres");

    private static final EmbeddingDescriptor DESCRIPTOR =
            EmbeddingDescriptor.of("dashscope", "text-embedding-v4");

    private static final Instant CREATED_AT = Instant.parse("2026-05-01T10:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PGVECTOR_IMAGE)
            .withDatabaseName("flowdesk")
            .withUsername("flowdesk")
            .withPassword("flowdesk");

    private static JdbcClient jdbcClient;

    private static JdbcKnowledgeVectorSearchAdapter adapter;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration", "classpath:db/postgresql-migration")
                .load()
                .migrate();

        jdbcClient = JdbcClient.create(dataSource);
        adapter = new JdbcKnowledgeVectorSearchAdapter(jdbcClient);
    }

    @BeforeEach
    void clearTables() {
        jdbcClient.sql("DELETE FROM knowledge_document_chunk_embeddings").update();
        jdbcClient.sql("DELETE FROM knowledge_document_chunks").update();
        jdbcClient.sql("DELETE FROM knowledge_documents").update();
    }

    // ---------- 排名、topK 与阈值 ----------

    @Test
    void ranksMatchesByCosineSimilarityDescending() {
        UUID documentId = indexedDocument("VPN 故障处理手册", DESCRIPTOR);
        chunk(documentId, 0, "完全相关", similarity(1.0));
        chunk(documentId, 1, "高度相关", similarity(0.8));
        chunk(documentId, 2, "中等相关", similarity(0.5));
        chunk(documentId, 3, "无关", similarity(0.0));

        List<KnowledgeVectorMatch> matches = adapter.search(query(similarity(1.0)), 0.0, 10);

        assertThat(matches).hasSize(4);
        assertThat(matches).extracting(match -> match.chunkIndex()).containsExactly(0, 1, 2, 3);
        assertThat(matches.get(0).score()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
        assertThat(matches.get(1).score()).isCloseTo(0.8, org.assertj.core.data.Offset.offset(1e-5));
        assertThat(matches.get(2).score()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-5));
        assertThat(matches.get(3).score()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-5));
        assertThat(matches.get(0).documentId().value()).isEqualTo(documentId);
        assertThat(matches.get(0).documentVersion()).isEqualTo(4L);
    }

    @Test
    void appliesTopK() {
        UUID documentId = indexedDocument("VPN 故障处理手册", DESCRIPTOR);
        chunk(documentId, 0, "完全相关", similarity(1.0));
        chunk(documentId, 1, "高度相关", similarity(0.8));
        chunk(documentId, 2, "中等相关", similarity(0.5));

        assertThat(adapter.search(query(similarity(1.0)), 0.0, 2)).hasSize(2);
        assertThat(adapter.search(query(similarity(1.0)), 0.0, 1)).hasSize(1);
        assertThat(adapter.search(query(similarity(1.0)), 0.0, 1).get(0).chunkIndex()).isZero();
    }

    @Test
    void appliesMinScoreInclusively() {
        UUID documentId = indexedDocument("VPN 故障处理手册", DESCRIPTOR);
        chunk(documentId, 0, "完全相关", similarity(1.0));
        chunk(documentId, 1, "高度相关", similarity(0.8));
        chunk(documentId, 2, "中等相关", similarity(0.5));

        // 恰好等于阈值：必须包含（>= 而不是 >）
        assertThat(adapter.search(query(similarity(1.0)), 0.5, 10)).hasSize(3);
        assertThat(adapter.search(query(similarity(1.0)), 0.5000001, 10)).hasSize(2);
        assertThat(adapter.search(query(similarity(1.0)), 0.81, 10)).hasSize(1);
        assertThat(adapter.search(query(similarity(1.0)), 1.0, 10)).hasSize(1);
    }

    @Test
    void withoutMatchesReturnsAnEmptyList() {
        UUID documentId = indexedDocument("VPN 故障处理手册", DESCRIPTOR);
        chunk(documentId, 0, "无关", similarity(0.0));

        assertThat(adapter.search(query(similarity(1.0)), 0.9, 10)).isEmpty();
    }

    @Test
    void breaksTiesByDocumentIdThenChunkIndex() {
        // 距离完全相同的三行只能靠 tie-break 决定顺序，而 tie-break 的第一键是 document_id ASC，
        // 因此这里必须用**固定且顺序已知**的文档标识：用随机 UUID 的话，
        // 「谁排在前面」每次运行都不一样，断言会随机失败（测试夹具自身的不确定性）。
        UUID first = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
        UUID second = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
        indexedDocument(first, "A 手册", DESCRIPTOR);
        indexedDocument(second, "B 手册", DESCRIPTOR);
        // 三个完全相同的向量 → 距离完全相同，只能靠 tie-break 决定顺序
        chunk(first, 5, "A-5", similarity(0.8));
        chunk(first, 2, "A-2", similarity(0.8));
        chunk(second, 0, "B-0", similarity(0.8));

        List<KnowledgeVectorMatch> matches = adapter.search(query(similarity(1.0)), 0.5, 10);

        assertThat(matches).hasSize(3);
        assertThat(matches).extracting(match -> match.documentId().value() + "#" + match.chunkIndex())
                .as("document_id 升序在前，同一文档内 chunk_index 升序")
                .containsExactly(first + "#2", first + "#5", second + "#0");
    }

    // ---------- 排除规则 ----------

    @Test
    void excludesDocumentsThatAreNotIndexed() {
        UUID indexed = indexedDocument("已索引文档", DESCRIPTOR);
        chunk(indexed, 0, "已索引切片", similarity(0.5));
        UUID parsed = documentWithStatus("PARSED", "仅解析文档", DESCRIPTOR);
        chunk(parsed, 0, "仅解析切片", similarity(1.0));

        List<KnowledgeVectorMatch> matches = adapter.search(query(similarity(1.0)), 0.0, 10);

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).documentId().value()).isEqualTo(indexed);
    }

    @Test
    void excludesVectorsWhoseDescriptorDoesNotMatch() {
        UUID matching = indexedDocument("匹配模型", DESCRIPTOR);
        chunk(matching, 0, "匹配切片", similarity(0.5));

        // 文档上声明了别的模型
        UUID otherModelOnDocument = documentWithStatus("INDEXED", "文档模型不同",
                EmbeddingDescriptor.of("dashscope", "text-embedding-v3"));
        chunk(otherModelOnDocument, 0, "文档模型不同的切片", similarity(1.0));

        // 向量行上声明了别的模型（文档行是当前模型）
        UUID otherModelOnVector = indexedDocument("向量模型不同", DESCRIPTOR);
        chunkWithDescriptor(otherModelOnVector, 0, "向量模型不同的切片", similarity(1.0),
                EmbeddingDescriptor.of("dashscope", "text-embedding-v3"));

        List<KnowledgeVectorMatch> matches = adapter.search(query(similarity(1.0)), 0.0, 10);

        assertThat(matches).extracting(match -> match.documentId().value())
                .as("只有 provider/model/dimensions 全部匹配的向量才参与检索")
                .containsExactly(matching);
    }

    @Test
    void excludesChunksWhoseDigestDoesNotMatchTheEmbedding() {
        UUID documentId = indexedDocument("摘要不一致", DESCRIPTOR);
        chunk(documentId, 0, "摘要一致", similarity(0.5));
        chunkWithTamperedDigest(documentId, 1, "摘要被改坏", similarity(1.0));

        List<KnowledgeVectorMatch> matches = adapter.search(query(similarity(1.0)), 0.0, 10);

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).chunkIndex()).as("摘要不符的向量必须被排除").isZero();
    }

    // ---------- 映射与只读 ----------

    @Test
    void mapsUnicodeTitlesAndContents() {
        UUID documentId = indexedDocument("VPN 故障处理手册 \uD83D\uDE00", DESCRIPTOR);
        chunk(documentId, 3, "第一步：检查隧道状态，确认 \uD83D\uDD10 密钥未过期", similarity(1.0));

        KnowledgeVectorMatch match = adapter.search(query(similarity(1.0)), 0.9, 10).get(0);

        assertThat(match.documentTitle()).isEqualTo("VPN 故障处理手册 \uD83D\uDE00");
        assertThat(match.content()).isEqualTo("第一步：检查隧道状态，确认 \uD83D\uDD10 密钥未过期");
        assertThat(match.chunkIndex()).isEqualTo(3);
        assertThat(match.chunkSha256().value()).hasSize(64);
    }

    @Test
    void theSearchIsReadOnly() {
        UUID documentId = indexedDocument("VPN 故障处理手册", DESCRIPTOR);
        chunk(documentId, 0, "完全相关", similarity(1.0));

        String statusBefore = statusOf(documentId);
        long versionBefore = versionOf(documentId);
        long vectorsBefore = countVectors(documentId);
        String digestBefore = digestOf(documentId, 0);
        java.time.OffsetDateTime updatedAtBefore = updatedAtOf(documentId);

        adapter.search(query(similarity(1.0)), 0.0, 5);

        assertThat(statusOf(documentId)).as("检索不得修改状态").isEqualTo(statusBefore);
        assertThat(versionOf(documentId)).as("检索不得修改版本").isEqualTo(versionBefore);
        assertThat(countVectors(documentId)).as("检索不得写入或删除向量").isEqualTo(vectorsBefore);
        assertThat(digestOf(documentId, 0)).isEqualTo(digestBefore);
        assertThat(indexFailureCodeOf(documentId)).as("检索不得写失败码").isNull();
        assertThat(updatedAtOf(documentId)).as("检索不得触碰 updated_at").isEqualTo(updatedAtBefore);
    }

    /**
     * 数据库查询阶段的失败必须收敛为 {@code KNOWLEDGE_RETRIEVAL_FAILURE}，而<b>不是</b>领域输入错误。
     *
     * <p>查询向量是**合法的 1024 维向量**：{@link KnowledgeQueryEmbedding} 的构造期不变量
     * 要求「长度恰好等于描述符维度、全为有限值、不得全零」，所以不可能用「维度错的向量」
     * 制造数据库错误 —— 那样的向量在进入适配器之前就会被领域层拒绝，异常发生在被测代码之外，
     * 断言到的也不是数据库失败。</p>
     *
     * <p>因此失败由**测试范围内的真实数据库状态**制造：临时把向量表改名，让适配器那条
     * {@code SELECT} 在真实 PostgreSQL 上执行失败（{@code undefined_table}），
     * 并在 {@code finally} 里改回。前后各加一次正向对照，证明失败确实来自数据库状态，
     * 而不是查询向量或别的输入。</p>
     */
    @Test
    void aDatabaseFailureIsMappedToASafeRetrievalFailure() {
        UUID documentId = indexedDocument("VPN 故障处理手册", DESCRIPTOR);
        chunk(documentId, 0, "完全相关", similarity(1.0));

        // 合法的 1024 维查询向量（与其余用例用的是同一个构造函数）
        KnowledgeQueryEmbedding legalQuery = query(similarity(1.0));

        // 正向对照 1：合法查询在数据库正常时返回结果
        assertThat(adapter.search(legalQuery, 0.0, 5)).hasSize(1);

        // 在测试范围内制造真实的数据库查询失败：把向量表临时改名
        jdbcClient.sql("ALTER TABLE knowledge_document_chunk_embeddings "
                + "RENAME TO knowledge_document_chunk_embeddings_offline").update();
        try {
            assertThatThrownBy(() -> adapter.search(legalQuery, 0.0, 5))
                    .isInstanceOf(KnowledgeApplicationException.class)
                    // 根因必须是数据库访问失败（Spring 的 DataAccessException），
                    // 而不是参数校验或领域不变量拒绝
                    .hasCauseInstanceOf(DataAccessException.class)
                    .extracting(thrown -> ((KnowledgeApplicationException) thrown).errorCode())
                    .isEqualTo(KnowledgeApplicationErrorCode.KNOWLEDGE_RETRIEVAL_FAILURE);
        }
        finally {
            jdbcClient.sql("ALTER TABLE knowledge_document_chunk_embeddings_offline "
                    + "RENAME TO knowledge_document_chunk_embeddings").update();
        }

        // 正向对照 2：表恢复后同一个合法查询又能正常工作
        assertThat(adapter.search(legalQuery, 0.0, 5)).hasSize(1);
    }

    // ---------- 数据构造 ----------

    /**
     * 造一个「已索引」的文档（状态 INDEXED、版本 4、完整时间线），标识随机。
     *
     * @param title      标题
     * @param descriptor 文档上声明的描述符
     * @return 文档标识
     */
    private static UUID indexedDocument(String title, EmbeddingDescriptor descriptor) {
        return documentWithStatus(UUID.randomUUID(), "INDEXED", title, descriptor);
    }

    /**
     * 造一个「已索引」的文档，**指定**文档标识。
     *
     * <p>给 tie-break 用例使用：那一类断言依赖 {@code document_id ASC} 的排序，
     * 随机标识会让顺序两可。</p>
     *
     * @param documentId 文档标识
     * @param title      标题
     * @param descriptor 文档上声明的描述符
     * @return 文档标识（原样返回，便于链式使用）
     */
    private static UUID indexedDocument(UUID documentId, String title, EmbeddingDescriptor descriptor) {
        return documentWithStatus(documentId, "INDEXED", title, descriptor);
    }

    /**
     * 造一个指定状态的文档（标识随机）。
     *
     * @param status     状态：{@code PARSED} 或 {@code INDEXED}
     * @param title      标题
     * @param descriptor 描述符（仅 INDEXED 文档才有索引字段）
     * @return 文档标识
     */
    private static UUID documentWithStatus(String status, String title, EmbeddingDescriptor descriptor) {
        return documentWithStatus(UUID.randomUUID(), status, title, descriptor);
    }

    /**
     * 造一个指定状态、指定标识的文档。
     *
     * @param documentId 文档标识
     * @param status     状态：{@code PARSED} 或 {@code INDEXED}
     * @param title      标题
     * @param descriptor 描述符（仅 INDEXED 文档才有索引字段）
     * @return 文档标识（原样返回）
     */
    private static UUID documentWithStatus(UUID documentId, String status, String title,
            EmbeddingDescriptor descriptor) {
        Instant parsedAt = CREATED_AT.plusSeconds(1);
        Instant indexStartedAt = CREATED_AT.plusSeconds(2);
        Instant indexedAt = CREATED_AT.plusSeconds(3);
        Instant updatedAt = CREATED_AT.plusSeconds(4);

        if ("INDEXED".equals(status)) {
            jdbcClient.sql("INSERT INTO knowledge_documents (id, title, original_filename, format, media_type, "
                            + "size_bytes, sha256, content_key, status, version, created_at, updated_at, parsed_at, "
                            + "index_started_at, indexed_at, embedding_provider, embedding_model, "
                            + "embedding_dimensions) VALUES (?, ?, 'manual.txt', 'TEXT', 'text/plain', 64, ?, ?, "
                            + "'INDEXED', 4, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .param(1, documentId)
                    .param(2, title)
                    .param(3, KnowledgePersistenceTestSupport.sha256Of(documentId.toString()))
                    .param(4, "manual-" + documentId)
                    .param(5, offset(CREATED_AT))
                    .param(6, offset(updatedAt))
                    .param(7, offset(parsedAt))
                    .param(8, offset(indexStartedAt))
                    .param(9, offset(indexedAt))
                    .param(10, descriptor.provider())
                    .param(11, descriptor.model())
                    .param(12, descriptor.dimensions())
                    .update();
            return documentId;
        }

        jdbcClient.sql("INSERT INTO knowledge_documents (id, title, original_filename, format, media_type, "
                        + "size_bytes, sha256, content_key, status, version, created_at, updated_at, parsed_at) "
                        + "VALUES (?, ?, 'manual.txt', 'TEXT', 'text/plain', 64, ?, ?, 'PARSED', 2, ?, ?, ?)")
                .param(1, documentId)
                .param(2, title)
                .param(3, KnowledgePersistenceTestSupport.sha256Of(documentId.toString()))
                .param(4, "manual-" + documentId)
                .param(5, offset(CREATED_AT))
                .param(6, offset(updatedAt))
                .param(7, offset(parsedAt))
                .update();
        return documentId;
    }

    /**
     * 写入一条切片、对应的向量（描述符为当前配置）与匹配的摘要。
     *
     * @param documentId 文档标识
     * @param chunkIndex 切片序号
     * @param content    切片正文
     * @param vector     向量（与查询向量的余弦相似度即 {@code score}）
     */
    private static void chunk(UUID documentId, int chunkIndex, String content, float[] vector) {
        chunkWithDescriptor(documentId, chunkIndex, content, vector, DESCRIPTOR);
    }

    /**
     * 写入一条切片与向量（向量行使用指定描述符）。
     *
     * @param documentId 文档标识
     * @param chunkIndex 切片序号
     * @param content    切片正文
     * @param vector     向量
     * @param descriptor 向量行上声明的描述符
     */
    private static void chunkWithDescriptor(UUID documentId, int chunkIndex, String content, float[] vector,
            EmbeddingDescriptor descriptor) {

        String digest = KnowledgePersistenceTestSupport.sha256Of(content);
        jdbcClient.sql("INSERT INTO knowledge_document_chunks (document_id, chunk_index, content, "
                        + "code_point_count, sha256, created_at) VALUES (?, ?, ?, ?, ?, ?)")
                .param(1, documentId)
                .param(2, chunkIndex)
                .param(3, content)
                .param(4, content.codePointCount(0, content.length()))
                .param(5, digest)
                .param(6, offset(CREATED_AT.plusSeconds(1)))
                .update();
        insertEmbedding(documentId, chunkIndex, digest, vector, descriptor, descriptor.dimensions());
    }

    /**
     * 写入一条切片与向量，但让向量行记录的摘要与切片不一致。
     *
     * @param documentId 文档标识
     * @param chunkIndex 切片序号
     * @param content    切片正文
     * @param vector     向量
     */
    private static void chunkWithTamperedDigest(UUID documentId, int chunkIndex, String content, float[] vector) {
        String digest = KnowledgePersistenceTestSupport.sha256Of(content);
        jdbcClient.sql("INSERT INTO knowledge_document_chunks (document_id, chunk_index, content, "
                        + "code_point_count, sha256, created_at) VALUES (?, ?, ?, ?, ?, ?)")
                .param(1, documentId)
                .param(2, chunkIndex)
                .param(3, content)
                .param(4, content.codePointCount(0, content.length()))
                .param(5, digest)
                .param(6, offset(CREATED_AT.plusSeconds(1)))
                .update();
        // 向量行记录的摘要来自另一段文本 → 检索必须排除它
        insertEmbedding(documentId, chunkIndex, KnowledgePersistenceTestSupport.sha256Of("另一段文本"),
                vector, DESCRIPTOR, DESCRIPTOR.dimensions());
    }

    private static void insertEmbedding(UUID documentId, int chunkIndex, String chunkSha256, float[] vector,
            EmbeddingDescriptor descriptor, int dimensions) {

        jdbcClient.sql("INSERT INTO knowledge_document_chunk_embeddings (document_id, chunk_index, chunk_sha256, "
                        + "embedding, provider, model, embedding_dimensions, created_at) "
                        + "VALUES (?, ?, ?, ?::vector, ?, ?, ?, ?)")
                .param(1, documentId)
                .param(2, chunkIndex)
                .param(3, chunkSha256)
                .param(4, PgVectorLiteral.serialize(vector))
                .param(5, descriptor.provider())
                .param(6, descriptor.model())
                .param(7, dimensions)
                .param(8, offset(CREATED_AT.plusSeconds(2)))
                .update();
    }

    /**
     * 造一个 1024 维向量：在由 {@code (cos, sin)} 张成的二维子空间里取值，
     * 因此它与 {@code similarity(1.0)} 的余弦相似度<b>恰好</b>等于给定值。
     *
     * @param similarity 期望的余弦相似度（0..1）
     * @return 1024 维单位向量
     */
    private static float[] similarity(double similarity) {
        double angle = Math.acos(similarity);
        float[] vector = new float[EmbeddingDescriptor.REQUIRED_DIMENSIONS];
        vector[0] = (float) Math.cos(angle);
        vector[1] = (float) Math.sin(angle);
        return vector;
    }

    private static KnowledgeQueryEmbedding query(float[] vector) {
        return new KnowledgeQueryEmbedding(DESCRIPTOR, vector);
    }

    private static java.time.OffsetDateTime offset(Instant instant) {
        return java.time.OffsetDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
    }

    private static String statusOf(UUID documentId) {
        return jdbcClient.sql("SELECT status FROM knowledge_documents WHERE id = ?")
                .param(1, documentId).query(String.class).single();
    }

    private static long versionOf(UUID documentId) {
        Long version = jdbcClient.sql("SELECT version FROM knowledge_documents WHERE id = ?")
                .param(1, documentId).query(Long.class).single();
        return version == null ? -1L : version;
    }

    private static String indexFailureCodeOf(UUID documentId) {
        return jdbcClient.sql("SELECT index_failure_code FROM knowledge_documents WHERE id = ?")
                .param(1, documentId).query(String.class).optional().orElse(null);
    }

    private static java.time.OffsetDateTime updatedAtOf(UUID documentId) {
        return jdbcClient.sql("SELECT updated_at FROM knowledge_documents WHERE id = ?")
                .param(1, documentId).query(java.time.OffsetDateTime.class).single();
    }

    private static long countVectors(UUID documentId) {
        Long count = jdbcClient.sql("SELECT COUNT(*) FROM knowledge_document_chunk_embeddings "
                + "WHERE document_id = ?").param(1, documentId).query(Long.class).single();
        return count == null ? -1L : count;
    }

    private static String digestOf(UUID documentId, int chunkIndex) {
        return jdbcClient.sql("SELECT chunk_sha256 FROM knowledge_document_chunk_embeddings "
                        + "WHERE document_id = ? AND chunk_index = ?")
                .param(1, documentId).param(2, chunkIndex).query(String.class).single().strip();
    }
}
