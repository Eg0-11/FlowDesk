package com.flowdesk.domain.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link KnowledgeDocument} 解析状态机测试（FD-0009）。
 *
 * <p>状态机是解析链路的核心不变量：只有它能阻止「两个请求同时解析同一文档」、
 * 「已解析的文档被重复解析」这类会互相覆盖切片的情况。因此这里对每一种
 * <b>状态 × 操作</b>组合都做显式断言，而不是只测happy path。</p>
 */
class KnowledgeDocumentLifecycleTest {

    private static final KnowledgeDocumentId ID =
            KnowledgeDocumentId.of(UUID.fromString("11111111-2222-3333-4444-555555555555"));

    private static final Instant NOW = Instant.parse("2026-05-01T10:00:00Z");

    private static final String DIGEST = "0123456789abcdef".repeat(4);

    // ---------- 领取 ----------

    @Test
    void claimMovesUploadedToParsingAndAdvancesUpdatedAt() {
        KnowledgeDocument document = uploaded();

        Instant claimedAt = NOW.plusSeconds(5);
        document.markParsing(claimedAt);

        assertThat(document.status()).isEqualTo(KnowledgeDocumentStatus.PARSING);
        assertThat(document.updatedAt()).isEqualTo(claimedAt);
        assertThat(document.createdAt()).as("领取不改变创建时间").isEqualTo(NOW);
        assertThat(document.parsedAt()).isNull();
        assertThat(document.parseFailedAt()).isNull();
        assertThat(document.parseFailureCode()).isNull();
    }

    @Test
    void claimAcceptsTheExactCurrentUpdatedAtAsBoundary() {
        KnowledgeDocument document = uploaded();

        document.markParsing(NOW);

        assertThat(document.updatedAt()).as("等于当前更新时间是允许的边界，不算时间倒流").isEqualTo(NOW);
    }

    @Test
    void claimRejectsNullAndBackwardsTimes() {
        assertError(KnowledgeErrorCode.INVALID_TIMELINE, () -> uploaded().markParsing(null));
        assertError(KnowledgeErrorCode.INVALID_TIMELINE, () -> uploaded().markParsing(NOW.minusSeconds(1)));
    }

    @Test
    void claimIsRejectedWhileAlreadyParsing() {
        KnowledgeDocument document = uploaded();
        document.markParsing(NOW.plusSeconds(1));

        // 同一文档不允许被两个请求同时领取：否则两份切片会互相覆盖
        assertError(KnowledgeErrorCode.ILLEGAL_STATUS_TRANSITION,
                () -> document.markParsing(NOW.plusSeconds(2)));
        assertThat(document.status()).isEqualTo(KnowledgeDocumentStatus.PARSING);
    }

    @Test
    void claimIsRejectedAfterSuccessfulParsing() {
        KnowledgeDocument document = parsed();

        assertError(KnowledgeErrorCode.ILLEGAL_STATUS_TRANSITION, () -> document.markParsing(NOW.plusSeconds(9)));
        assertThat(document.status()).isEqualTo(KnowledgeDocumentStatus.PARSED);
    }

    @Test
    void claimClearsTheTraceOfAPreviousFailure() {
        KnowledgeDocument document = failed();

        document.markParsing(NOW.plusSeconds(10));

        assertThat(document.status()).isEqualTo(KnowledgeDocumentStatus.PARSING);
        assertThat(document.parseFailedAt()).as("重新领取必须清掉上一次的失败痕迹").isNull();
        assertThat(document.parseFailureCode()).isNull();
    }

    // ---------- 完成 ----------

    @Test
    void completionMovesParsingToParsedAndRecordsParsedAt() {
        KnowledgeDocument document = uploaded();
        document.markParsing(NOW.plusSeconds(1));

        Instant parsedAt = NOW.plusSeconds(7);
        document.markParsed(parsedAt);

        assertThat(document.status()).isEqualTo(KnowledgeDocumentStatus.PARSED);
        assertThat(document.parsedAt()).isEqualTo(parsedAt);
        assertThat(document.updatedAt()).isEqualTo(parsedAt);
        assertThat(document.parseFailedAt()).isNull();
        assertThat(document.parseFailureCode()).isNull();
    }

    @Test
    void completionIsRejectedOutsideParsing() {
        assertError(KnowledgeErrorCode.ILLEGAL_STATUS_TRANSITION, () -> uploaded().markParsed(NOW));
        assertError(KnowledgeErrorCode.ILLEGAL_STATUS_TRANSITION, () -> parsed().markParsed(NOW.plusSeconds(9)));
        assertError(KnowledgeErrorCode.ILLEGAL_STATUS_TRANSITION, () -> failed().markParsed(NOW.plusSeconds(9)));
    }

    @Test
    void completionRejectsNullAndBackwardsTimes() {
        KnowledgeDocument document = uploaded();
        document.markParsing(NOW.plusSeconds(5));

        assertError(KnowledgeErrorCode.INVALID_TIMELINE, () -> document.markParsed(null));
        assertError(KnowledgeErrorCode.INVALID_TIMELINE, () -> document.markParsed(NOW.plusSeconds(4)));
        assertThat(document.status()).as("失败的转换不能留下半个状态").isEqualTo(KnowledgeDocumentStatus.PARSING);
    }

    // ---------- 失败 ----------

