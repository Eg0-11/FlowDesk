package com.flowdesk.infrastructure.knowledge.persistence.jdbc;

import static com.flowdesk.infrastructure.knowledge.persistence.jdbc.KnowledgePersistenceTestSupport.UPLOADED_AT;
import static com.flowdesk.infrastructure.knowledge.persistence.jdbc.KnowledgePersistenceTestSupport.assertApplicationError;
import static com.flowdesk.infrastructure.knowledge.persistence.jdbc.KnowledgePersistenceTestSupport.claim;
import static com.flowdesk.infrastructure.knowledge.persistence.jdbc.KnowledgePersistenceTestSupport.insertUploaded;
import static com.flowdesk.infrastructure.knowledge.persistence.jdbc.KnowledgePersistenceTestSupport.randomId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.application.knowledge.port.out.VersionedKnowledgeDocument;
import com.flowdesk.domain.knowledge.KnowledgeDocument;
import com.flowdesk.domain.knowledge.KnowledgeDocumentChunk;
import com.flowdesk.domain.knowledge.KnowledgeDocumentId;
import com.flowdesk.domain.knowledge.KnowledgeDocumentStatus;
import com.flowdesk.domain.knowledge.KnowledgeParseFailureCode;
import com.flowdesk.domain.knowledge.Sha256Digest;
import com.flowdesk.infrastructure.knowledge.KnowledgeTestContent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 「完成解析」的 JDBC 原子性测试（FD-0009）。
 *
 * <p>这里验证的是本项目最关键的一条数据一致性约定：<b>删除旧切片、插入新切片、
 * 把文档标记为 PARSED 并加版本，必须整体成功或整体回滚</b>。
 * 因此除了 happy path，重点用例是「中途失败后数据库里什么都没有改变」。</p>
 */
class JdbcKnowledgeDocumentChunkStoreIntegrationTest {

    private static KnowledgePersistenceTestSupport.Fixture fixture;

    private JdbcClient jdbcClient;

    @BeforeAll
    static void migrate() {
        fixture = KnowledgePersistenceTestSupport.migrate("flowdesk_chunk_store_it");
    }

    @BeforeEach
    void clearTables() {
        this.jdbcClient = fixture.jdbcClient();
        this.jdbcClient.sql("DELETE FROM knowledge_document_chunks").update();
        this.jdbcClient.sql("DELETE FROM knowledge_documents").update();
    }

    // ---------- ① 成功路径 ----------

    @Test
    void completionWritesChunksAndMarksTheDocumentParsedInOneStep() {
        KnowledgeDocument document = insertUploaded(fixture.repository(), "key-a");
        long claimed = claim(fixture.repository(), document, 0L);
        assertThat(claimed).isEqualTo(1L);

        List<KnowledgeDocumentChunk> chunks = chunks(document.id(), "第一段", "第二段", "第三段");
        document.markParsed(UPLOADED_AT.plusSeconds(2));
        VersionedKnowledgeDocument saved = fixture.chunkStore().completeParsing(document, claimed, chunks);

        assertThat(saved.version()).as("完成阶段把版本再加 1").isEqualTo(2L);
        assertThat(saved.document().status()).isEqualTo(KnowledgeDocumentStatus.PARSED);
        assertThat(saved.document().parsedAt()).isEqualTo(UPLOADED_AT.plusSeconds(2));

        assertThat(statusOf(document.id())).isEqualTo("PARSED");
        assertThat(versionOf(document.id())).isEqualTo(2L);
        assertThat(parsedAtOf(document.id())).isNotNull();
        assertThat(fixture.chunkStore().countChunks(document.id())).isEqualTo(3);
    }

