package com.flowdesk.infrastructure.knowledge.persistence.jdbc;

import static com.flowdesk.infrastructure.knowledge.persistence.jdbc.KnowledgePersistenceTestSupport.UPLOADED_AT;
import static com.flowdesk.infrastructure.knowledge.persistence.jdbc.KnowledgePersistenceTestSupport.chunksOf;
import static com.flowdesk.infrastructure.knowledge.persistence.jdbc.KnowledgePersistenceTestSupport.insertUploaded;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.domain.knowledge.EmbeddingDescriptor;
import com.flowdesk.domain.knowledge.KnowledgeDocument;
import com.flowdesk.domain.knowledge.KnowledgeDocumentChunkEmbedding;
import com.flowdesk.domain.knowledge.Sha256Digest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * PostgreSQL + pgvector 集成测试（FD-0010）。
 *
 * <p>只有真实 PostgreSQL 才能验证的东西全在这里：{@code vector(1024)} 列、
 * {@code vector_dims} 约束、HNSW 余弦索引、复合外键级联删除，以及
 * 「向量写入 + 文档状态推进」在同一事务里提交/回滚。</p>
 *
 * <p><b>没有 Docker 时整个类会跳过</b>（{@code disabledWithoutDocker = true}），
 * 交付报告中如实标注 {@code POSTGRESQL_PGVECTOR_IT=NOT_RUN}，绝不宣称已通过。
 * 镜像固定为 pgvector 官方镜像的<b>具体版本</b>，不使用 {@code latest}。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class JdbcKnowledgeDocumentEmbeddingStorePostgresTests {

    /** 固定版本的 pgvector 官方镜像（PostgreSQL 16）。 */
    private static final DockerImageName PGVECTOR_IMAGE =
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PGVECTOR_IMAGE)
            .withDatabaseName("flowdesk")
            .withUsername("flowdesk")
            .withPassword("flowdesk");

    private static MigrateResult migrateResult;

    private static JdbcClient jdbcClient;

    private static JdbcKnowledgeDocumentRepository repository;

    private static JdbcKnowledgeDocumentChunkStore chunkStore;

    private static JdbcKnowledgeDocumentEmbeddingStore embeddingStore;

    private static StatementInterceptingDataSource intercepting;

    private static DriverManagerDataSource dataSource;

    @BeforeAll
    static void migrate() {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        // 与 application-postgres.yml 一致：通用目录 + PostgreSQL 专用目录
        migrateResult = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration", "classpath:db/postgresql-migration")
                .load()
                .migrate();

        intercepting = new StatementInterceptingDataSource(dataSource);
        jdbcClient = JdbcClient.create(intercepting);
        TransactionTemplate transactions = new TransactionTemplate(
                new DataSourceTransactionManager(intercepting));
        repository = new JdbcKnowledgeDocumentRepository(jdbcClient, transactions);
        chunkStore = new JdbcKnowledgeDocumentChunkStore(jdbcClient, transactions, repository);
        embeddingStore = new JdbcKnowledgeDocumentEmbeddingStore(jdbcClient, transactions, repository);
    }

    @BeforeEach
    void clearTables() {
        jdbcClient.sql("DELETE FROM knowledge_document_chunk_embeddings").update();
        jdbcClient.sql("DELETE FROM knowledge_document_chunks").update();
        jdbcClient.sql("DELETE FROM knowledge_documents").update();
        intercepting.resetStatements();
        intercepting.failOnStatement(0);
    }

    // ---------- 迁移与结构 ----------

    @Test
    void flywayAppliesAllSixMigrationsIncludingThePgVectorOne() {
        assertThat(migrateResult.migrationsExecuted)
                .as("V1~V5 通用 + V6 PostgreSQL 专用")
                .isEqualTo(6);
    }

    @Test
    void theVectorExtensionExists() {
        Long count = jdbcClient.sql("SELECT COUNT(*) FROM pg_extension WHERE extname = 'vector'")
                .query(Long.class).single();

        assertThat(count).as("pgvector 扩展必须由 V6 创建").isEqualTo(1L);
    }

    @Test
    void theEmbeddingColumnIsAVectorWith1024Dimensions() {
        String columnType = jdbcClient.sql("SELECT format_type(a.atttypid, a.atttypmod) "
                + "FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid "
                + "WHERE c.relname = 'knowledge_document_chunk_embeddings' AND a.attname = 'embedding'")
                .query(String.class).single();

        assertThat(columnType).as("列类型必须是 vector(1024)").isEqualTo("vector(1024)");
    }

    @Test
    void theHnswCosineIndexExists() {
        String indexDefinition = jdbcClient.sql("SELECT indexdef FROM pg_indexes "
                + "WHERE tablename = 'knowledge_document_chunk_embeddings' "
                + "AND indexname = 'idx_knowledge_document_chunk_embeddings_hnsw'")
                .query(String.class).single();

        assertThat(indexDefinition)
                .containsIgnoringCase("USING hnsw")
                .containsIgnoringCase("vector_cosine_ops");
    }

    @Test
    void noGenericVectorStoreTableIsCreated() {
        Long tables = jdbcClient.sql("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_name = 'vector_store'").query(Long.class).single();

        assertThat(tables).as("本项目不使用自动初始化的通用 vector_store 表").isZero();
    }

    @Test
    void deletingTheDocumentCascadesToChunksAndVectors() {
        KnowledgeDocument document = indexedDocument("key-cascade", 3);
        assertThat(countVectors(document.id())).isEqualTo(3);

        jdbcClient.sql("DELETE FROM knowledge_documents WHERE id = ?")
                .param(1, document.id().value()).update();

        assertThat(countVectors(document.id())).isZero();
        assertThat(chunkStore.countChunks(document.id())).isZero();
    }

    // ---------- 维度约束 ----------

    @Test
    void theDatabaseRejectsVectorsWhoseDimensionsDoNotMatchTheDeclaredValue() {
        KnowledgeDocument document = indexingDocument("key-dims", 1);

        // 1023 维：列宽是 vector(1024)，pgvector 在写入时直接拒绝
        assertThatThrownBy(() -> insertRawVector(document, literal(1023)))
                .as("1023 维必须被数据库拒绝")
                .isInstanceOf(org.springframework.dao.DataAccessException.class);

        // 1025 维：同样拒绝
        assertThatThrownBy(() -> insertRawVector(document, literal(1025)))
                .as("1025 维必须被数据库拒绝")
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    // ---------- 原子完成 ----------

    @Test
    void completingIndexingWritesAllVectorsAndAdvancesTheDocumentInOneTransaction() {
        KnowledgeDocument document = indexingDocument("key-atomic", 23);
        List<KnowledgeDocumentChunkEmbedding> embeddings = embeddings(document, 23);

        document.markIndexed(UPLOADED_AT.plusSeconds(6));
        var saved = embeddingStore.completeIndexing(document, 3L, embeddings);

        assertThat(saved.version()).isEqualTo(4L);
        assertThat(countVectors(document.id())).isEqualTo(23);
        assertThat(statusOf(document)).isEqualTo("INDEXED");
        assertThat(versionOf(document)).isEqualTo(4L);
        assertThat(embeddingProviderOf(document)).isEqualTo("dashscope");
        assertThat(embeddingDimensionsOf(document)).isEqualTo(1024);
    }

    @Test
    void aMidwayInsertFailureRollsBackTheWholeBatch() {
        KnowledgeDocument document = indexingDocument("key-rollback", 3);
        List<KnowledgeDocumentChunkEmbedding> embeddings = embeddings(document, 3);
        document.markIndexed(UPLOADED_AT.plusSeconds(6));

        // 让第 4 条语句（第一条向量 INSERT 之后的第二条）失败：语句序列是
        // SELECT FOR UPDATE(1) → SELECT 摘要证明(2) → DELETE(3) → INSERT(4)…
        intercepting.resetStatements();
        intercepting.failOnStatement(5);

        assertThatThrownBy(() -> embeddingStore.completeIndexing(document, 3L, embeddings))
                .isInstanceOf(KnowledgeApplicationException.class)
                .extracting(thrown -> ((KnowledgeApplicationException) thrown).errorCode())
                .isEqualTo(KnowledgeApplicationErrorCode.METADATA_STORAGE_FAILURE);

        assertThat(countVectors(document.id()))
                .as("已插入的向量必须随事务一起回滚")
                .isZero();
        assertThat(statusOf(document)).as("文档不得变成 INDEXED").isEqualTo("INDEXING");
        assertThat(versionOf(document)).isEqualTo(3L);
    }

    @Test
    void aDigestMismatchIsRejectedAndNothingIsWritten() {
        KnowledgeDocument document = indexingDocument("key-digest", 2);
        // 在读取向量之后、写入之前替换切片内容：向量与其切片的摘要不再匹配
        jdbcClient.sql("UPDATE knowledge_document_chunks SET sha256 = ? WHERE document_id = ? AND chunk_index = 1")
                .param(1, "f".repeat(64))
                .param(2, document.id().value())
                .update();

        List<KnowledgeDocumentChunkEmbedding> embeddings = embeddingsFromStoredChunks(document, 2);
        document.markIndexed(UPLOADED_AT.plusSeconds(6));

        assertThatThrownBy(() -> embeddingStore.completeIndexing(document, 3L, embeddings))
                .isInstanceOf(KnowledgeApplicationException.class)
                .extracting(thrown -> ((KnowledgeApplicationException) thrown).errorCode())
                .isEqualTo(KnowledgeApplicationErrorCode.KNOWLEDGE_INTERNAL_ERROR);

        assertThat(countVectors(document.id())).isZero();
        assertThat(statusOf(document)).isEqualTo("INDEXING");
    }

    @Test
    void aVersionConflictIsRejectedBeforeAnyVectorIsWritten() {
        KnowledgeDocument document = indexingDocument("key-version", 2);
        document.markIndexed(UPLOADED_AT.plusSeconds(6));

        assertThatThrownBy(() -> embeddingStore.completeIndexing(document, 99L, embeddings(document, 2)))
                .isInstanceOf(KnowledgeApplicationException.class)
                .extracting(thrown -> ((KnowledgeApplicationException) thrown).errorCode())
                .isEqualTo(KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_VERSION_CONFLICT);

        assertThat(countVectors(document.id())).isZero();
        assertThat(statusOf(document)).isEqualTo("INDEXING");
    }

    @Test
    void reindexingReplacesPreviousVectorsInsteadOfAccumulatingThem() {
        KnowledgeDocument document = indexedDocument("key-replace", 3);
        assertThat(countVectors(document.id())).isEqualTo(3);

        // 人工把文档退回 INDEX_FAILED（与文档里记录的恢复方式一致），再用更少的切片重新索引
        jdbcClient.sql("UPDATE knowledge_documents SET status = 'INDEX_FAILED', indexed_at = NULL, "
                        + "index_failed_at = ?, index_failure_code = 'VECTOR_STORAGE_FAILURE', updated_at = ? "
                        + "WHERE id = ?")
                .param(1, offset(UPLOADED_AT.plusSeconds(7)))
                .param(2, offset(UPLOADED_AT.plusSeconds(7)))
                .param(3, document.id().value())
                .update();
        jdbcClient.sql("DELETE FROM knowledge_document_chunks WHERE document_id = ? AND chunk_index > 0")
                .param(1, document.id().value()).update();

        KnowledgeDocument failed = repository.findById(document.id()).orElseThrow().document();
        failed.markIndexing(DESCRIPTOR, UPLOADED_AT.plusSeconds(8));
        long claimed = repository.update(failed, 4L).version();
        KnowledgeDocument indexing = repository.findById(document.id()).orElseThrow().document();
        indexing.markIndexed(UPLOADED_AT.plusSeconds(9));
        embeddingStore.completeIndexing(indexing, claimed, embeddings(indexing, 1));

        assertThat(countVectors(document.id())).as("旧向量必须被替换").isEqualTo(1);
    }

    // ---------- 辅助 ----------

    private static final EmbeddingDescriptor DESCRIPTOR =
            EmbeddingDescriptor.of("dashscope", "text-embedding-v4");

    private static java.time.OffsetDateTime offset(Instant instant) {
        return java.time.OffsetDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
    }

    /** 造一个数据库里处于 INDEXING（版本 3）的文档，并写入切片。 */
    private static KnowledgeDocument indexingDocument(String contentKey, int chunkCount) {
        KnowledgeDocument document = insertUploaded(repository, contentKey);
        long claimed = KnowledgePersistenceTestSupport.claim(repository, document, 0L);
        document.markParsed(UPLOADED_AT.plusSeconds(2));
        chunkStore.completeParsing(document, claimed, chunksOf(document.id(), chunkCount));

        KnowledgeDocument parsed = repository.findById(document.id()).orElseThrow().document();
        parsed.markIndexing(DESCRIPTOR, UPLOADED_AT.plusSeconds(4));
        repository.update(parsed, 2L);
        return repository.findById(document.id()).orElseThrow().document();
    }

    /** 造一个已经完成索引（INDEXED，版本 4）的文档。 */
    private static KnowledgeDocument indexedDocument(String contentKey, int chunkCount) {
        KnowledgeDocument indexing = indexingDocument(contentKey, chunkCount);
        List<KnowledgeDocumentChunkEmbedding> embeddings = embeddings(indexing, chunkCount);
        indexing.markIndexed(UPLOADED_AT.plusSeconds(6));
        embeddingStore.completeIndexing(indexing, 3L, embeddings);
        return repository.findById(indexing.id()).orElseThrow().document();
    }

    private static List<KnowledgeDocumentChunkEmbedding> embeddings(KnowledgeDocument document, int count) {
        List<KnowledgeDocumentChunkEmbedding> embeddings = new ArrayList<>(count);
        for (var chunk : chunkStore.findChunks(document.id(), 0, count)) {
            embeddings.add(new KnowledgeDocumentChunkEmbedding(document.id(), chunk.chunkIndex(),
                    chunk.sha256(), DESCRIPTOR, vector(1.0f)));
        }
        return embeddings;
    }

    /** 直接按库中当前摘要构造向量：用于「摘要已被改坏」的反例。 */
    private static List<KnowledgeDocumentChunkEmbedding> embeddingsFromStoredChunks(KnowledgeDocument document,
            int count) {

        List<KnowledgeDocumentChunkEmbedding> embeddings = new ArrayList<>(count);
        for (var chunk : chunkStore.findChunks(document.id(), 0, count)) {
            // 用「变更前」的摘要构造，从而与库中当前值不一致
            String original = "chunk-" + chunk.chunkIndex();
            embeddings.add(new KnowledgeDocumentChunkEmbedding(document.id(), chunk.chunkIndex(),
                    Sha256Digest.of(KnowledgePersistenceTestSupport.sha256Of(original)), DESCRIPTOR,
                    vector(1.0f)));
        }
        return embeddings;
    }

    private static float[] vector(float value) {
        float[] vector = new float[EmbeddingDescriptor.REQUIRED_DIMENSIONS];
        Arrays.fill(vector, value);
        return vector;
    }

    private static String literal(int dimensions) {
        float[] vector = new float[dimensions];
        Arrays.fill(vector, 0.5f);
        StringBuilder literal = new StringBuilder(dimensions * 6 + 2).append('[');
        for (int index = 0; index < dimensions; index++) {
            if (index > 0) {
                literal.append(',');
            }
            literal.append("0.5");
        }
        return literal.append(']').toString();
    }

    private static void insertRawVector(KnowledgeDocument document, String vectorLiteral) {
        jdbcClient.sql("INSERT INTO knowledge_document_chunk_embeddings (document_id, chunk_index, chunk_sha256, "
                        + "embedding, provider, model, embedding_dimensions, created_at) "
                        + "VALUES (?, 0, ?, ?::vector, 'dashscope', 'text-embedding-v4', 1024, ?)")
                .param(1, document.id().value())
                .param(2, chunkStore.findChunks(document.id(), 0, 1).get(0).sha256().value())
                .param(3, vectorLiteral)
                .param(4, offset(UPLOADED_AT.plusSeconds(6)))
                .update();
    }

    private static long countVectors(com.flowdesk.domain.knowledge.KnowledgeDocumentId documentId) {
        Long count = jdbcClient.sql("SELECT COUNT(*) FROM knowledge_document_chunk_embeddings "
                + "WHERE document_id = ?::uuid").param(1, documentId.value()).query(Long.class).single();
        return count == null ? -1L : count;
    }

    private static String statusOf(KnowledgeDocument document) {
        return jdbcClient.sql("SELECT status FROM knowledge_documents WHERE id = ?")
                .param(1, document.id().value()).query(String.class).single();
    }

    private static long versionOf(KnowledgeDocument document) {
        Long version = jdbcClient.sql("SELECT version FROM knowledge_documents WHERE id = ?")
                .param(1, document.id().value()).query(Long.class).single();
        return version == null ? -1L : version;
    }

    private static String embeddingProviderOf(KnowledgeDocument document) {
        return jdbcClient.sql("SELECT embedding_provider FROM knowledge_documents WHERE id = ?")
                .param(1, document.id().value()).query(String.class).single();
    }

    private static int embeddingDimensionsOf(KnowledgeDocument document) {
        Integer dimensions = jdbcClient.sql("SELECT embedding_dimensions FROM knowledge_documents WHERE id = ?")
                .param(1, document.id().value()).query(Integer.class).single();
        return dimensions == null ? -1 : dimensions;
    }
}
