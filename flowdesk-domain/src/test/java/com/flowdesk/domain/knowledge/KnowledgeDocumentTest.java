package com.flowdesk.domain.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link KnowledgeDocument} 聚合测试：创建不变量、恢复校验、相等性与日志安全。
 */
class KnowledgeDocumentTest {

    private static final KnowledgeDocumentId ID =
            KnowledgeDocumentId.of(UUID.fromString("11111111-2222-3333-4444-555555555555"));

    private static final Instant NOW = Instant.parse("2026-05-01T10:00:00Z");

    private static final String DIGEST = "0123456789abcdef".repeat(4);

    private static final String CONTENT_KEY = "content-abc123";

    private static final String TITLE = "季度运维报告";

    private static final String FILE_NAME = "report.pdf";

    // ---------- 创建 ----------

    @Test
    void createsUploadedDocumentWithEqualTimestamps() {
        KnowledgeDocument document = create(DocumentFormat.PDF, FILE_NAME, "application/pdf", 1024L, DIGEST,
                CONTENT_KEY, NOW);

        assertThat(document.id()).isEqualTo(ID);
        assertThat(document.title().value()).isEqualTo(TITLE);
        assertThat(document.originalFilename().value()).isEqualTo(FILE_NAME);
        assertThat(document.format()).isEqualTo(DocumentFormat.PDF);
        assertThat(document.mediaType()).isEqualTo("application/pdf");
        assertThat(document.sizeBytes()).isEqualTo(1024L);
        assertThat(document.sha256().value()).isEqualTo(DIGEST);
        assertThat(document.contentKey()).isEqualTo(CONTENT_KEY);
        assertThat(document.status()).isEqualTo(KnowledgeDocumentStatus.UPLOADED);
        assertThat(document.createdAt()).isEqualTo(NOW);
        assertThat(document.updatedAt()).as("新上传时两个时间必须相等").isEqualTo(NOW);
    }

    @Test
    void theStatusSetIsExactlyTheTwoLifecycles() {
        // FD-0009 加入解析状态机、FD-0010 加入索引状态机：状态只有这七个，
        // 多一个都意味着对外契约被悄悄改动
        assertThat(KnowledgeDocumentStatus.values()).containsExactly(
                KnowledgeDocumentStatus.UPLOADED,
                KnowledgeDocumentStatus.PARSING,
                KnowledgeDocumentStatus.PARSED,
                KnowledgeDocumentStatus.PARSE_FAILED,
                KnowledgeDocumentStatus.INDEXING,
                KnowledgeDocumentStatus.INDEXED,
                KnowledgeDocumentStatus.INDEX_FAILED);
    }

    @Test
    void rejectsMissingIdFormatOrUploadTime() {
        assertError(KnowledgeErrorCode.INVALID_DOCUMENT_ID, () -> KnowledgeDocument.create(null,
                DocumentTitle.of(TITLE), OriginalFilename.of(FILE_NAME), DocumentFormat.PDF, "application/pdf",
                1L, Sha256Digest.of(DIGEST), CONTENT_KEY, NOW));

        assertError(KnowledgeErrorCode.INVALID_FORMAT, () -> KnowledgeDocument.create(ID, DocumentTitle.of(TITLE),
                OriginalFilename.of(FILE_NAME), null, "application/pdf", 1L, Sha256Digest.of(DIGEST),
                CONTENT_KEY, NOW));

        assertError(KnowledgeErrorCode.INVALID_TIMELINE, () -> KnowledgeDocument.create(ID,
                DocumentTitle.of(TITLE), OriginalFilename.of(FILE_NAME), DocumentFormat.PDF, "application/pdf",
                1L, Sha256Digest.of(DIGEST), CONTENT_KEY, null));
    }

    @Test
    void rejectsZeroOrNegativeSize() {
        assertError(KnowledgeErrorCode.INVALID_SIZE, () -> create(DocumentFormat.PDF, FILE_NAME,
                "application/pdf", 0L, DIGEST, CONTENT_KEY, NOW));
        assertError(KnowledgeErrorCode.INVALID_SIZE, () -> create(DocumentFormat.PDF, FILE_NAME,
                "application/pdf", -1L, DIGEST, CONTENT_KEY, NOW));
    }

    @Test
    void rejectsMissingDigestMediaTypeOrContentKey() {
        assertError(KnowledgeErrorCode.INVALID_DIGEST, () -> create(DocumentFormat.PDF, FILE_NAME,
                "application/pdf", 1L, null, CONTENT_KEY, NOW));
        assertError(KnowledgeErrorCode.INVALID_MEDIA_TYPE, () -> create(DocumentFormat.PDF, FILE_NAME, null, 1L,
                DIGEST, CONTENT_KEY, NOW));
        assertError(KnowledgeErrorCode.INVALID_MEDIA_TYPE, () -> create(DocumentFormat.PDF, FILE_NAME, "  ", 1L,
                DIGEST, CONTENT_KEY, NOW));
        assertError(KnowledgeErrorCode.INVALID_CONTENT_KEY, () -> create(DocumentFormat.PDF, FILE_NAME,
                "application/pdf", 1L, DIGEST, null, NOW));
    }

