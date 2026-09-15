package com.flowdesk.infrastructure.knowledge.persistence.jdbc;

import static com.flowdesk.infrastructure.knowledge.persistence.jdbc.KnowledgePersistenceTestSupport.UPLOADED_AT;
import static com.flowdesk.infrastructure.knowledge.persistence.jdbc.KnowledgePersistenceTestSupport.claim;
import static com.flowdesk.infrastructure.knowledge.persistence.jdbc.KnowledgePersistenceTestSupport.chunksOf;
import static com.flowdesk.infrastructure.knowledge.persistence.jdbc.KnowledgePersistenceTestSupport.insertUploaded;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.application.knowledge.index.DocumentIndexingException;
import com.flowdesk.domain.knowledge.EmbeddingDescriptor;
import com.flowdesk.domain.knowledge.KnowledgeDocument;
import com.flowdesk.domain.knowledge.KnowledgeDocumentChunk;
import com.flowdesk.domain.knowledge.KnowledgeDocumentChunkEmbedding;
import com.flowdesk.domain.knowledge.KnowledgeDocumentId;
import com.flowdesk.domain.knowledge.KnowledgeIndexFailureCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 「完成索引」的真实 JDBC 批处理与事务边界（FD-0010-R1，H2 可执行部分）。
 *
 * <h2>为什么这里能在 H2 上跑真实的写入路径</h2>
 * <p>pgvector 的 {@code vector(1024)} 类型在 H2 上不存在，因此测试在这里创建一张
 * <b>影子表</b>：表名、列名、主键、复合外键与 V6 完全一致，只把 {@code embedding} 列换成
 * PostgreSQL 兼容模式下可用的 {@code vector} 域（底层是 {@code VARCHAR}）。
 * 这样做的目的很明确 —— 验证的是<b>批处理机制与事务边界</b>（{@code addBatch}/{@code executeBatch}、
 * 批与批之间是否同事务、回滚是否覆盖已经下发的前一批），而<b>不是</b>向量类型本身。</p>
 * <p>向量类型、列宽约束、HNSW 索引与 pgvector 的写入语义由
 * {@link JdbcKnowledgeDocumentEmbeddingStorePostgresTests}（Testcontainers，无 Docker 时跳过）覆盖。</p>
 */
class JdbcKnowledgeDocumentEmbeddingStoreBatchWritesTests {

    /** 单批 2 行：让 5 条向量必然产生 3 个写批次。 */
    private static final int WRITE_BATCH_SIZE = 2;

    private static KnowledgePersistenceTestSupport.Fixture fixture;

    private JdbcClient jdbcClient;

    @BeforeAll
    static void migrateAndCreateShadowTable() {
        fixture = KnowledgePersistenceTestSupport.migrateWithWriteBatchSize("flowdesk_embedding_batch_it",
                WRITE_BATCH_SIZE);

        // V6 是 PostgreSQL 专用迁移（CREATE EXTENSION vector + vector(1024)），H2 无法执行；
        // 这里建一张结构等价、类型可用的影子表，仅用于验证批处理与事务语义。
        fixture.jdbcClient().sql("CREATE DOMAIN vector AS VARCHAR(40000)").update();
        fixture.jdbcClient().sql("""
                CREATE TABLE knowledge_document_chunk_embeddings (
                    document_id UUID NOT NULL,
                    chunk_index INTEGER NOT NULL,
                    chunk_sha256 CHAR(64) NOT NULL,
                    embedding vector NOT NULL,
                    provider VARCHAR(32) NOT NULL,
                    model VARCHAR(128) NOT NULL,
                    embedding_dimensions INTEGER NOT NULL,
                    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                    CONSTRAINT pk_knowledge_document_chunk_embeddings PRIMARY KEY (document_id, chunk_index),
                    CONSTRAINT fk_knowledge_document_chunk_embeddings_chunk
                        FOREIGN KEY (document_id, chunk_index)
                        REFERENCES knowledge_document_chunks (document_id, chunk_index) ON DELETE CASCADE
                )
                """).update();
    }

