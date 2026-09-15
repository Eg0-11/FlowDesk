package com.flowdesk.infrastructure.knowledge.persistence.jdbc;

import static com.flowdesk.infrastructure.knowledge.persistence.jdbc.KnowledgePersistenceTestSupport.UPLOADED_AT;
import static com.flowdesk.infrastructure.knowledge.persistence.jdbc.KnowledgePersistenceTestSupport.claim;
import static com.flowdesk.infrastructure.knowledge.persistence.jdbc.KnowledgePersistenceTestSupport.chunksOf;
import static com.flowdesk.infrastructure.knowledge.persistence.jdbc.KnowledgePersistenceTestSupport.insertUploaded;
import static org.assertj.core.api.Assertions.assertThat;

import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.application.knowledge.command.IndexKnowledgeDocumentCommand;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentEmbeddingStore;
import com.flowdesk.application.knowledge.port.out.KnowledgeEmbeddingPort;
import com.flowdesk.application.knowledge.port.out.VersionedKnowledgeDocument;
import com.flowdesk.application.knowledge.service.KnowledgeDocumentIndexingService;
import com.flowdesk.application.knowledge.view.IndexedDocumentView;
import com.flowdesk.domain.knowledge.EmbeddingDescriptor;
import com.flowdesk.domain.knowledge.KnowledgeDocument;
import com.flowdesk.domain.knowledge.KnowledgeDocumentChunkEmbedding;
import com.flowdesk.domain.knowledge.KnowledgeDocumentId;
import com.flowdesk.infrastructure.knowledge.support.SystemKnowledgeTimeProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code PARSED → INDEXING} 的并发领取（FD-0010-R1）。
 *
 * <p>用<b>真实线程 + 真实 H2（PostgreSQL 兼容模式）+ 真实仓储 CAS</b>验证：
 * 多个请求同时拿着同一份版本快照时，只有一个能进入 {@code INDEXING}，
 * 其余必须拿到 {@link KnowledgeApplicationErrorCode#KNOWLEDGE_DOCUMENT_VERSION_CONFLICT}，
 * 且数据库版本<b>只增加一次</b>。</p>
 *
 * <p>领取之后的向量生成与写入用替身端口：本测试只关心「谁拿到了领取权」，
 * 真实写入路径由 {@link JdbcKnowledgeDocumentEmbeddingStoreBatchWritesTests}（H2 影子表）
 * 与 {@link JdbcKnowledgeDocumentEmbeddingStorePostgresTests}（pgvector）覆盖。</p>
 */
class KnowledgeDocumentIndexingClaimConcurrencyTest {

    private static final EmbeddingDescriptor DESCRIPTOR =
            EmbeddingDescriptor.of("dashscope", "text-embedding-v4");

    private static final Instant INDEXED_AT = UPLOADED_AT.plusSeconds(30);

    private static KnowledgePersistenceTestSupport.Fixture fixture;

    private static JdbcClient jdbcClient;

    private KnowledgeDocumentIndexingService service;

    @BeforeAll
    static void migrate() {
        fixture = KnowledgePersistenceTestSupport.migrate("flowdesk_indexing_claim_concurrency_it");
        jdbcClient = fixture.jdbcClient();
    }

    @BeforeEach
    void createService() {
        jdbcClient.sql("DELETE FROM knowledge_document_chunks").update();
        jdbcClient.sql("DELETE FROM knowledge_documents").update();
        fixture.resetStatements();

        this.service = new KnowledgeDocumentIndexingService(fixture.repository(), fixture.chunkStore(),
                new StubEmbeddingPort(), new StubEmbeddingStore(),
                new SystemKnowledgeTimeProvider(Clock.fixed(INDEXED_AT, ZoneOffset.UTC)),
                true, DESCRIPTOR, 10);
    }

    @Test
    void concurrentIndexingClaimsAllowExactlyOneWinner() throws Exception {
        KnowledgeDocumentId documentId = parsedDocument("claim-key");
        int attempts = 8;

        // 所有线程先各自读取同一份快照（版本 2 / PARSED），再同时发起领取：
        // 这样每个线程手里的 (version, status) 都相同，落败原因只能是 CAS 失败。
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CyclicBarrier snapshotsReady = new CyclicBarrier(attempts);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int index = 0; index < attempts; index++) {
                tasks.add(() -> {
                    // 快照：每个线程独立读出「版本 2、PARSED」
                    VersionedKnowledgeDocument snapshot = fixture.repository().findById(documentId)
                            .orElseThrow();
                    snapshotsReady.await();
                    try {
                        IndexedDocumentView view = service.index(new IndexKnowledgeDocumentCommand(
                                snapshot.document().id(), snapshot.version()));
                        return "claimed:" + view.status();
                    }
                    catch (KnowledgeApplicationException ex) {
                        return "rejected:" + ex.errorCode().name();
                    }
                });
            }

            List<Future<String>> results = pool.invokeAll(tasks);
            List<String> outcomes = new ArrayList<>();
            for (Future<String> result : results) {
                outcomes.add(result.get());
            }

            assertThat(outcomes).filteredOn(outcome -> outcome.startsWith("claimed"))
                    .as("并发领取只能有一个成功")
                    .hasSize(1);
            assertThat(outcomes).filteredOn(outcome -> outcome.startsWith("rejected"))
                    .as("其余请求必须得到版本冲突")
                    .hasSize(attempts - 1)
                    .allSatisfy(outcome -> assertThat(outcome)
                            .isEqualTo("rejected:" + KnowledgeApplicationErrorCode
                                    .KNOWLEDGE_DOCUMENT_VERSION_CONFLICT.name()));
        }
        finally {
            pool.shutdownNow();
        }

        assertThat(statusOf(documentId)).as("只有一个请求把文档推进到 INDEXING").isEqualTo("INDEXING");
        assertThat(versionOf(documentId)).as("数据库版本只增加一次（2 → 3）").isEqualTo(3L);
    }

    @Test
    void oneWinnerAndVersionBumpedOnceEvenWhenTheSameRequestIsRepeated() throws Exception {
        // 同一个快照被连续提交两次：第一次领取成功，第二次必须被拒绝且不产生额外写入
        KnowledgeDocumentId documentId = parsedDocument("claim-key-2");
        long version = versionOf(documentId);

        service.index(new IndexKnowledgeDocumentCommand(documentId, version));

        assertThat(statusOf(documentId)).isEqualTo("INDEXING");
        assertThat(versionOf(documentId)).isEqualTo(3L);

        // 第二次用同一版本重放：CAS 失败 → 版本冲突，且版本不再增长
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> service.index(new IndexKnowledgeDocumentCommand(documentId, version))))
                .isInstanceOf(KnowledgeApplicationException.class)
                .extracting(thrown -> ((KnowledgeApplicationException) thrown).errorCode())
                .isEqualTo(KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_VERSION_CONFLICT);

        assertThat(versionOf(documentId)).isEqualTo(3L);
    }

    // ---------- 辅助 ----------

    /** 上传 → 领取解析 → 完成解析：返回处于 PARSED（版本 2）的文档标识。 */
    private static KnowledgeDocumentId parsedDocument(String contentKey) {
        KnowledgeDocument document = insertUploaded(fixture.repository(), contentKey);
        long claimed = claim(fixture.repository(), document, 0L);
        document.markParsed(UPLOADED_AT.plusSeconds(2));
        fixture.chunkStore().completeParsing(document, claimed, chunksOf(document.id(), 2));
        return document.id();
    }

    private static String statusOf(KnowledgeDocumentId documentId) {
        return jdbcClient.sql("SELECT status FROM knowledge_documents WHERE id = ?")
                .param(1, documentId.value()).query(String.class).single();
    }

    private static long versionOf(KnowledgeDocumentId documentId) {
        Long version = jdbcClient.sql("SELECT version FROM knowledge_documents WHERE id = ?")
                .param(1, documentId.value()).query(Long.class).single();
        return version == null ? -1L : version;
    }

    /** 向量生成替身：始终返回合法的 1024 维向量。 */
    private static final class StubEmbeddingPort implements KnowledgeEmbeddingPort {

        @Override
        public List<float[]> embedAll(List<String> texts, EmbeddingDescriptor descriptor) {
            List<float[]> vectors = new ArrayList<>(texts.size());
            for (int index = 0; index < texts.size(); index++) {
                float[] vector = new float[descriptor.dimensions()];
                Arrays.fill(vector, 0.5f);
                vectors.add(vector);
            }
            return List.copyOf(vectors);
        }
    }

    /** 向量写入替身：不落库，只回报「版本 +1」。 */
    private static final class StubEmbeddingStore implements KnowledgeDocumentEmbeddingStore {

        @Override
        public VersionedKnowledgeDocument completeIndexing(KnowledgeDocument indexedDocument,
                long expectedVersion, List<KnowledgeDocumentChunkEmbedding> embeddings) {

            return new VersionedKnowledgeDocument(indexedDocument, expectedVersion + 1L);
        }
    }
}
