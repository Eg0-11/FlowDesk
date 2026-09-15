package com.flowdesk.infrastructure.knowledge.persistence.jdbc;

import static com.flowdesk.infrastructure.knowledge.persistence.jdbc.KnowledgePersistenceTestSupport.UPLOADED_AT;
import static com.flowdesk.infrastructure.knowledge.persistence.jdbc.KnowledgePersistenceTestSupport.chunksOf;
import static com.flowdesk.infrastructure.knowledge.persistence.jdbc.KnowledgePersistenceTestSupport.insertUploaded;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.application.knowledge.index.DocumentIndexingException;
import com.flowdesk.domain.knowledge.EmbeddingDescriptor;
import com.flowdesk.domain.knowledge.KnowledgeDocument;
import com.flowdesk.domain.knowledge.KnowledgeDocumentChunkEmbedding;
import com.flowdesk.domain.knowledge.KnowledgeDocumentId;
import com.flowdesk.domain.knowledge.KnowledgeIndexFailureCode;
import com.flowdesk.domain.knowledge.Sha256Digest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * PostgreSQL + pgvector 集成测试（FD-0010 / FD-0010-R1）。
 *
 * <p>只有真实 PostgreSQL 才能验证的东西全在这里：{@code vector(1024)} 列、
 * {@code vector_dims} 约束、HNSW 余弦索引、复合外键级联删除，
 * 「向量写入 + 文档状态推进」在同一事务里提交/回滚（含跨写批次的回滚），
 * 以及 {@code PARSED → INDEXING} 的并发领取。</p>
 *
 * <p><b>没有 Docker 时整个类会跳过</b>（{@code disabledWithoutDocker = true}），
 * 交付报告中如实标注 {@code POSTGRESQL_PGVECTOR_IT=NOT_RUN}，绝不宣称已通过。</p>
 *
 * <h2>镜像固定（FD-0010-R1）</h2>
 * <p>FD-0010 用的是 {@code pgvector/pgvector:pg16} —— 那是<b>会移动的主版本标签</b>：
 * 同一个标签今天与半年后拉到的扩展版本可能不同（0.8.x 与后续版本的算子/行为并不保证一致），
 * 因此不能称作「固定具体版本」。现在固定到<b>带扩展版本的标签</b>
 * {@code pgvector/pgvector:0.8.6-pg16}（pgvector 0.8.6 + PostgreSQL 16）。</p>
 * <p>该标签在 2026-08-13 由 Docker Hub 解析到的 manifest list digest 为
 * {@code sha256:ccc6e83d6e35e931dc7c5def2022729d5a6c370318d099181995567ff1fb4d6b}；
 * 本机没有 Docker，无法拉取校验，因此这里只记录、不断言（标签仍是上游可重新推送的引用）。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class JdbcKnowledgeDocumentEmbeddingStorePostgresTests {

    /** 固定到 pgvector 扩展版本的官方镜像（pgvector 0.8.6 + PostgreSQL 16）。 */
    private static final DockerImageName PGVECTOR_IMAGE =
            DockerImageName.parse("pgvector/pgvector:0.8.6-pg16").asCompatibleSubstituteFor("postgres");

    /** 单批 2 行：让 5 条向量必然产生 3 个写批次。 */
    private static final int WRITE_BATCH_SIZE = 2;

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

    private static JdbcKnowledgeDocumentEmbeddingStore batchStore;

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
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(intercepting));
        repository = new JdbcKnowledgeDocumentRepository(jdbcClient, transactions);
        chunkStore = new JdbcKnowledgeDocumentChunkStore(jdbcClient, transactions, repository);
        embeddingStore = new JdbcKnowledgeDocumentEmbeddingStore(jdbcClient, intercepting, transactions, repository);
        // 收窄写批次，用于制造「前一批已执行、后一批失败」
        batchStore = new JdbcKnowledgeDocumentEmbeddingStore(jdbcClient, intercepting, transactions, repository,
                WRITE_BATCH_SIZE);
    }

    @BeforeEach
    void clearTables() {
        // 先关掉上一轮注入的失败，否则清理语句自己就会失败
        intercepting.failOnStatement(0);
        intercepting.failOnBatchExecution(0);
        intercepting.failOnSql(null);
        jdbcClient.sql("DELETE FROM knowledge_document_chunk_embeddings").update();
        jdbcClient.sql("DELETE FROM knowledge_document_chunks").update();
        jdbcClient.sql("DELETE FROM knowledge_documents").update();
        intercepting.resetStatements();
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

    // ---------- 真正的 JDBC 批处理（FD-0010-R1） ----------

    @Test
    void theWriteUsesRealJdbcBatchesOnPgVector() {
        KnowledgeDocument document = indexingDocument("key-batch", 5);
        List<KnowledgeDocumentChunkEmbedding> embeddings = embeddings(document, 5);
        document.markIndexed(UPLOADED_AT.plusSeconds(6));

        intercepting.resetStatements();
        batchStore.completeIndexing(document, 3L, embeddings);

        assertThat(countVectors(document.id())).isEqualTo(5);
        // 5 行、每批 2 行 → 3 次 executeBatch()，每批行数不超过 writeBatchSize
        assertThat(intercepting.batchExecutions()).isEqualTo(3);
        assertThat(intercepting.batchSizes()).containsExactlyInAnyOrder(2, 2, 1);
        assertThat(intercepting.batchAdds()).isEqualTo(5);
        assertThat(intercepting.batchExecutionSql()).allSatisfy(sql -> assertThat(sql)
                .contains("INSERT INTO knowledge_document_chunk_embeddings")
                .doesNotContain("),("));
        assertThat(intercepting.singleExecutionSql())
                .as("向量写入不得走 N 次 executeUpdate")
                .noneMatch(sql -> sql.contains("INSERT INTO knowledge_document_chunk_embeddings"));
    }

    @Test
    void aLaterWriteBatchFailureRollsBackTheEarlierBatchesTheDeleteAndTheIndexedTransition() {
        KnowledgeDocument document = indexingDocument("key-later-batch", 5);
        insertOldVectors(document.id(), 5);

        List<KnowledgeDocumentChunkEmbedding> embeddings = embeddings(document, 5);
        document.markIndexed(UPLOADED_AT.plusSeconds(6));

        intercepting.resetStatements();
        // 第 3 批失败：第 1、2 批（共 4 行）已经真实下发到 PostgreSQL
        intercepting.failOnBatchExecution(3);

        assertThatThrownBy(() -> batchStore.completeIndexing(document, 3L, embeddings))
                .isInstanceOf(DocumentIndexingException.class)
                .extracting(thrown -> ((DocumentIndexingException) thrown).failureCode())
                .isEqualTo(KnowledgeIndexFailureCode.VECTOR_STORAGE_FAILURE);

        assertThat(intercepting.batchExecutions()).as("前两批已执行").isEqualTo(2);
        assertThat(intercepting.batchExecutionsFailed()).isEqualTo(1);
        assertThat(intercepting.singleExecutionSql())
                .as("删除旧向量发生在失败之前")
                .anyMatch(sql -> sql.contains("DELETE FROM knowledge_document_chunk_embeddings"));

        assertThat(digestsOf(document.id())).as("旧向量必须原样保留（DELETE 被回滚）")
                .hasSize(5).containsOnly("a".repeat(64));
        assertThat(statusOf(document)).as("文档不得变成 INDEXED").isEqualTo("INDEXING");
        assertThat(versionOf(document)).isEqualTo(3L);
    }

    @Test
    void aFailureAfterEveryBatchRollsBackEveryBatchOnPgVector() {
        KnowledgeDocument document = indexingDocument("key-after-batches", 5);
        insertOldVectors(document.id(), 5);

        List<KnowledgeDocumentChunkEmbedding> embeddings = embeddings(document, 5);
        document.markIndexed(UPLOADED_AT.plusSeconds(6));

        intercepting.resetStatements();
        // 三个批次全部成功，让随后的文档状态 CAS 失败
        intercepting.failOnSql("UPDATE knowledge_documents SET");

        assertThatThrownBy(() -> batchStore.completeIndexing(document, 3L, embeddings))
                .isInstanceOf(DocumentIndexingException.class)
                .extracting(thrown -> ((DocumentIndexingException) thrown).failureCode())
                .isEqualTo(KnowledgeIndexFailureCode.VECTOR_STORAGE_FAILURE);

        assertThat(intercepting.batchExecutions()).isEqualTo(3);
        assertThat(digestsOf(document.id())).hasSize(5).containsOnly("a".repeat(64));
        assertThat(statusOf(document)).isEqualTo("INDEXING");
        assertThat(versionOf(document)).isEqualTo(3L);
    }

    @Test
    void aDigestMismatchIsReportedAsChunkDataInvalidAndNothingIsWritten() {
        KnowledgeDocument document = indexingDocument("key-digest", 2);
        // 在读取向量之后、写入之前替换切片内容：向量与其切片的摘要不再匹配
        jdbcClient.sql("UPDATE knowledge_document_chunks SET sha256 = ? WHERE document_id = ? AND chunk_index = 1")
                .param(1, "f".repeat(64))
                .param(2, document.id().value())
                .update();

        List<KnowledgeDocumentChunkEmbedding> embeddings = embeddingsFromStoredChunks(document, 2);
        document.markIndexed(UPLOADED_AT.plusSeconds(6));

        intercepting.resetStatements();
        assertThatThrownBy(() -> embeddingStore.completeIndexing(document, 3L, embeddings))
                .isInstanceOf(DocumentIndexingException.class)
                .extracting(thrown -> ((DocumentIndexingException) thrown).failureCode())
                .isEqualTo(KnowledgeIndexFailureCode.CHUNK_DATA_INVALID);

        assertThat(intercepting.batchExecutions()).as("摘要不一致时一条向量都不写").isZero();
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

    // ---------- 并发领取（FD-0010-R1） ----------

    @Test
    void concurrentIndexingClaimsOnPostgresAllowExactlyOneWinner() throws Exception {
        KnowledgeDocumentId documentId = parsedDocumentId("key-claim", 2);
        int attempts = 8;
        long snapshotVersion = 2L;

        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CyclicBarrier snapshotsReady = new CyclicBarrier(attempts);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int index = 0; index < attempts; index++) {
                tasks.add(() -> {
                    // 所有线程持有同一份快照（版本 2、PARSED）
                    KnowledgeDocument snapshot = repository.findById(documentId).orElseThrow().document();
                    snapshotsReady.await();
                    try {
                        snapshot.markIndexing(DESCRIPTOR, UPLOADED_AT.plusSeconds(4));
                        repository.update(snapshot, snapshotVersion);
                        return "claimed";
                    }
                    catch (KnowledgeApplicationException ex) {
                        return ex.errorCode().name();
                    }
                });
            }

            List<Future<String>> results = pool.invokeAll(tasks);
            List<String> outcomes = new ArrayList<>();
            for (Future<String> result : results) {
                outcomes.add(result.get());
            }

            assertThat(outcomes).filteredOn("claimed"::equals)
                    .as("并发领取只能有一个成功进入 INDEXING")
                    .hasSize(1);
            assertThat(outcomes).filteredOn(outcome -> !"claimed".equals(outcome))
                    .as("其余请求必须是版本冲突")
                    .hasSize(attempts - 1)
                    .allSatisfy(outcome -> assertThat(outcome)
                            .isEqualTo(KnowledgeApplicationErrorCode
                                    .KNOWLEDGE_DOCUMENT_VERSION_CONFLICT.name()));
        }
        finally {
            pool.shutdownNow();
        }

        assertThat(statusOfId(documentId)).isEqualTo("INDEXING");
        assertThat(versionOfId(documentId)).as("数据库版本只增加一次").isEqualTo(3L);
    }

    // ---------- 辅助 ----------

    private static final EmbeddingDescriptor DESCRIPTOR =
            EmbeddingDescriptor.of("dashscope", "text-embedding-v4");

    private static java.time.OffsetDateTime offset(Instant instant) {
        return java.time.OffsetDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
    }

    /** 造一个数据库里处于 INDEXING（版本 3）的文档，并写入切片。 */
    private static KnowledgeDocument indexingDocument(String contentKey, int chunkCount) {
        KnowledgeDocumentId documentId = parsedDocumentId(contentKey, chunkCount);
        KnowledgeDocument parsed = repository.findById(documentId).orElseThrow().document();
        parsed.markIndexing(DESCRIPTOR, UPLOADED_AT.plusSeconds(4));
        repository.update(parsed, 2L);
        return repository.findById(documentId).orElseThrow().document();
    }

    /** 上传 → 领取解析 → 完成解析：返回处于 PARSED（版本 2）的文档标识。 */
    private static KnowledgeDocumentId parsedDocumentId(String contentKey, int chunkCount) {
        KnowledgeDocument document = insertUploaded(repository, contentKey);
        long claimed = KnowledgePersistenceTestSupport.claim(repository, document, 0L);
        document.markParsed(UPLOADED_AT.plusSeconds(2));
        chunkStore.completeParsing(document, claimed, chunksOf(document.id(), chunkCount));
        return document.id();
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
        StringBuilder literal = new StringBuilder(dimensions * 6 + 2).append('[');
        for (int index = 0; index < dimensions; index++) {
            if (index > 0) {
                literal.append(',');
            }
            literal.append("0.5");
        }
        return literal.append(']').toString();
    }

    /** 直接插入 {@code count} 条「旧向量」，摘要固定为全 a，用于识别回滚是否保留旧数据。 */
    private static void insertOldVectors(KnowledgeDocumentId documentId, int count) {
        for (var chunk : chunkStore.findChunks(documentId, 0, count)) {
            jdbcClient.sql("INSERT INTO knowledge_document_chunk_embeddings "
                            + "(document_id, chunk_index, chunk_sha256, embedding, provider, model, "
                            + "embedding_dimensions, created_at) VALUES (?, ?, ?, ?::vector, 'dashscope', "
                            + "'text-embedding-v4', 1024, ?)")
                    .param(1, documentId.value())
                    .param(2, chunk.chunkIndex())
                    .param(3, "a".repeat(64))
                    .param(4, literal(EmbeddingDescriptor.REQUIRED_DIMENSIONS))
                    .param(5, offset(UPLOADED_AT.plusSeconds(5)))
                    .update();
        }
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

    private static long countVectors(KnowledgeDocumentId documentId) {
        Long count = jdbcClient.sql("SELECT COUNT(*) FROM knowledge_document_chunk_embeddings "
                + "WHERE document_id = ?::uuid").param(1, documentId.value()).query(Long.class).single();
        return count == null ? -1L : count;
    }

    private static List<String> digestsOf(KnowledgeDocumentId documentId) {
        return jdbcClient.sql("SELECT chunk_sha256 FROM knowledge_document_chunk_embeddings "
                        + "WHERE document_id = ?::uuid ORDER BY chunk_index")
                .param(1, documentId.value()).query(String.class).list().stream().map(String::strip).toList();
    }

    private static String statusOf(KnowledgeDocument document) {
        return statusOfId(document.id());
    }

    private static String statusOfId(KnowledgeDocumentId documentId) {
        return jdbcClient.sql("SELECT status FROM knowledge_documents WHERE id = ?")
                .param(1, documentId.value()).query(String.class).single();
    }

    private static long versionOf(KnowledgeDocument document) {
        return versionOfId(document.id());
    }

    private static long versionOfId(KnowledgeDocumentId documentId) {
        Long version = jdbcClient.sql("SELECT version FROM knowledge_documents WHERE id = ?")
                .param(1, documentId.value()).query(Long.class).single();
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