    @Test
    void storedChunksKeepTheirIndexContentCodePointCountAndDigest() {
        KnowledgeDocument document = insertUploaded(fixture.repository(), "key-b");
        long claimed = claim(fixture.repository(), document, 0L);
        String first = "含 emoji 的切片 📄";
        String second = "第二片";
        document.markParsed(UPLOADED_AT.plusSeconds(2));

        fixture.chunkStore().completeParsing(document, claimed, chunks(document.id(), first, second));

        List<KnowledgeDocumentChunk> stored = fixture.chunkStore().findChunks(document.id(), 0, 10);
        assertThat(stored).hasSize(2);
        assertThat(stored.get(0).chunkIndex()).isZero();
        assertThat(stored.get(0).content()).isEqualTo(first);
        assertThat(stored.get(0).codePointCount()).isEqualTo(first.codePointCount(0, first.length()));
        assertThat(stored.get(0).sha256().value())
                .isEqualTo(KnowledgeTestContent.sha256Hex(first.getBytes(StandardCharsets.UTF_8)));
        assertThat(stored.get(1).chunkIndex()).isEqualTo(1);
        assertThat(stored.get(1).content()).isEqualTo(second);
    }

    // ---------- ② 替换旧切片 ----------

    @Test
    void aSecondCompletionReplacesTheChunksOfTheFirstOne() {
        KnowledgeDocument document = insertUploaded(fixture.repository(), "key-c");
        long firstClaim = claim(fixture.repository(), document, 0L);
        document.markParsed(UPLOADED_AT.plusSeconds(2));
        fixture.chunkStore().completeParsing(document, firstClaim,
                chunks(document.id(), "旧片一", "旧片二", "旧片三"));
        assertThat(fixture.chunkStore().countChunks(document.id())).isEqualTo(3);

        // 模拟「人工把文档退回可重试状态」（文档里记录的恢复方式），然后用更少的切片重新解析
        java.time.OffsetDateTime rolledBackAt =
                java.time.OffsetDateTime.ofInstant(UPLOADED_AT.plusSeconds(3), java.time.ZoneOffset.UTC);
        this.jdbcClient.sql("UPDATE knowledge_documents SET status = 'PARSE_FAILED', parsed_at = NULL, "
                + "parse_failed_at = ?, parse_failure_code = 'PARSER_FAILURE', updated_at = ? WHERE id = ?")
                .param(1, rolledBackAt)
                .param(2, rolledBackAt)
                .param(3, document.id().value())
                .update();

        KnowledgeDocument reloaded = fixture.repository().findById(document.id()).orElseThrow().document();
        long secondClaim = claim(fixture.repository(), reloaded, versionOf(document.id()),
                UPLOADED_AT.plusSeconds(4));
        reloaded.markParsed(UPLOADED_AT.plusSeconds(5));
        fixture.chunkStore().completeParsing(reloaded, secondClaim, chunks(document.id(), "新片"));

        List<KnowledgeDocumentChunk> stored = fixture.chunkStore().findChunks(document.id(), 0, 10);
        assertThat(stored).as("旧切片必须被替换，而不是与新切片共存").hasSize(1);
        assertThat(stored.get(0).content()).isEqualTo("新片");
    }

    // ---------- ③ 拒绝条件与回滚 ----------

    @Test
    void versionMismatchIsRejectedBeforeAnythingIsWritten() {
        KnowledgeDocument document = insertUploaded(fixture.repository(), "key-d");
        claim(fixture.repository(), document, 0L);
        document.markParsed(UPLOADED_AT.plusSeconds(2));

        assertApplicationError(() -> fixture.chunkStore().completeParsing(document, 0L,
                chunks(document.id(), "不该落库")),
                KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_VERSION_CONFLICT);

        assertThat(statusOf(document.id())).isEqualTo("PARSING");
        assertThat(fixture.chunkStore().countChunks(document.id())).isZero();
    }

    @Test
    void aDocumentThatIsNotParsingIsRejected() {
        KnowledgeDocument document = insertUploaded(fixture.repository(), "key-e");
        document.markParsing(UPLOADED_AT.plusSeconds(1));
        document.markParsed(UPLOADED_AT.plusSeconds(2));

        assertApplicationError(() -> fixture.chunkStore().completeParsing(document, 0L,
                chunks(document.id(), "不该落库")),
                KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_NOT_PARSABLE);

        assertThat(statusOf(document.id())).isEqualTo("UPLOADED");
        assertThat(fixture.chunkStore().countChunks(document.id())).isZero();
    }