    @BeforeEach
    void clearState() {
        this.jdbcClient = fixture.jdbcClient();
        // 先关掉上一轮注入的失败，否则清理语句自己就会失败
        fixture.failOnStatement(0);
        fixture.failOnBatchExecution(0);
        fixture.failOnSql(null);
        this.jdbcClient.sql("DELETE FROM knowledge_document_chunk_embeddings").update();
        this.jdbcClient.sql("DELETE FROM knowledge_document_chunks").update();
        this.jdbcClient.sql("DELETE FROM knowledge_documents").update();
        fixture.resetStatements();
    }

    // ---------- 真正的批处理 ----------

    @Test
    void theWriteUsesJdbcBatchExecutionInsteadOfPerRowUpdates() {
        KnowledgeDocument document = indexingDocument("key-batch", 5);
        List<KnowledgeDocumentChunkEmbedding> embeddings = embeddings(document, 5);
        document.markIndexed(UPLOADED_AT.plusSeconds(6));

        fixture.resetStatements();
        var saved = fixture.embeddingStore().completeIndexing(document, 3L, embeddings);

        assertThat(saved.version()).isEqualTo(4L);
        assertThat(countVectors(document.id())).isEqualTo(5);

        // ① addBatch/executeBatch 被真实使用：5 行、每批 2 行 → 3 个批次
        assertThat(fixture.batchAdds()).as("每一行都通过 addBatch() 累积").isEqualTo(5);
        assertThat(fixture.batchExecutions()).as("5 行 / 每批 2 行 = 3 批").isEqualTo(3);
        assertThat(fixture.batchSizes()).hasSize(3).allSatisfy(size ->
                assertThat(size).isBetween(1, WRITE_BATCH_SIZE));
        assertThat(fixture.batchSizes()).containsExactlyInAnyOrder(2, 2, 1);

        // ② 每批下发的都是同一条单行 INSERT，没有拼接成超长多值 SQL
        assertThat(fixture.batchExecutionSql()).hasSize(3).allSatisfy(sql -> {
            assertThat(sql).contains("INSERT INTO knowledge_document_chunk_embeddings");
            assertThat(sql).as("不得把多行拼成一条 SQL").doesNotContain("),(").doesNotContain("VALUES (?, ?, ?, ?::vector),");
        });
        assertThat(fixture.batchExecutionSql()).containsOnly(fixture.batchExecutionSql().get(0));

        // ③ 没有任何逐条 executeUpdate 写向量
        assertThat(fixture.singleExecutionSql())
                .as("向量写入不得走 N 次 executeUpdate")
                .noneMatch(sql -> sql.contains("INSERT INTO knowledge_document_chunk_embeddings"));
    }

