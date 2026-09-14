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
                    // 竞争失败有两种可能：读到文档时它已经被别人领取（领域层拒绝转换），
                    // 或者读到时还是 UPLOADED、写回时 CAS 失败（仓储层拒绝）。
                    // 两者都是「正常落败」，因此捕获范围必须覆盖整段竞争逻辑。
                    try {
                        KnowledgeDocument candidate = fixture.repository().findById(document.id())
                                .orElseThrow().document();
                        candidate.markParsing(UPLOADED_AT.plusSeconds(1));
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
    void parsedCannotBeWrittenThroughTheGenericUpdate() {
        KnowledgeDocument document = insertUploaded(fixture.repository(), "cas-key-f");
        claim(document);
        KnowledgeDocument claimed = fixture.repository().findById(document.id()).orElseThrow().document();
        claimed.markParsed(UPLOADED_AT.plusSeconds(5));

        // PARSED 只能由 completeParsing 的原子端口落库（切片与状态必须同时生效）
        assertApplicationError(() -> fixture.repository().update(claimed, 1L),
                KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_NOT_PARSABLE);

        assertThat(statusOf(document)).isEqualTo("PARSING");
        assertThat(versionOf(document)).isEqualTo(1L);
        assertThat(parsedAtOf(document)).as("非法转换不得产生任何写入").isNull();
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

    // ---------- 通用 update 的状态转换矩阵（FD-0009-R1） ----------

    @Test
    void genericUpdateAllowsOnlyTheThreeLegalSingleSteps() {
        // UPLOADED → PARSING
        KnowledgeDocument uploaded = insertUploaded(fixture.repository(), "cas-key-legal-a");
        uploaded.markParsing(UPLOADED_AT.plusSeconds(1));
        assertThat(fixture.repository().update(uploaded, 0L).version()).isEqualTo(1L);

        // PARSING → PARSE_FAILED
        KnowledgeDocument parsing = fixture.repository().findById(uploaded.id()).orElseThrow().document();
        parsing.markParseFailed(KnowledgeParseFailureCode.CORRUPTED_DOCUMENT, UPLOADED_AT.plusSeconds(2));
        assertThat(fixture.repository().update(parsing, 1L).version()).isEqualTo(2L);
        assertThat(statusOf(uploaded)).isEqualTo("PARSE_FAILED");

        // PARSE_FAILED → PARSING（修复后重试）
        KnowledgeDocument failed = fixture.repository().findById(uploaded.id()).orElseThrow().document();
        failed.markParsing(UPLOADED_AT.plusSeconds(3));
        assertThat(fixture.repository().update(failed, 2L).version()).isEqualTo(3L);
        assertThat(statusOf(uploaded)).isEqualTo("PARSING");
    }

    @Test
    void genericUpdateRejectsUPLOADEDToPARSEDAndUPLOADEDToPARSEFAILED() {
        // 跳过 PARSING 直接写 PARSED：切片根本不会写入，文档却看起来已经解析完成
        assertRejectedJump("cas-key-jump-a", KnowledgeDocumentStatus.UPLOADED, 0L,
                KnowledgeDocumentStatus.PARSED);

        // 跳过领取直接写 PARSE_FAILED
        assertRejectedJump("cas-key-jump-b", KnowledgeDocumentStatus.UPLOADED, 0L,
                KnowledgeDocumentStatus.PARSE_FAILED);
    }

    @Test
    void genericUpdateRejectsPARSINGToPARSINGAndPARSINGToPARSED() {
        assertRejectedJump("cas-key-jump-c", KnowledgeDocumentStatus.PARSING, 1L,
                KnowledgeDocumentStatus.PARSING);
        assertRejectedJump("cas-key-jump-d", KnowledgeDocumentStatus.PARSING, 1L,
                KnowledgeDocumentStatus.PARSED);
    }

    @Test
    void genericUpdateRejectsPARSEFAILEDToPARSED() {
        assertRejectedJump("cas-key-jump-e", KnowledgeDocumentStatus.PARSE_FAILED, 2L,
                KnowledgeDocumentStatus.PARSED);
    }

    @Test
    void genericUpdateRejectsEveryTransitionOutOfAParsedRow() {
        // 造出一行真正的 PARSED 记录：只能经由 completeParsing 的原子端口
        KnowledgeDocument document = insertUploaded(fixture.repository(), "cas-key-parsed-row");
        long claimed = KnowledgePersistenceTestSupport.claim(fixture.repository(), document, 0L);
        document.markParsed(UPLOADED_AT.plusSeconds(2));
        fixture.chunkStore().completeParsing(document, claimed, List.of(
                new com.flowdesk.domain.knowledge.KnowledgeDocumentChunk(document.id(), 0, "切片",
                        2, com.flowdesk.domain.knowledge.Sha256Digest.of(
                                KnowledgePersistenceTestSupport.sha256Of("切片")),
                        UPLOADED_AT.plusSeconds(2))));
        assertThat(statusOf(document)).isEqualTo("PARSED");
        long parsedVersion = versionOf(document);

        // PARSED → PARSING：已定型的文档不得被重新领取
        assertApplicationError(() -> fixture.repository().update(
                aggregateIn(document, KnowledgeDocumentStatus.PARSING), parsedVersion),
                KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_NOT_PARSABLE);

        // PARSED → PARSE_FAILED：同样只能通过 completeParsing 之外的状态机入口
        assertApplicationError(() -> fixture.repository().update(
                aggregateIn(document, KnowledgeDocumentStatus.PARSE_FAILED), parsedVersion),
                KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_NOT_PARSABLE);

        assertThat(statusOf(document)).as("PARSED 行不得被通用 update 改写").isEqualTo("PARSED");
        assertThat(versionOf(document)).isEqualTo(parsedVersion);
        assertThat(fixture.chunkStore().countChunks(document.id()))
                .as("被拒绝的写入不得影响切片").isEqualTo(1L);
    }

    /**
     * 断言「数据库当前状态 → 目标状态」的非法跳跃被拒绝，且不产生任何写入。
     *
     * @param contentKey     内容键（唯一）
     * @param databaseStatus 数据库里该行的当前状态
     * @param expectedVersion 数据库里该行的当前版本（必须与目标聚合一起传对，才能证明拒绝的是状态而非版本）
     * @param targetStatus   想通过通用 update 写入的状态
     */
    private void assertRejectedJump(String contentKey, KnowledgeDocumentStatus databaseStatus,
            long expectedVersion, KnowledgeDocumentStatus targetStatus) {

        KnowledgeDocument stored = insertUploaded(fixture.repository(), contentKey);
        if (databaseStatus == KnowledgeDocumentStatus.PARSING) {
            KnowledgePersistenceTestSupport.claim(fixture.repository(), stored, 0L);
        }
        else if (databaseStatus == KnowledgeDocumentStatus.PARSE_FAILED) {
            long claimed = KnowledgePersistenceTestSupport.claim(fixture.repository(), stored, 0L);
            KnowledgeDocument claimedDocument = fixture.repository().findById(stored.id()).orElseThrow()
                    .document();
            claimedDocument.markParseFailed(KnowledgeParseFailureCode.CORRUPTED_DOCUMENT,
                    UPLOADED_AT.plusSeconds(2));
            fixture.repository().update(claimedDocument, claimed);
        }
        else if (databaseStatus != KnowledgeDocumentStatus.UPLOADED) {
            throw new IllegalArgumentException("测试不支持以 " + databaseStatus + " 为起点");
        }
        assertThat(statusOf(stored)).isEqualTo(databaseStatus.name());
        assertThat(versionOf(stored)).isEqualTo(expectedVersion);

        // 目标聚合用同一个标识、但自行推进到目标状态（模拟「有人跳过中间状态直接拼一个结果」）
        KnowledgeDocument illegalTarget = aggregateIn(stored, targetStatus);

        assertApplicationError(() -> fixture.repository().update(illegalTarget, expectedVersion),
                KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_NOT_PARSABLE);

        assertThat(statusOf(stored)).as("非法转换不得改变状态").isEqualTo(databaseStatus.name());
        assertThat(versionOf(stored)).as("非法转换不得推进版本").isEqualTo(expectedVersion);
        assertThat(parsedAtOf(stored)).isNull();
    }

    /**
     * 用同一份标识与元数据、在内存里把聚合推进到指定状态（不落库）。
     */
    private static KnowledgeDocument aggregateIn(KnowledgeDocument stored, KnowledgeDocumentStatus status) {
        KnowledgeDocument aggregate = KnowledgeDocument.create(stored.id(), stored.title(),
                stored.originalFilename(), stored.format(), stored.mediaType(), stored.sizeBytes(),
                stored.sha256(), stored.contentKey(), UPLOADED_AT);
        switch (status) {
            case UPLOADED -> {
                // 什么都不做
            }
            case PARSING -> aggregate.markParsing(UPLOADED_AT.plusSeconds(1));
            case PARSED -> {
                aggregate.markParsing(UPLOADED_AT.plusSeconds(1));
                aggregate.markParsed(UPLOADED_AT.plusSeconds(2));
            }
            case PARSE_FAILED -> {
                aggregate.markParsing(UPLOADED_AT.plusSeconds(1));
                aggregate.markParseFailed(KnowledgeParseFailureCode.CORRUPTED_DOCUMENT,
                        UPLOADED_AT.plusSeconds(2));
            }
        }
        return aggregate;
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

    private java.time.OffsetDateTime parsedAtOf(KnowledgeDocument document) {
        return this.jdbcClient.sql("SELECT parsed_at FROM knowledge_documents WHERE id = ?")
                .param(1, document.id().value()).query(java.time.OffsetDateTime.class).optional().orElse(null);
    }
}