    @Test
    void unknownDocumentIsReportedAsNotFound() {
        KnowledgeDocumentId missing = randomId();
        KnowledgeDocument document = insertUploaded(fixture.repository(), missing, "key-f");
        document.markParsing(UPLOADED_AT.plusSeconds(1));
        document.markParsed(UPLOADED_AT.plusSeconds(2));
        this.jdbcClient.sql("DELETE FROM knowledge_documents WHERE id = ?")
                .param(1, missing.value()).update();

        assertApplicationError(() -> fixture.chunkStore().completeParsing(document, 1L,
                chunks(missing, "不该落库")),
                KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_NOT_FOUND);
    }

    @Test
    void aFailureWhileInsertingChunksRollsBackTheWholeTransaction() {
        KnowledgeDocument document = insertUploaded(fixture.repository(), "key-g");
        long claimed = claim(fixture.repository(), document, 0L);
        document.markParsed(UPLOADED_AT.plusSeconds(2));

        // 用「第二条 INSERT 语句必定失败」的数据源：第一片已经插入之后才炸，
        // 因此这条用例证明的是「中途失败会整体回滚」，而不是前置校验
        fixture.resetStatements();
        fixture.failOnStatement(4);

        assertThatThrownBy(() -> fixture.chunkStore().completeParsing(document, claimed,
                chunks(document.id(), "第一片", "第二片")))
                .as("写入失败必须以稳定的应用层错误码暴露，而不是泄漏 Spring 的 JDBC 异常")
                .isInstanceOf(KnowledgeApplicationException.class)
                .extracting(thrown -> ((KnowledgeApplicationException) thrown).errorCode())
                .isEqualTo(KnowledgeApplicationErrorCode.METADATA_STORAGE_FAILURE);

        assertThat(statusOf(document.id()))
                .as("失败必须整体回滚：文档不能变成 PARSED")
                .isEqualTo("PARSING");
        assertThat(versionOf(document.id())).as("版本也不能前进").isEqualTo(1L);
        assertThat(fixture.chunkStore().countChunks(document.id()))
                .as("已插入的第一片必须一起回滚")
                .isZero();
    }

    // ---------- ③.1 完成解析的入参防线（FD-0009-R1） ----------

    @Test
    void completeParsingRejectsAnEmptyChunkList() {
        assertGuarded("key-guard-empty", documentId -> List.of(), "完成解析不接受空切片列表");
    }

    @Test
    void completeParsingRejectsNullElements() {
        assertGuarded("key-guard-null",
                documentId -> java.util.Arrays.asList(chunkFor(documentId, 0, "第一片"), null),
                "切片列表中不能包含 null");
    }

    @Test
    void completeParsingRejectsChunksBelongingToAnotherDocument() {
        assertGuarded("key-guard-owner",
                documentId -> List.of(chunkFor(documentId, 0, "第一片"),
                        chunkFor(randomId(), 1, "别人的切片")),
                "切片归属的文档与目标文档不一致");
    }

    @Test
    void completeParsingRejectsGappedChunkIndexes() {
        assertGuarded("key-guard-gap",
                documentId -> List.of(chunkFor(documentId, 0, "第一片"), chunkFor(documentId, 2, "跳号了")),
                "切片序号必须从 0 开始严格连续递增");
    }

    @Test
    void completeParsingRejectsOutOfOrderChunkIndexes() {
        assertGuarded("key-guard-order",
                documentId -> List.of(chunkFor(documentId, 1, "第二片"), chunkFor(documentId, 0, "第一片")),
                "切片序号必须从 0 开始严格连续递增");
    }

