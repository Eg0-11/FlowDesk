package com.flowdesk.infrastructure.knowledge.persistence.jdbc;

import static com.flowdesk.infrastructure.knowledge.persistence.jdbc.KnowledgePersistenceTestSupport.UPLOADED_AT;
import static com.flowdesk.infrastructure.knowledge.persistence.jdbc.KnowledgePersistenceTestSupport.assertApplicationError;
import static com.flowdesk.infrastructure.knowledge.persistence.jdbc.KnowledgePersistenceTestSupport.claim;
import static com.flowdesk.infrastructure.knowledge.persistence.jdbc.KnowledgePersistenceTestSupport.insertUploaded;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.domain.knowledge.EmbeddingDescriptor;
import com.flowdesk.domain.knowledge.KnowledgeDocument;
import com.flowdesk.domain.knowledge.KnowledgeDocumentChunk;
import com.flowdesk.domain.knowledge.KnowledgeDocumentChunkEmbedding;
import com.flowdesk.domain.knowledge.KnowledgeDocumentId;
import com.flowdesk.domain.knowledge.KnowledgeDocumentStatus;
import com.flowdesk.domain.knowledge.Sha256Digest;
import com.flowdesk.infrastructure.knowledge.KnowledgeTestContent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 「完成索引」的入参防线（FD-0010，H2 可执行部分）。
 *
 * <p>真正的向量写入需要 pgvector，因此这里只验证<b>开启事务与执行 SQL 之前</b>的校验：
 * 非法输入必须以稳定的内部错误拒绝，并且<b>一条 SQL 都不下发</b>。</p>
 */
class JdbcKnowledgeDocumentEmbeddingStorePreconditionTest {

    private static KnowledgePersistenceTestSupport.Fixture fixture;

    private JdbcClient jdbcClient;

    @BeforeAll
    static void migrate() {
        fixture = KnowledgePersistenceTestSupport.migrate("flowdesk_embedding_precondition_it");
    }

    @BeforeEach
    void clearTables() {
        this.jdbcClient = fixture.jdbcClient();
        this.jdbcClient.sql("DELETE FROM knowledge_document_chunks").update();
        this.jdbcClient.sql("DELETE FROM knowledge_documents").update();
    }

    @Test
    void rejectsAnEmptyVectorListWithoutTouchingTheDatabase() {
        KnowledgeDocument document = indexingDocument("key-empty", 2);

        assertGuarded("完成索引不接受空向量列表", document, List.of());
    }

    @Test
    void rejectsNullElements() {
        KnowledgeDocument document = indexingDocument("key-null", 2);
        List<KnowledgeDocumentChunkEmbedding> embeddings = embeddings(document, 2);
        embeddings.set(1, null);

        assertGuarded("向量列表中不能包含 null", document, embeddings);
    }

    @Test
    void rejectsVectorsBelongingToAnotherDocument() {
        KnowledgeDocument document = indexingDocument("key-owner", 2);
        List<KnowledgeDocumentChunkEmbedding> embeddings = embeddings(document, 2);
        embeddings.set(1, new KnowledgeDocumentChunkEmbedding(
                KnowledgePersistenceTestSupport.randomId(), 1,
                embeddings.get(1).chunkSha256(), descriptor(), vector(2.0f)));

        assertGuarded("向量归属的文档与目标文档不一致", document, embeddings);
    }

    @Test
    void rejectsGappedOrOutOfOrderIndexes() {
        KnowledgeDocument document = indexingDocument("key-order", 3);
        List<KnowledgeDocumentChunkEmbedding> embeddings = embeddings(document, 3);

        List<KnowledgeDocumentChunkEmbedding> gapped = new ArrayList<>(embeddings);
        gapped.remove(1);
        assertGuarded("向量序号必须从 0 开始严格连续递增", document, gapped);

        List<KnowledgeDocumentChunkEmbedding> reordered = new ArrayList<>(embeddings);
        java.util.Collections.swap(reordered, 0, 2);
        assertGuarded("向量序号必须从 0 开始严格连续递增", document, reordered);
    }