    @Test
    void rejectsContentKeyThatLooksLikeAPath() {
        assertError(KnowledgeErrorCode.INVALID_CONTENT_KEY, () -> create(DocumentFormat.PDF, FILE_NAME,
                "application/pdf", 1L, DIGEST, "../escape", NOW));
        assertError(KnowledgeErrorCode.INVALID_CONTENT_KEY, () -> create(DocumentFormat.PDF, FILE_NAME,
                "application/pdf", 1L, DIGEST, "a\\b", NOW));
        assertError(KnowledgeErrorCode.INVALID_CONTENT_KEY, () -> create(DocumentFormat.PDF, FILE_NAME,
                "application/pdf", 1L, DIGEST, "a/b", NOW));
    }

    @Test
    void rejectsMediaTypeWithWhitespaceOrControlCharacters() {
        assertError(KnowledgeErrorCode.INVALID_MEDIA_TYPE, () -> create(DocumentFormat.PDF, FILE_NAME,
                "application/ pdf", 1L, DIGEST, CONTENT_KEY, NOW));
        assertError(KnowledgeErrorCode.INVALID_MEDIA_TYPE, () -> create(DocumentFormat.PDF, FILE_NAME,
                "application/pdf\n", 1L, DIGEST, CONTENT_KEY, NOW));
    }

    // ---------- 恢复 ----------

    @Test
    void restoresAValidSnapshot() {
        KnowledgeDocument document = restore(DocumentFormat.TEXT, "notes.txt", "text/plain", 10L,
                Sha256Digest.of(DIGEST), CONTENT_KEY, KnowledgeDocumentStatus.UPLOADED, NOW, NOW.plusSeconds(5));

        assertThat(document.title().value()).isEqualTo(TITLE);
        assertThat(document.originalFilename().value()).isEqualTo("notes.txt");
        assertThat(document.format()).isEqualTo(DocumentFormat.TEXT);
        assertThat(document.createdAt()).isEqualTo(NOW);
        assertThat(document.updatedAt()).isEqualTo(NOW.plusSeconds(5));
    }

    @Test
    void restoreRevalidatesEveryFieldInsteadOfTrustingTheDatabase() {
        // 数据库里的非法快照必须在恢复时立刻暴露，而不是被当作正常数据继续使用
        assertRestoreRejected(DocumentFormat.TEXT, "notes.txt", "text/plain", 0L, Sha256Digest.of(DIGEST),
                CONTENT_KEY, KnowledgeDocumentStatus.UPLOADED, NOW, NOW);
        assertRestoreRejected(DocumentFormat.TEXT, "../etc/passwd", "text/plain", 10L, Sha256Digest.of(DIGEST),
                CONTENT_KEY, KnowledgeDocumentStatus.UPLOADED, NOW, NOW);
        assertRestoreRejected(DocumentFormat.TEXT, "docs\\file.txt", "text/plain", 10L, Sha256Digest.of(DIGEST),
                CONTENT_KEY, KnowledgeDocumentStatus.UPLOADED, NOW, NOW);
        assertRestoreRejected(DocumentFormat.TEXT, "  ", "text/plain", 10L, Sha256Digest.of(DIGEST),
                CONTENT_KEY, KnowledgeDocumentStatus.UPLOADED, NOW, NOW);
        assertRestoreRejected(DocumentFormat.TEXT, "notes.txt", null, 10L, Sha256Digest.of(DIGEST),
                CONTENT_KEY, KnowledgeDocumentStatus.UPLOADED, NOW, NOW);
        assertRestoreRejected(DocumentFormat.TEXT, "notes.txt", "text/plain", 10L, null, CONTENT_KEY,
                KnowledgeDocumentStatus.UPLOADED, NOW, NOW);
        assertRestoreRejected(DocumentFormat.TEXT, "notes.txt", "text/plain", 10L, Sha256Digest.of(DIGEST),
                null, KnowledgeDocumentStatus.UPLOADED, NOW, NOW);
        assertRestoreRejected(DocumentFormat.TEXT, "notes.txt", "text/plain", 10L, Sha256Digest.of(DIGEST),
                CONTENT_KEY, null, NOW, NOW);
        assertRestoreRejected(DocumentFormat.TEXT, "notes.txt", "text/plain", 10L, Sha256Digest.of(DIGEST),
                CONTENT_KEY, KnowledgeDocumentStatus.UPLOADED, null, NOW);
    }