    @Test
    void completeParsingRejectsADocumentThatIsNotMarkedParsed() {
        KnowledgeDocument document = insertUploaded(fixture.repository(), "key-guard-status");
        long claimed = claim(fixture.repository(), document, 0L);
        // 聚合仍是 PARSING：完成端口只接受 PARSED 的聚合
        fixture.resetStatements();

        assertApplicationError(() -> fixture.chunkStore().completeParsing(document, claimed,
                chunks(document.id(), "不该落库")),
                KnowledgeApplicationErrorCode.KNOWLEDGE_INTERNAL_ERROR);

        assertThat(fixture.statementsExecuted()).isZero();
        assertThat(statusOf(document.id())).isEqualTo("PARSING");
        assertThat(versionOf(document.id())).isEqualTo(1L);
        assertThat(fixture.chunkStore().countChunks(document.id())).isZero();
    }

    /**
     * 断言非法切片输入被稳定错误拒绝，且<b>一条 SQL 都没有下发</b>。
     *
     * @param contentKey      内容键
     * @param illegalChunks   以目标文档标识构造非法切片列表的函数
     * @param expectedMessage 期望的内部错误说明
     */
    private void assertGuarded(String contentKey,
            java.util.function.Function<KnowledgeDocumentId, List<KnowledgeDocumentChunk>> illegalChunks,
            String expectedMessage) {

        KnowledgeDocument document = insertUploaded(fixture.repository(), contentKey);
        long claimed = claim(fixture.repository(), document, 0L);
        document.markParsed(UPLOADED_AT.plusSeconds(2));
        List<KnowledgeDocumentChunk> prepared = illegalChunks.apply(document.id());

        fixture.resetStatements();
        KnowledgeApplicationException thrown = (KnowledgeApplicationException) org.assertj.core.api.Assertions
                .catchThrowable(() -> fixture.chunkStore().completeParsing(document, claimed, prepared));

        assertThat(thrown.errorCode()).isEqualTo(KnowledgeApplicationErrorCode.KNOWLEDGE_INTERNAL_ERROR);
        assertThat(thrown.getMessage()).isEqualTo(expectedMessage);
        assertThat(fixture.statementsExecuted())
                .as("前置校验必须发生在开启事务与执行 SQL 之前")
                .isZero();
        assertThat(statusOf(document.id())).isEqualTo("PARSING");
        assertThat(versionOf(document.id())).isEqualTo(1L);
        assertThat(fixture.chunkStore().countChunks(document.id()))
                .as("非法输入不得产生任何切片").isZero();
    }

    private static KnowledgeDocumentChunk chunkFor(KnowledgeDocumentId documentId, int index, String content) {
        return new KnowledgeDocumentChunk(documentId, index, content,
                content.codePointCount(0, content.length()),
                Sha256Digest.of(KnowledgeTestContent.sha256Hex(content.getBytes(StandardCharsets.UTF_8))),
                Instant.parse("2026-05-01T10:00:05Z"));
    }

    @Test
    void aFailureWhileUpdatingTheDocumentRollsBackInsertedChunks() {
        KnowledgeDocument document = insertUploaded(fixture.repository(), "key-h");
        long claimed = claim(fixture.repository(), document, 0L);
        // 完成阶段用错误的版本：切片插入发生在前，文档 CAS 必然失败
        document.markParsed(UPLOADED_AT.plusSeconds(2));

        assertApplicationError(() -> fixture.chunkStore().completeParsing(document, claimed + 5L,
                chunks(document.id(), "第一片", "第二片")),
                KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_VERSION_CONFLICT);

        assertThat(statusOf(document.id())).isEqualTo("PARSING");
        assertThat(fixture.chunkStore().countChunks(document.id()))
                .as("CAS 失败时已插入的切片必须回滚")
                .isZero();
    }

    // ---------- ④ 读取端口 ----------