    @Test
    void rejectsAVectorWhoseDescriptorDiffersFromTheDocument() {
        KnowledgeDocument document = indexingDocument("key-descriptor", 2);
        List<KnowledgeDocumentChunkEmbedding> embeddings = embeddings(document, 2);
        embeddings.set(0, new KnowledgeDocumentChunkEmbedding(document.id(), 0,
                embeddings.get(0).chunkSha256(), EmbeddingDescriptor.of("other-provider", "other-model"),
                vector(1.0f)));

        assertGuarded("向量描述符与文档记录的模型不一致", document, embeddings);
    }

    @Test
    void rejectsADocumentThatIsNotMarkedIndexed() {
        // 聚合仍是 INDEXING：完成索引只接受 INDEXED 的聚合
        KnowledgeDocument document = indexingAggregate("key-status", 2);

        assertApplicationError(() -> fixture.embeddingStore().completeIndexing(document, versionOf(document.id()),
                embeddings(document, 2)), KnowledgeApplicationErrorCode.KNOWLEDGE_INTERNAL_ERROR);
    }

    // ---------- V5 迁移与约束（H2 可执行部分） ----------

    @Test
    void v5AddsTheIndexingColumns() {
        List<String> columns = this.jdbcClient
                .sql("SELECT column_name FROM information_schema.columns WHERE table_name = 'knowledge_documents'")
                .query(String.class)
                .list();

        assertThat(columns).contains("index_started_at", "indexed_at", "index_failed_at",
                "index_failure_code", "embedding_provider", "embedding_model", "embedding_dimensions");
    }

    @Test
    void theVectorTableIsNotCreatedOnH2() {
        Long tables = this.jdbcClient.sql("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_name = 'knowledge_document_chunk_embeddings'").query(Long.class).single();

        assertThat(tables).as("V6 是 PostgreSQL 专用迁移，H2 上不得建出向量表").isZero();
    }

    @Test
    void theDatabaseRejectsInconsistentIndexingRows() {
        KnowledgeDocument document = insertUploaded(fixture.repository(), "key-checks");

        // 状态是 INDEXING 但缺索引字段
        assertRejected("UPDATE knowledge_documents SET status = 'INDEXING' WHERE id = ?",
                document.id());
        // 维度不是 1024
        assertRejected("UPDATE knowledge_documents SET embedding_dimensions = 1023 WHERE id = ?",
                document.id());
        // 非索引状态却带索引字段
        assertRejected("UPDATE knowledge_documents SET embedding_provider = 'dashscope' WHERE id = ?",
                document.id());
        // 未知状态
        assertRejected("UPDATE knowledge_documents SET status = 'DONE' WHERE id = ?", document.id());
    }

    @Test
    void theDatabaseRejectsIndexStatesWithoutTheirFields() {
        KnowledgeDocument document = insertUploaded(fixture.repository(), "key-checks-2");

        // 已解析但带索引字段
        assertRejected("UPDATE knowledge_documents SET status = 'PARSED', parsed_at = ?, "
                + "index_started_at = ?, embedding_provider = 'dashscope', embedding_model = 'text-embedding-v4', "
                + "embedding_dimensions = 1024 WHERE id = ?", document.id());
        // 索引完成却缺 indexed_at
        assertRejected("UPDATE knowledge_documents SET status = 'INDEXED', parsed_at = ?, index_started_at = ?, "
                + "embedding_provider = 'dashscope', embedding_model = 'text-embedding-v4', "
                + "embedding_dimensions = 1024 WHERE id = ?", document.id());
    }

    // ---------- 辅助 ----------

    private void assertGuarded(String expectedMessage, KnowledgeDocument document,
            List<KnowledgeDocumentChunkEmbedding> embeddings) {

        fixture.resetStatements();
        KnowledgeApplicationErrorCode code = KnowledgeApplicationErrorCode.KNOWLEDGE_INTERNAL_ERROR;

        assertThatThrownBy(() -> fixture.embeddingStore().completeIndexing(document, 1L, embeddings))
                .isInstanceOf(com.flowdesk.application.knowledge.KnowledgeApplicationException.class)
                .hasMessage(expectedMessage)
                .extracting(thrown -> ((com.flowdesk.application.knowledge.KnowledgeApplicationException) thrown)
                        .errorCode())
                .isEqualTo(code);
        assertThat(fixture.statementsExecuted())
                .as("前置校验必须发生在开启事务与执行 SQL 之前")
                .isZero();
    }

