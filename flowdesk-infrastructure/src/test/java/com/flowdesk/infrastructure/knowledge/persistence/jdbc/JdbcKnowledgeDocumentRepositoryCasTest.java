package com.flowdesk.infrastructure.knowledge.persistence.jdbc;

import static com.flowdesk.infrastructure.knowledge.persistence.jdbc.KnowledgePersistenceTestSupport.UPLOADED_AT;
import static com.flowdesk.infrastructure.knowledge.persistence.jdbc.KnowledgePersistenceTestSupport.assertApplicationError;
import static com.flowdesk.infrastructure.knowledge.persistence.jdbc.KnowledgePersistenceTestSupport.insertUploaded;
import static com.flowdesk.infrastructure.knowledge.persistence.jdbc.KnowledgePersistenceTestSupport.randomId;
import static org.assertj.core.api.Assertions.assertThat;

import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.port.out.VersionedKnowledgeDocument;
import com.flowdesk.domain.knowledge.KnowledgeDocument;
import com.flowdesk.domain.knowledge.KnowledgeDocumentStatus;
import com.flowdesk.domain.knowledge.KnowledgeParseFailureCode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 知识文档 CAS 更新测试（FD-0009）。
 *
 * <p>「领取解析」是整个解析链路的并发闸门：它必须保证同一文档<b>只有一个</b>请求能从
 * {@code UPLOADED}/{@code PARSE_FAILED} 进入 {@code PARSING}。这里用真实数据库
 * （行锁 + 带版本条件的 UPDATE）验证这一点，包括真正并发的场景。</p>
 */
class JdbcKnowledgeDocumentRepositoryCasTest {

    private static KnowledgePersistenceTestSupport.Fixture fixture;

    private JdbcClient jdbcClient;

    @BeforeAll
    static void migrate() {
        fixture = KnowledgePersistenceTestSupport.migrate("flowdesk_document_cas_it");
    }

    @BeforeEach
    void clearDocuments() {
        this.jdbcClient = fixture.jdbcClient();
        this.jdbcClient.sql("DELETE FROM knowledge_document_chunks").update();
        this.jdbcClient.sql("DELETE FROM knowledge_documents").update();
    }

    // ---------- 基本 CAS ----------

    @Test
    void updateBumpsTheVersionAndPersistsTheNewStatus() {
        KnowledgeDocument document = insertUploaded(fixture.repository(), "cas-key-a");

        document.markParsing(UPLOADED_AT.plusSeconds(1));
        VersionedKnowledgeDocument updated = fixture.repository().update(document, 0L);

        assertThat(updated.version()).isEqualTo(1L);
        assertThat(updated.document().status()).isEqualTo(KnowledgeDocumentStatus.PARSING);
        assertThat(statusOf(document)).isEqualTo("PARSING");
        assertThat(versionOf(document)).isEqualTo(1L);
    }

    @Test
    void staleVersionIsRejectedWithoutAnyWrite() {
        KnowledgeDocument document = insertUploaded(fixture.repository(), "cas-key-b");
        document.markParsing(UPLOADED_AT.plusSeconds(1));

        assertApplicationError(() -> fixture.repository().update(document, 7L),
                KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_VERSION_CONFLICT);

        assertThat(versionOf(document)).isZero();
        assertThat(statusOf(document)).isEqualTo("UPLOADED");
    }

    @Test
    void theSameExpectedVersionCanOnlyBeUsedOnce() {
        KnowledgeDocument document = insertUploaded(fixture.repository(), "cas-key-c");
        document.markParsing(UPLOADED_AT.plusSeconds(1));
        fixture.repository().update(document, 0L);

        KnowledgeDocument second = fixture.repository().findById(document.id()).orElseThrow().document();
        second.markParsed(UPLOADED_AT.plusSeconds(2));
        // 仍然拿着旧版本：必须被拒绝
        assertApplicationError(() -> fixture.repository().update(second, 0L),
                KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_VERSION_CONFLICT);

        assertThat(statusOf(document)).isEqualTo("PARSING");
        assertThat(versionOf(document)).isEqualTo(1L);
    }

    @Test
    void updatingAnUnknownDocumentIsReportedAsNotFound() {
        KnowledgeDocument document = insertUploaded(fixture.repository(), randomId(), "cas-key-d");
        document.markParsing(UPLOADED_AT.plusSeconds(1));
        this.jdbcClient.sql("DELETE FROM knowledge_documents WHERE id = ?")
                .param(1, document.id().value()).update();

        assertApplicationError(() -> fixture.repository().update(document, 0L),
                KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_NOT_FOUND);
    }

    // ---------- 并发领取 ----------

    @Test
    void concurrentClaimsAllowExactlyOneWinner() throws Exception {
        KnowledgeDocument document = insertUploaded(fixture.repository(), "cas-key-e");
        int attempts = 8;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int index = 0; index < attempts; index++) {
                tasks.add(() -> {
                    KnowledgeDocument candidate = fixture.repository().findById(document.id())
                            .orElseThrow().document();
                    candidate.markParsing(UPLOADED_AT.plusSeconds(1));
                    try {
                        fixture.repository().update(candidate, 0L);
                        return "claimed";
                    }
                    catch (RuntimeException ex) {
                        return "rejected";
                    }
                });
            }