    @Test
    void countAndPagedReadsFollowChunkIndexOrder() {
        KnowledgeDocument document = insertUploaded(fixture.repository(), "key-i");
        long claimed = claim(fixture.repository(), document, 0L);
        document.markParsed(UPLOADED_AT.plusSeconds(2));
        fixture.chunkStore().completeParsing(document, claimed,
                chunks(document.id(), "片零", "片一", "片二", "片三", "片四"));

        assertThat(fixture.chunkStore().countChunks(document.id())).isEqualTo(5);
        assertThat(fixture.chunkStore().findChunks(document.id(), 0, 2))
                .extracting(KnowledgeDocumentChunk::chunkIndex).containsExactly(0, 1);
        assertThat(fixture.chunkStore().findChunks(document.id(), 2, 2))
                .extracting(KnowledgeDocumentChunk::chunkIndex).containsExactly(2, 3);
        assertThat(fixture.chunkStore().findChunks(document.id(), 4, 2))
                .extracting(KnowledgeDocumentChunk::chunkIndex).containsExactly(4);
        assertThat(fixture.chunkStore().findChunks(document.id(), 5, 2)).isEmpty();
        assertThat(fixture.chunkStore().countChunks(randomId())).as("没有切片的文档返回 0").isZero();
    }

    @Test
    void pagedReadsRejectIllegalPagingParameters() {
        assertApplicationError(() -> fixture.chunkStore().findChunks(randomId(), -1, 10),
                KnowledgeApplicationErrorCode.INVALID_QUERY);
        assertApplicationError(() -> fixture.chunkStore().findChunks(randomId(), 0, 0),
                KnowledgeApplicationErrorCode.INVALID_QUERY);
    }

    // ---------- ⑤ 表结构与约束（V4 迁移） ----------

    @Test
    void chunksTableHasTheExpectedPrimaryKeyAndForeignKeyCascade() {
        KnowledgeDocument document = insertUploaded(fixture.repository(), "key-j");
        long claimed = claim(fixture.repository(), document, 0L);
        document.markParsed(UPLOADED_AT.plusSeconds(2));
        fixture.chunkStore().completeParsing(document, claimed, chunks(document.id(), "片"));

        assertThat(fixture.chunkStore().countChunks(document.id())).isEqualTo(1);

        this.jdbcClient.sql("DELETE FROM knowledge_documents WHERE id = ?")
                .param(1, document.id().value()).update();

        assertThat(fixture.chunkStore().countChunks(document.id()))
                .as("删除文档必须级联删除切片，不留孤儿行")
                .isZero();
    }