    @Test
    void failureMovesParsingToParseFailedAndKeepsOnlyTheStableCode() {
        KnowledgeDocument document = uploaded();
        document.markParsing(NOW.plusSeconds(1));

        Instant failedAt = NOW.plusSeconds(3);
        document.markParseFailed(KnowledgeParseFailureCode.CORRUPTED_DOCUMENT, failedAt);

        assertThat(document.status()).isEqualTo(KnowledgeDocumentStatus.PARSE_FAILED);
        assertThat(document.parseFailedAt()).isEqualTo(failedAt);
        assertThat(document.parseFailureCode()).isEqualTo(KnowledgeParseFailureCode.CORRUPTED_DOCUMENT);
        assertThat(document.parsedAt()).isNull();
        assertThat(document.updatedAt()).isEqualTo(failedAt);
    }

    @Test
    void failureRejectsMissingCode() {
        KnowledgeDocument document = uploaded();
        document.markParsing(NOW.plusSeconds(1));

        assertError(KnowledgeErrorCode.INVALID_PARSE_FAILURE_CODE,
                () -> document.markParseFailed(null, NOW.plusSeconds(2)));
        assertThat(document.status()).isEqualTo(KnowledgeDocumentStatus.PARSING);
    }

    @Test
    void failureIsRejectedOutsideParsing() {
        assertError(KnowledgeErrorCode.ILLEGAL_STATUS_TRANSITION,
                () -> uploaded().markParseFailed(KnowledgeParseFailureCode.PARSER_FAILURE, NOW));
        assertError(KnowledgeErrorCode.ILLEGAL_STATUS_TRANSITION,
                () -> parsed().markParseFailed(KnowledgeParseFailureCode.PARSER_FAILURE, NOW.plusSeconds(9)));
    }

    @Test
    void failureRejectsBackwardsTime() {
        KnowledgeDocument document = uploaded();
        document.markParsing(NOW.plusSeconds(5));

        assertError(KnowledgeErrorCode.INVALID_TIMELINE,
                () -> document.markParseFailed(KnowledgeParseFailureCode.PARSER_FAILURE, NOW.plusSeconds(4)));
    }

    // ---------- 完整生命周期 ----------

    @Test
    void retryAfterFailureEndsInParsedWithoutLeftoverFailureState() {
        KnowledgeDocument document = failed();
        assertThat(document.parseFailureCode()).isEqualTo(KnowledgeParseFailureCode.CORRUPTED_DOCUMENT);

        document.markParsing(NOW.plusSeconds(20));
        document.markParsed(NOW.plusSeconds(21));

        assertThat(document.status()).isEqualTo(KnowledgeDocumentStatus.PARSED);
        assertThat(document.parsedAt()).isEqualTo(NOW.plusSeconds(21));
        assertThat(document.parseFailedAt()).isNull();
        assertThat(document.parseFailureCode()).isNull();
    }

    @Test
    void everyTransitionChangesTheStatusSoTheVersionBumpIsMeaningful() {
        KnowledgeDocument document = uploaded();
        KnowledgeDocumentStatus first = document.status();
        document.markParsing(NOW.plusSeconds(1));
        KnowledgeDocumentStatus second = document.status();
        document.markParseFailed(KnowledgeParseFailureCode.TOO_MANY_CHUNKS, NOW.plusSeconds(2));
        KnowledgeDocumentStatus third = document.status();

        assertThat(first).isEqualTo(KnowledgeDocumentStatus.UPLOADED);
        assertThat(second).isEqualTo(KnowledgeDocumentStatus.PARSING);
        assertThat(third).isEqualTo(KnowledgeDocumentStatus.PARSE_FAILED);
        assertThat(document.updatedAt()).isEqualTo(NOW.plusSeconds(2));
    }

    @Test
    void transitionsKeepIdentityEquality() {
        KnowledgeDocument document = uploaded();
        KnowledgeDocument sameId = uploaded();

        document.markParsing(NOW.plusSeconds(1));
        document.markParsed(NOW.plusSeconds(2));

        assertThat(document).isEqualTo(sameId).hasSameHashCodeAs(sameId);
    }

    @Test
    void toStringReflectsTheCurrentStatusWithoutLeakingMetadata() {
        KnowledgeDocument document = failed();

        assertThat(document.toString())
                .contains("PARSE_FAILED")
                .contains(ID.toString())
                .doesNotContain("季度运维报告")
                .doesNotContain("report.pdf")
                .doesNotContain("content-abc123");
    }

    // ---------- 辅助 ----------

    private static KnowledgeDocument uploaded() {
        return KnowledgeDocument.create(ID, DocumentTitle.of("季度运维报告"), OriginalFilename.of("report.pdf"),
                DocumentFormat.PDF, "application/pdf", 1024L, Sha256Digest.of(DIGEST), "content-abc123", NOW);
    }

    private static KnowledgeDocument parsed() {
        KnowledgeDocument document = uploaded();
        document.markParsing(NOW.plusSeconds(1));
        document.markParsed(NOW.plusSeconds(2));
        return document;
    }

    private static KnowledgeDocument failed() {
        KnowledgeDocument document = uploaded();
        document.markParsing(NOW.plusSeconds(1));
        document.markParseFailed(KnowledgeParseFailureCode.CORRUPTED_DOCUMENT, NOW.plusSeconds(2));
        return document;
    }

    private static void assertError(KnowledgeErrorCode expected, Runnable callable) {
        assertThatThrownBy(callable::run)
                .isInstanceOf(KnowledgeDomainException.class)
                .extracting(thrown -> ((KnowledgeDomainException) thrown).errorCode())
                .isEqualTo(expected);
    }
}