    @Test
    void restoreRejectsBrokenTimeline() {
        assertRestoreRejected(DocumentFormat.TEXT, "notes.txt", "text/plain", 10L, Sha256Digest.of(DIGEST),
                CONTENT_KEY, KnowledgeDocumentStatus.UPLOADED, NOW.plusSeconds(10), NOW);
    }

    @Test
    void restoreReportsTheFieldCodeWithoutEchoingValues() {
        String sentinel = "sentinel-../evil";

        assertThatThrownBy(() -> KnowledgeDocument.restore(ID, TITLE, sentinel, DocumentFormat.TEXT, "text/plain",
                10L, Sha256Digest.of(DIGEST), CONTENT_KEY, KnowledgeDocumentStatus.UPLOADED, NOW, NOW,
                null, null, null, null, null, null, null, null))
                .isInstanceOf(KnowledgeDomainException.class)
                .hasMessageNotContaining(sentinel)
                .hasMessageContaining(KnowledgeErrorCode.INVALID_ORIGINAL_FILENAME.name());
    }

    @Test
    void restoresParsedAndFailedSnapshotsWithTheirParseFields() {
        KnowledgeDocument parsed = restore(DocumentFormat.TEXT, "notes.txt", "text/plain", 10L,
                Sha256Digest.of(DIGEST), CONTENT_KEY, KnowledgeDocumentStatus.PARSED, NOW, NOW.plusSeconds(5),
                NOW.plusSeconds(5), null, null);

        assertThat(parsed.status()).isEqualTo(KnowledgeDocumentStatus.PARSED);
        assertThat(parsed.parsedAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(parsed.parseFailedAt()).isNull();
        assertThat(parsed.parseFailureCode()).isNull();

        KnowledgeDocument failed = restore(DocumentFormat.TEXT, "notes.txt", "text/plain", 10L,
                Sha256Digest.of(DIGEST), CONTENT_KEY, KnowledgeDocumentStatus.PARSE_FAILED, NOW,
                NOW.plusSeconds(5), null, NOW.plusSeconds(5), KnowledgeParseFailureCode.CORRUPTED_DOCUMENT);

        assertThat(failed.status()).isEqualTo(KnowledgeDocumentStatus.PARSE_FAILED);
        assertThat(failed.parsedAt()).isNull();
        assertThat(failed.parseFailedAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(failed.parseFailureCode()).isEqualTo(KnowledgeParseFailureCode.CORRUPTED_DOCUMENT);
    }

    @Test
    void restoreRejectsSnapshotsWhoseStatusAndParseFieldsDisagree() {
        // PARSED 必须带 parsedAt
        assertRestoreRejected(KnowledgeDocumentStatus.PARSED, NOW, NOW, null, null, null);
        // PARSED 不得同时带失败信息
        assertRestoreRejected(KnowledgeDocumentStatus.PARSED, NOW, NOW, NOW, NOW,
                KnowledgeParseFailureCode.PARSER_FAILURE);
        // PARSE_FAILED 必须同时带失败时间与失败码
        assertRestoreRejected(KnowledgeDocumentStatus.PARSE_FAILED, NOW, NOW, null, NOW, null);
        assertRestoreRejected(KnowledgeDocumentStatus.PARSE_FAILED, NOW, NOW, null, null,
                KnowledgeParseFailureCode.PARSER_FAILURE);
        // UPLOADED / PARSING 不得携带任何解析结果字段
        assertRestoreRejected(KnowledgeDocumentStatus.UPLOADED, NOW, NOW, NOW, null, null);
        assertRestoreRejected(KnowledgeDocumentStatus.PARSING, NOW, NOW, null, NOW,
                KnowledgeParseFailureCode.PARSER_FAILURE);
    }

    @Test
    void restoreRejectsParseTimesOutsideTheDocumentWindow() {
        // parsedAt 早于 createdAt
        assertRestoreRejected(KnowledgeDocumentStatus.PARSED, NOW, NOW.plusSeconds(5), NOW.minusSeconds(1),
                null, null);
        // parsedAt 晚于 updatedAt
        assertRestoreRejected(KnowledgeDocumentStatus.PARSED, NOW, NOW.plusSeconds(5), NOW.plusSeconds(6),
                null, null);
    }

    // ---------- 相等性与日志安全 ----------

    @Test
    void equalityIsBasedOnTheId() {
        KnowledgeDocument first = create(DocumentFormat.PDF, FILE_NAME, "application/pdf", 1024L, DIGEST,
                CONTENT_KEY, NOW);
        KnowledgeDocument second = create(DocumentFormat.TEXT, "other.txt", "text/plain", 5L, DIGEST,
                "content-other", NOW.plusSeconds(1));

        assertThat(first).isEqualTo(second);
        assertThat(first).hasSameHashCodeAs(second);
    }

    @Test
    void toStringDoesNotLeakTitleFileNameOrContentKey() {
        KnowledgeDocument document = create(DocumentFormat.PDF, FILE_NAME, "application/pdf", 1024L, DIGEST,
                CONTENT_KEY, NOW);

        assertThat(document.toString())
                .doesNotContain(TITLE)
                .doesNotContain(FILE_NAME)
                .doesNotContain(CONTENT_KEY)
                .contains("UPLOADED")
                .contains(ID.toString());
    }

    @Test
    void aggregateExposesNoRawMutators() {
        // 元数据不可变：没有任何 setter，也没有「直接改状态」的通用入口。
        // 状态只能经由 markParsing / markParsed / markParseFailed 三个领域行为转换。
        assertThat(KnowledgeDocument.class.getMethods())
                .noneMatch(method -> method.getName().startsWith("set"))
                .noneMatch(method -> method.getName().equals("update"))
                .noneMatch(method -> method.getName().equals("changeStatus"))
                .anyMatch(method -> method.getName().equals("markParsing"))
                .anyMatch(method -> method.getName().equals("markParsed"))
                .anyMatch(method -> method.getName().equals("markParseFailed"));
    }

    // ---------- 辅助 ----------

    private static KnowledgeDocument create(DocumentFormat format, String fileName, String mediaType,
            long sizeBytes, String digest, String contentKey, Instant uploadedAt) {

        return KnowledgeDocument.create(ID, DocumentTitle.of(TITLE), OriginalFilename.of(fileName), format,
                mediaType, sizeBytes, digest == null ? null : Sha256Digest.of(digest), contentKey, uploadedAt);
    }

    private static KnowledgeDocument restore(DocumentFormat format, String fileName, String mediaType,
            long sizeBytes, Sha256Digest digest, String contentKey, KnowledgeDocumentStatus status,
            Instant createdAt, Instant updatedAt) {

        return restore(format, fileName, mediaType, sizeBytes, digest, contentKey, status, createdAt, updatedAt,
                null, null, null);
    }

    private static KnowledgeDocument restore(DocumentFormat format, String fileName, String mediaType,
            long sizeBytes, Sha256Digest digest, String contentKey, KnowledgeDocumentStatus status,
            Instant createdAt, Instant updatedAt, Instant parsedAt, Instant parseFailedAt,
            KnowledgeParseFailureCode failureCode) {

        return restore(format, fileName, mediaType, sizeBytes, digest, contentKey, status, createdAt,
                updatedAt, parsedAt, parseFailedAt, failureCode, null, null, null, null, null);
    }

    private static KnowledgeDocument restore(DocumentFormat format, String fileName, String mediaType,
            long sizeBytes, Sha256Digest digest, String contentKey, KnowledgeDocumentStatus status,
            Instant createdAt, Instant updatedAt, Instant parsedAt, Instant parseFailedAt,
            KnowledgeParseFailureCode failureCode, Instant indexStartedAt, Instant indexedAt,
            Instant indexFailedAt, KnowledgeIndexFailureCode indexFailureCode,
            EmbeddingDescriptor embedding) {

        return KnowledgeDocument.restore(ID, TITLE, fileName, format, mediaType, sizeBytes, digest, contentKey,
                status, createdAt, updatedAt, parsedAt, parseFailedAt, failureCode, indexStartedAt, indexedAt,
                indexFailedAt, indexFailureCode, embedding);
    }

    private static void assertRestoreRejected(DocumentFormat format, String fileName, String mediaType,
            long sizeBytes, Sha256Digest digest, String contentKey, KnowledgeDocumentStatus status,
            Instant createdAt, Instant updatedAt) {

        assertError(KnowledgeErrorCode.INVALID_RESTORED_STATE, () -> restore(format, fileName, mediaType,
                sizeBytes, digest, contentKey, status, createdAt, updatedAt));
    }

    private static void assertRestoreRejected(KnowledgeDocumentStatus status, Instant createdAt, Instant updatedAt,
            Instant parsedAt, Instant parseFailedAt, KnowledgeParseFailureCode failureCode) {

        assertError(KnowledgeErrorCode.INVALID_RESTORED_STATE,
                () -> restore(DocumentFormat.TEXT, "notes.txt", "text/plain", 10L, Sha256Digest.of(DIGEST),
                        CONTENT_KEY, status, createdAt, updatedAt, parsedAt, parseFailedAt, failureCode));
    }

    private static void assertError(KnowledgeErrorCode expected, Runnable callable) {
        assertThatThrownBy(callable::run)
                .isInstanceOf(KnowledgeDomainException.class)
                .extracting(thrown -> ((KnowledgeDomainException) thrown).errorCode())
                .isEqualTo(expected);
    }
}