            List<Future<String>> results = pool.invokeAll(tasks);
            long claimed = 0L;
            for (Future<String> result : results) {
                if ("claimed".equals(result.get())) {
                    claimed++;
                }
            }

            assertThat(claimed).as("并发领取必须只有一个成功").isEqualTo(1L);
            assertThat(versionOf(document)).as("只有一次 CAS 生效").isEqualTo(1L);
            assertThat(statusOf(document)).isEqualTo("PARSING");
        }
        finally {
            pool.shutdownNow();
        }
    }

    // ---------- 解析字段 round-trip ----------

    @Test
    void parsedSnapshotRoundTripsThroughTheDatabase() {
        KnowledgeDocument document = insertUploaded(fixture.repository(), "cas-key-f");
        claim(document);
        KnowledgeDocument claimed = fixture.repository().findById(document.id()).orElseThrow().document();
        claimed.markParsed(UPLOADED_AT.plusSeconds(5));
        fixture.repository().update(claimed, 1L);

        KnowledgeDocument reloaded = fixture.repository().findById(document.id()).orElseThrow().document();

        assertThat(reloaded.status()).isEqualTo(KnowledgeDocumentStatus.PARSED);
        assertThat(reloaded.parsedAt()).isEqualTo(UPLOADED_AT.plusSeconds(5));
        assertThat(reloaded.parseFailedAt()).isNull();
        assertThat(reloaded.parseFailureCode()).isNull();
    }

    @Test
    void failedSnapshotRoundTripsWithItsFailureCode() {
        KnowledgeDocument document = insertUploaded(fixture.repository(), "cas-key-g");
        claim(document);
        KnowledgeDocument claimed = fixture.repository().findById(document.id()).orElseThrow().document();
        claimed.markParseFailed(KnowledgeParseFailureCode.ENCRYPTED_DOCUMENT, UPLOADED_AT.plusSeconds(6));
        fixture.repository().update(claimed, 1L);

        KnowledgeDocument reloaded = fixture.repository().findById(document.id()).orElseThrow().document();

        assertThat(reloaded.status()).isEqualTo(KnowledgeDocumentStatus.PARSE_FAILED);
        assertThat(reloaded.parseFailedAt()).isEqualTo(UPLOADED_AT.plusSeconds(6));
        assertThat(reloaded.parseFailureCode()).isEqualTo(KnowledgeParseFailureCode.ENCRYPTED_DOCUMENT);
        assertThat(reloaded.parsedAt()).isNull();
    }

    @Test
    void theDatabaseItselfRejectsSnapshotsWhoseStatusAndParseFieldsDisagree() {
        KnowledgeDocument document = insertUploaded(fixture.repository(), "cas-key-h");

        // 领域层已经拒绝这种组合；数据库 CHECK 是第二道闸门，
        // 保护任何绕过聚合的写入（运维脚本、将来的批量任务）
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> this.jdbcClient
                .sql("UPDATE knowledge_documents SET parsed_at = ? WHERE id = ?")
                .param(1, java.time.OffsetDateTime.ofInstant(UPLOADED_AT, java.time.ZoneOffset.UTC))
                .param(2, document.id().value())
                .update()))
                .as("UPLOADED 状态不得携带 parsed_at")
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void databaseFailuresAreReportedAsStorageFailuresWithTheirCause() {
        JdbcKnowledgeDocumentRepository brokenRepository = repositoryAgainstAnEmptyDatabase();

        KnowledgeDocument document = insertUploaded(fixture.repository(), "cas-key-i");
        document.markParsing(UPLOADED_AT.plusSeconds(1));

        assertApplicationError(() -> brokenRepository.update(document, 0L),
                KnowledgeApplicationErrorCode.METADATA_STORAGE_FAILURE);
        assertApplicationError(() -> brokenRepository.findById(document.id()),
                KnowledgeApplicationErrorCode.METADATA_STORAGE_FAILURE);
    }

    /**
     * 指向一个<b>没有建表</b>的独立 H2 库：用真实数据库故障驱动异常映射。
     */
    private static JdbcKnowledgeDocumentRepository repositoryAgainstAnEmptyDatabase() {
        org.h2.jdbcx.JdbcDataSource empty = new org.h2.jdbcx.JdbcDataSource();
        empty.setURL("jdbc:h2:mem:flowdesk_document_cas_broken"
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        empty.setUser("sa");
        empty.setPassword("");
        // 刻意不跑 Flyway：knowledge_documents 表不存在
        return new JdbcKnowledgeDocumentRepository(JdbcClient.create(empty),
                new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(empty)));
    }

    private void claim(KnowledgeDocument document) {
        document.markParsing(UPLOADED_AT.plusSeconds(1));
        fixture.repository().update(document, 0L);
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
}