    @Test
    void duplicateChunkIndexForTheSameDocumentIsRejectedByThePrimaryKey() {
        KnowledgeDocumentId id = insertUploaded(fixture.repository(), "key-pk").id();
        KnowledgeDocumentId other = insertUploaded(fixture.repository(), "key-pk-2").id();
        insertRawChunk(id, 0, "abc", 3, "a".repeat(64));
        insertRawChunk(other, 0, "abc", 3, "a".repeat(64));

        assertThatThrownBy(() -> insertRawChunk(id, 0, "other", 5, "b".repeat(64)))
                .as("同一文档内序号是主键的一部分")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void chunkTableCheckConstraintsRejectInconsistentRows() {
        KnowledgeDocumentId id = insertUploaded(fixture.repository(), "key-check").id();

        assertThatThrownBy(() -> insertRawChunk(id, -1, "abc", 3, "a".repeat(64)))
                .as("序号不得为负").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRawChunk(id, 0, "", 0, "a".repeat(64)))
                .as("内容不得为空").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRawChunk(id, 1, "abc", 0, "a".repeat(64)))
                .as("code point 计数必须为正").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRawChunk(id, 2, "abc", 3, "a".repeat(63)))
                .as("摘要必须是 64 位十六进制").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRawChunk(id, 3, "abc", 3, "A".repeat(64)))
                .as("摘要必须是小写十六进制").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRawChunk(randomId(), 0, "abc", 3, "a".repeat(64)))
                .as("切片必须属于一个真实存在的文档").isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void documentTableRejectsUnknownStatusAndInconsistentParseFields() {
        KnowledgeDocument document = insertUploaded(fixture.repository(), "key-k");

        assertThatThrownBy(() -> this.jdbcClient.sql("UPDATE knowledge_documents SET status = 'DONE' WHERE id = ?")
                .param(1, document.id().value()).update())
                .as("状态只允许四个枚举值").isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> this.jdbcClient.sql("UPDATE knowledge_documents SET status = 'PARSED' WHERE id = ?")
                .param(1, document.id().value()).update())
                .as("PARSED 必须带 parsed_at").isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> this.jdbcClient.sql("UPDATE knowledge_documents SET status = 'PARSE_FAILED', "
                + "parse_failed_at = ? WHERE id = ?")
                .param(1, java.time.OffsetDateTime.ofInstant(UPLOADED_AT, java.time.ZoneOffset.UTC))
                .param(2, document.id().value()).update())
                .as("PARSE_FAILED 必须带失败码").isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> this.jdbcClient.sql("UPDATE knowledge_documents SET status = 'PARSING', "
                + "parse_failure_code = 'PARSER_FAILURE' WHERE id = ?")
                .param(1, document.id().value()).update())
                .as("PARSING 不得携带解析结果字段").isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anUnknownPersistedFailureCodeIsReportedAsACorruptedSnapshot() {
        KnowledgeDocument document = insertUploaded(fixture.repository(), "key-l");
        this.jdbcClient.sql("UPDATE knowledge_documents SET status = 'PARSE_FAILED', parse_failed_at = ?, "
                + "parse_failure_code = 'NOT_A_REAL_CODE' WHERE id = ?")
                .param(1, java.time.OffsetDateTime.ofInstant(UPLOADED_AT, java.time.ZoneOffset.UTC))
                .param(2, document.id().value())
                .update();

        assertApplicationError(() -> fixture.repository().findById(document.id()),
                KnowledgeApplicationErrorCode.INVALID_PERSISTED_DOCUMENT);
    }

    // ---------- 辅助 ----------

    private static List<KnowledgeDocumentChunk> chunks(KnowledgeDocumentId documentId, String... contents) {
        List<KnowledgeDocumentChunk> chunks = new ArrayList<>(contents.length);
        for (int index = 0; index < contents.length; index++) {
            chunks.add(chunk(documentId, index, contents[index]));
        }
        return List.copyOf(chunks);
    }

    private static KnowledgeDocumentChunk chunk(KnowledgeDocumentId documentId, int index, String content) {
        return new KnowledgeDocumentChunk(documentId, index, content,
                content.codePointCount(0, content.length()),
                Sha256Digest.of(KnowledgeTestContent.sha256Hex(content.getBytes(StandardCharsets.UTF_8))),
                Instant.parse("2026-05-01T10:00:05Z"));
    }

    private void insertRawChunk(KnowledgeDocumentId id, int index, String content, int codePoints,
            String sha256) {

        this.jdbcClient.sql("INSERT INTO knowledge_document_chunks (document_id, chunk_index, content, "
                + "code_point_count, sha256, created_at) VALUES (?, ?, ?, ?, ?, ?)")
                .param(1, id.value())
                .param(2, index)
                .param(3, content)
                .param(4, codePoints)
                .param(5, sha256)
                .param(6, java.time.OffsetDateTime.ofInstant(Instant.parse("2026-05-01T10:00:05Z"),
                        java.time.ZoneOffset.UTC))
                .update();
    }

    private String statusOf(KnowledgeDocumentId id) {
        return this.jdbcClient.sql("SELECT status FROM knowledge_documents WHERE id = ?")
                .param(1, id.value()).query(String.class).single();
    }

    private long versionOf(KnowledgeDocumentId id) {
        Long version = this.jdbcClient.sql("SELECT version FROM knowledge_documents WHERE id = ?")
                .param(1, id.value()).query(Long.class).single();
        return version == null ? -1L : version;
    }

    private java.time.OffsetDateTime parsedAtOf(KnowledgeDocumentId id) {
        return this.jdbcClient.sql("SELECT parsed_at FROM knowledge_documents WHERE id = ?")
                .param(1, id.value()).query(java.time.OffsetDateTime.class).optional().orElse(null);
    }
}