    private void assertRejected(String sql, KnowledgeDocumentId documentId) {
        assertThatThrownBy(() -> {
            if (sql.contains("parsed_at = ?")) {
                this.jdbcClient.sql(sql)
                        .param(1, java.time.OffsetDateTime.ofInstant(UPLOADED_AT.plusSeconds(2),
                                java.time.ZoneOffset.UTC))
                        .param(2, java.time.OffsetDateTime.ofInstant(UPLOADED_AT.plusSeconds(3),
                                java.time.ZoneOffset.UTC))
                        .param(3, documentId.value())
                        .update();
            }
            else {
                this.jdbcClient.sql(sql).param(1, documentId.value()).update();
            }
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * 造一个「数据库里仍是 INDEXING、聚合已推进到 INDEXED」的场景，与用例服务的调用形态一致。
     *
     * @param contentKey 内容键
     * @param chunkCount 切片数量
     * @return 已标记完成的聚合（INDEXED）
     */
    private KnowledgeDocument indexingDocument(String contentKey, int chunkCount) {
        KnowledgeDocument indexing = indexingAggregate(contentKey, chunkCount);
        // 与用例服务一致：先在内存里推进到 INDEXED，再调用完成端口（数据库仍是 INDEXING）
        indexing.markIndexed(UPLOADED_AT.plusSeconds(5));
        return indexing;
    }

    /**
     * 造一个数据库里处于 {@code INDEXING}（版本 2）的文档，并写入对应数量的切片。
     *
     * @param contentKey 内容键
     * @param chunkCount 切片数量
     * @return 聚合（INDEXING）
     */
    private KnowledgeDocument indexingAggregate(String contentKey, int chunkCount) {
        KnowledgeDocument document = insertUploaded(fixture.repository(), contentKey);
        long claimed = claim(fixture.repository(), document, 0L);
        document.markParsed(UPLOADED_AT.plusSeconds(2));
        fixture.chunkStore().completeParsing(document, claimed, chunks(document.id(), chunkCount));

        KnowledgeDocument parsed = fixture.repository().findById(document.id()).orElseThrow().document();
        parsed.markIndexing(descriptor(), UPLOADED_AT.plusSeconds(4));
        fixture.repository().update(parsed, versionOf(document.id()));
        return fixture.repository().findById(document.id()).orElseThrow().document();
    }

    /**
     * 用切片存储写好的切片内容生成对应向量列表。
     *
     * @param document 文档
     * @param count    切片数量
     * @return 向量列表
     */
    private List<KnowledgeDocumentChunkEmbedding> embeddings(KnowledgeDocument document, int count) {
        List<KnowledgeDocumentChunkEmbedding> embeddings = new ArrayList<>(count);
        for (KnowledgeDocumentChunk chunk : savedChunks(document.id(), count)) {
            embeddings.add(new KnowledgeDocumentChunkEmbedding(document.id(), chunk.chunkIndex(),
                    chunk.sha256(), descriptor(), vector(1.0f)));
        }
        return embeddings;
    }

    private List<KnowledgeDocumentChunk> savedChunks(KnowledgeDocumentId documentId, int count) {
        return fixture.chunkStore().findChunks(documentId, 0, Math.max(count, 1));
    }

    private static List<KnowledgeDocumentChunk> chunks(KnowledgeDocumentId documentId, int count) {
        List<KnowledgeDocumentChunk> chunks = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            String content = "chunk-" + index;
            chunks.add(new KnowledgeDocumentChunk(documentId, index, content,
                    content.codePointCount(0, content.length()),
                    Sha256Digest.of(KnowledgeTestContent.sha256Hex(content.getBytes(StandardCharsets.UTF_8))),
                    Instant.parse("2026-05-01T10:00:04Z")));
        }
        return List.copyOf(chunks);
    }

    private static EmbeddingDescriptor descriptor() {
        return EmbeddingDescriptor.of("dashscope", "text-embedding-v4");
    }

    private static float[] vector(float value) {
        float[] vector = new float[EmbeddingDescriptor.REQUIRED_DIMENSIONS];
        Arrays.fill(vector, value);
        return vector;
    }

    private long versionOf(KnowledgeDocumentId documentId) {
        Long version = this.jdbcClient.sql("SELECT version FROM knowledge_documents WHERE id = ?")
                .param(1, documentId.value()).query(Long.class).single();
        return version == null ? -1L : version;
    }
}