    @Test
    void writeBatchSizeIsBounded() {
        assertThatThrownBy(() -> new JdbcKnowledgeDocumentEmbeddingStore(fixture.jdbcClient(),
                new org.h2.jdbcx.JdbcDataSource(), fixture.transactions(), fixture.repository(), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("writeBatchSize");
        assertThatThrownBy(() -> new JdbcKnowledgeDocumentEmbeddingStore(fixture.jdbcClient(),
                new org.h2.jdbcx.JdbcDataSource(), fixture.transactions(), fixture.repository(),
                JdbcKnowledgeDocumentEmbeddingStore.MAX_WRITE_BATCH_SIZE + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("writeBatchSize");
    }

    // ---------- 事务边界 ----------

    @Test
    void aLaterBatchFailureRollsBackTheEarlierBatchTheDeleteAndTheIndexedTransition() {
        KnowledgeDocument document = indexingDocument("key-later-batch", 5);
        // 预先写入 5 条「旧向量」：用非零摘要标记，便于证明 DELETE 也被回滚
        insertOldVectors(document.id(), 5);
        assertThat(countVectors(document.id())).isEqualTo(5);

        List<KnowledgeDocumentChunkEmbedding> embeddings = embeddings(document, 5);
        document.markIndexed(UPLOADED_AT.plusSeconds(6));

        fixture.resetStatements();
        // 第 3 批失败：第 1、2 批（共 4 行）已经真实下发
        fixture.failOnBatchExecution(3);

        assertThatThrownBy(() -> fixture.embeddingStore().completeIndexing(document, 3L, embeddings))
                .isInstanceOf(DocumentIndexingException.class)
                .extracting(thrown -> ((DocumentIndexingException) thrown).failureCode())
                .isEqualTo(KnowledgeIndexFailureCode.VECTOR_STORAGE_FAILURE);

        // 前两批确实下发了，第三批失败
        assertThat(fixture.batchExecutions()).as("前两批已执行").isEqualTo(2);
        assertThat(fixture.failedBatchExecutions()).as("第三批被注入失败").isEqualTo(1);
        // DELETE 也真实下发过
        assertThat(fixture.singleExecutionSql())
                .as("删除旧向量发生在失败之前")
                .anyMatch(sql -> sql.contains("DELETE FROM knowledge_document_chunk_embeddings"));

        // 全部回滚：新向量没有留下，旧向量（摘要 aaaa…）原样还在
        assertThat(countVectors(document.id())).isEqualTo(5);
        assertThat(digestsOf(document.id())).containsOnly("a".repeat(64));
        assertThat(statusOf(document)).as("文档不得变成 INDEXED").isEqualTo("INDEXING");
        assertThat(versionOf(document)).isEqualTo(3L);
    }

    @Test
    void aFailureAfterEveryBatchRollsBackEveryBatchAndTheDelete() {
        KnowledgeDocument document = indexingDocument("key-after-batches", 5);
        insertOldVectors(document.id(), 5);

        List<KnowledgeDocumentChunkEmbedding> embeddings = embeddings(document, 5);
        document.markIndexed(UPLOADED_AT.plusSeconds(6));

        fixture.resetStatements();
        // 全部批次都成功，让随后的文档状态 CAS 失败：证明「所有批次 + 删除旧向量 + 状态推进」同事务
        fixture.failOnSql("UPDATE knowledge_documents SET");

        assertThatThrownBy(() -> fixture.embeddingStore().completeIndexing(document, 3L, embeddings))
                .isInstanceOf(DocumentIndexingException.class)
                .extracting(thrown -> ((DocumentIndexingException) thrown).failureCode())
                .isEqualTo(KnowledgeIndexFailureCode.VECTOR_STORAGE_FAILURE);

        assertThat(fixture.batchExecutions()).as("三个批次全部执行过").isEqualTo(3);
        assertThat(fixture.failedBatchExecutions()).isZero();

        assertThat(countVectors(document.id())).as("已提交到事务里的批次同样必须回滚").isEqualTo(5);
        assertThat(digestsOf(document.id())).containsOnly("a".repeat(64));
        assertThat(statusOf(document)).isEqualTo("INDEXING");
        assertThat(versionOf(document)).isEqualTo(3L);
    }

    @Test
    void aDeleteFailureIsReportedAsVectorStorageFailureAndKeepsTheOldVectors() {
        KnowledgeDocument document = indexingDocument("key-delete", 3);
        insertOldVectors(document.id(), 3);

        List<KnowledgeDocumentChunkEmbedding> embeddings = embeddings(document, 3);
        document.markIndexed(UPLOADED_AT.plusSeconds(6));

        fixture.resetStatements();
        fixture.failOnSql("DELETE FROM knowledge_document_chunk_embeddings");

        assertThatThrownBy(() -> fixture.embeddingStore().completeIndexing(document, 3L, embeddings))
                .isInstanceOf(DocumentIndexingException.class)
                .extracting(thrown -> ((DocumentIndexingException) thrown).failureCode())
                .isEqualTo(KnowledgeIndexFailureCode.VECTOR_STORAGE_FAILURE);

        assertThat(fixture.batchExecutions()).as("删除失败后不再写入向量").isZero();
        assertThat(countVectors(document.id())).isEqualTo(3);
        assertThat(digestsOf(document.id())).containsOnly("a".repeat(64));
        assertThat(statusOf(document)).isEqualTo("INDEXING");
    }

    @Test
    void theStorageFailureKeepsTheDriverFailureOnlyInTheCause() {
        KnowledgeDocument document = indexingDocument("key-cause", 2);
        List<KnowledgeDocumentChunkEmbedding> embeddings = embeddings(document, 2);
        document.markIndexed(UPLOADED_AT.plusSeconds(6));

        fixture.resetStatements();
        fixture.failOnBatchExecution(1);

        DocumentIndexingException thrown = (DocumentIndexingException) org.assertj.core.api.Assertions
                .catchThrowable(() -> fixture.embeddingStore().completeIndexing(document, 3L, embeddings));

        assertThat(thrown.failureCode()).isEqualTo(KnowledgeIndexFailureCode.VECTOR_STORAGE_FAILURE);
        assertThat(thrown.getMessage())
                .as("对外消息里不得出现驱动文本或 SQL")
                .doesNotContain("INSERT").doesNotContain("SQLException").doesNotContain("08006");
        assertThat(thrown.getCause())
                .as("原始数据库异常只作为 cause 留在服务端")
                .isNotNull();
    }

    // ---------- 摘要与版本语义 ----------

    @Test
    void aDigestMismatchIsReportedAsChunkDataInvalidAndWritesNothing() {
        KnowledgeDocument document = indexingDocument("key-digest", 3);
        // 先按「当时的切片」生成向量，再把切片内容换掉：写入时摘要必须对不上
        List<KnowledgeDocumentChunkEmbedding> embeddings = embeddings(document, 3);
        this.jdbcClient.sql("UPDATE knowledge_document_chunks SET sha256 = ? WHERE document_id = ? AND chunk_index = 2")
                .param(1, "f".repeat(64))
                .param(2, document.id().value())
                .update();
        document.markIndexed(UPLOADED_AT.plusSeconds(6));

        fixture.resetStatements();
        assertThatThrownBy(() -> fixture.embeddingStore().completeIndexing(document, 3L, embeddings))
                .isInstanceOf(DocumentIndexingException.class)
                .extracting(thrown -> ((DocumentIndexingException) thrown).failureCode())
                .isEqualTo(KnowledgeIndexFailureCode.CHUNK_DATA_INVALID);

        assertThat(fixture.batchExecutions()).as("摘要不一致时一条向量都不写").isZero();
        assertThat(countVectors(document.id())).isZero();
        assertThat(statusOf(document)).isEqualTo("INDEXING");
        assertThat(versionOf(document)).isEqualTo(3L);
    }

    @Test
    void aVersionConflictKeepsItsOwnSemanticsAndWritesNothing() {
        KnowledgeDocument document = indexingDocument("key-version", 2);
        List<KnowledgeDocumentChunkEmbedding> embeddings = embeddings(document, 2);
        document.markIndexed(UPLOADED_AT.plusSeconds(6));

        fixture.resetStatements();
        assertThatThrownBy(() -> fixture.embeddingStore().completeIndexing(document, 99L, embeddings))
                .isInstanceOf(KnowledgeApplicationException.class)
                .extracting(thrown -> ((KnowledgeApplicationException) thrown).errorCode())
                .isEqualTo(KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_VERSION_CONFLICT);

        assertThat(fixture.batchExecutions()).isZero();
        assertThat(countVectors(document.id())).isZero();
        assertThat(statusOf(document)).isEqualTo("INDEXING");
        assertThat(versionOf(document)).isEqualTo(3L);
    }

    // ---------- 辅助 ----------

    private static final EmbeddingDescriptor DESCRIPTOR =
            EmbeddingDescriptor.of("dashscope", "text-embedding-v4");

    /** 造一个数据库里处于 INDEXING（版本 3）的文档，并写入 {@code chunkCount} 条切片。 */
    private static KnowledgeDocument indexingDocument(String contentKey, int chunkCount) {
        KnowledgeDocument document = insertUploaded(fixture.repository(), contentKey);
        long claimed = claim(fixture.repository(), document, 0L);
        document.markParsed(UPLOADED_AT.plusSeconds(2));
        fixture.chunkStore().completeParsing(document, claimed, chunksOf(document.id(), chunkCount));

        KnowledgeDocument parsed = fixture.repository().findById(document.id()).orElseThrow().document();
        parsed.markIndexing(DESCRIPTOR, UPLOADED_AT.plusSeconds(4));
        fixture.repository().update(parsed, 2L);
        return fixture.repository().findById(document.id()).orElseThrow().document();
    }

    /** 按库中切片构造向量列表（摘要与库中一致，因此能通过二次证明）。 */
    private static List<KnowledgeDocumentChunkEmbedding> embeddings(KnowledgeDocument document, int count) {
        List<KnowledgeDocumentChunkEmbedding> embeddings = new ArrayList<>(count);
        for (KnowledgeDocumentChunk chunk : fixture.chunkStore().findChunks(document.id(), 0, count)) {
            embeddings.add(new KnowledgeDocumentChunkEmbedding(document.id(), chunk.chunkIndex(),
                    chunk.sha256(), DESCRIPTOR, vector(1.0f)));
        }
        return embeddings;
    }

    /** 直接插入 {@code count} 条「旧向量」，摘要固定为全 a，用于识别回滚是否保留旧数据。 */
    private void insertOldVectors(KnowledgeDocumentId documentId, int count) {
        for (KnowledgeDocumentChunk chunk : fixture.chunkStore().findChunks(documentId, 0, count)) {
            this.jdbcClient.sql("INSERT INTO knowledge_document_chunk_embeddings "
                            + "(document_id, chunk_index, chunk_sha256, embedding, provider, model, "
                            + "embedding_dimensions, created_at) VALUES (?, ?, ?, ?::vector, 'dashscope', "
                            + "'text-embedding-v4', 1024, ?)")
                    .param(1, documentId.value())
                    .param(2, chunk.chunkIndex())
                    .param(3, "a".repeat(64))
                    .param(4, "[0.25,0.25]")
                    .param(5, java.time.OffsetDateTime.ofInstant(Instant.parse("2026-05-01T10:00:05Z"),
                            java.time.ZoneOffset.UTC))
                    .update();
        }
    }

    private long countVectors(KnowledgeDocumentId documentId) {
        Long count = this.jdbcClient.sql(
                "SELECT COUNT(*) FROM knowledge_document_chunk_embeddings WHERE document_id = ?")
                .param(1, documentId.value()).query(Long.class).single();
        return count == null ? -1L : count;
    }

    private List<String> digestsOf(KnowledgeDocumentId documentId) {
        return this.jdbcClient.sql("SELECT chunk_sha256 FROM knowledge_document_chunk_embeddings "
                        + "WHERE document_id = ? ORDER BY chunk_index")
                .param(1, documentId.value()).query(String.class).list().stream().map(String::strip).toList();
    }

    private String statusOf(KnowledgeDocument document) {
        return this.jdbcClient.sql("SELECT status FROM knowledge_documents WHERE id = ?")
                .param(1, document.id().value()).query(String.class).single();
    }

    private long versionOf(KnowledgeDocument document) {
        Long version = this.jdbcClient.sql("SELECT version FROM knowledge_documents WHERE id = ?")
                .param(1, document.id().value()).query(Long.class).single();
        return version == null ? -1L : version;
    }

    private static float[] vector(float value) {
        float[] vector = new float[EmbeddingDescriptor.REQUIRED_DIMENSIONS];
        Arrays.fill(vector, value);
        return vector;
    }
}
