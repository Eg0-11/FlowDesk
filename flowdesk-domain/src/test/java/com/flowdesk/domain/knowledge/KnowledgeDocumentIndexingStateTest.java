package com.flowdesk.domain.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link KnowledgeDocument} 索引状态机测试（FD-0010）。
 *
 * <p>索引状态机与解析状态机同样是核心不变量：只有它能阻止「两个请求同时索引同一文档」、
 * 「已索引的文档被重复索引」这类会让两批向量互相覆盖的情况。因此这里对每一种
 * <b>状态 × 操作</b>组合都做显式断言。</p>
 */
class KnowledgeDocumentIndexingStateTest {

    private static final KnowledgeDocumentId ID =
            KnowledgeDocumentId.of(UUID.fromString("11111111-2222-3333-4444-555555555555"));

    private static final Instant NOW = Instant.parse("2026-05-01T10:00:00Z");

    private static final String DIGEST = "0123456789abcdef".repeat(4);

    private static final EmbeddingDescriptor DESCRIPTOR = EmbeddingDescriptor.of("dashscope", "text-embedding-v4");

    // ---------- 完整链路 ----------

    @Test
    void theWholeLifecycleRunsFromUploadToIndexed() {
        KnowledgeDocument document = uploaded();
        document.markParsing(NOW.plusSeconds(1));
        document.markParsed(NOW.plusSeconds(2));
        assertThat(document.status()).isEqualTo(KnowledgeDocumentStatus.PARSED);
        assertThat(document.parsedAt()).isEqualTo(NOW.plusSeconds(2));

        document.markIndexing(DESCRIPTOR, NOW.plusSeconds(3));
        assertThat(document.status()).isEqualTo(KnowledgeDocumentStatus.INDEXING);
        assertThat(document.indexStartedAt()).isEqualTo(NOW.plusSeconds(3));
        assertThat(document.embedding()).isEqualTo(DESCRIPTOR);
        assertThat(document.embeddingProvider()).isEqualTo("dashscope");
        assertThat(document.embeddingModel()).isEqualTo("text-embedding-v4");
        assertThat(document.embeddingDimensions()).isEqualTo(1024);
        assertThat(document.parsedAt()).as("进入索引流程后解析结果必须保留").isEqualTo(NOW.plusSeconds(2));

        document.markIndexed(NOW.plusSeconds(4));
        assertThat(document.status()).isEqualTo(KnowledgeDocumentStatus.INDEXED);
        assertThat(document.indexedAt()).isEqualTo(NOW.plusSeconds(4));
        assertThat(document.indexFailedAt()).isNull();
        assertThat(document.indexFailureCode()).isNull();
        assertThat(document.updatedAt()).isEqualTo(NOW.plusSeconds(4));
    }

    @Test
    void aFailedIndexCanBeRetriedAndThenSucceeds() {
        KnowledgeDocument document = parsed();
        document.markIndexing(DESCRIPTOR, NOW.plusSeconds(3));
        document.markIndexFailed(KnowledgeIndexFailureCode.EMBEDDING_PROVIDER_FAILURE, NOW.plusSeconds(4));

        assertThat(document.status()).isEqualTo(KnowledgeDocumentStatus.INDEX_FAILED);
        assertThat(document.indexFailedAt()).isEqualTo(NOW.plusSeconds(4));
        assertThat(document.indexFailureCode())
                .isEqualTo(KnowledgeIndexFailureCode.EMBEDDING_PROVIDER_FAILURE);
        assertThat(document.indexedAt()).isNull();

        // 重试：失败痕迹必须被清空，且解析结果仍然保留
        document.markIndexing(DESCRIPTOR, NOW.plusSeconds(5));
        assertThat(document.indexStartedAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(document.indexFailedAt()).isNull();
        assertThat(document.indexFailureCode()).isNull();
        assertThat(document.parsedAt()).isEqualTo(NOW.plusSeconds(2));

        document.markIndexed(NOW.plusSeconds(6));
        assertThat(document.status()).isEqualTo(KnowledgeDocumentStatus.INDEXED);
        assertThat(document.indexedAt()).isEqualTo(NOW.plusSeconds(6));
        assertThat(document.indexFailureCode()).isNull();
    }

    @Test
    void anIndexedDocumentCannotBeClaimedAgain() {
        KnowledgeDocument document = indexed();

        assertError(KnowledgeErrorCode.ILLEGAL_STATUS_TRANSITION,
                () -> document.markIndexing(DESCRIPTOR, NOW.plusSeconds(9)));
        assertThat(document.status())
                .as("失败的转换不能留下半个状态").isEqualTo(KnowledgeDocumentStatus.INDEXED);
        assertThat(document.indexedAt()).isEqualTo(NOW.plusSeconds(4));
    }

    // ---------- 非法转换 ----------

    @Test
    void documentsThatAreNotParsedCannotBeIndexed() {
        assertError(KnowledgeErrorCode.ILLEGAL_STATUS_TRANSITION,
                () -> uploaded().markIndexing(DESCRIPTOR, NOW.plusSeconds(1)));

        KnowledgeDocument parsing = uploaded();
        parsing.markParsing(NOW.plusSeconds(1));
        assertError(KnowledgeErrorCode.ILLEGAL_STATUS_TRANSITION,
                () -> parsing.markIndexing(DESCRIPTOR, NOW.plusSeconds(2)));

        KnowledgeDocument parseFailed = uploaded();
        parseFailed.markParsing(NOW.plusSeconds(1));
        parseFailed.markParseFailed(KnowledgeParseFailureCode.CORRUPTED_DOCUMENT, NOW.plusSeconds(2));
        assertError(KnowledgeErrorCode.ILLEGAL_STATUS_TRANSITION,
                () -> parseFailed.markIndexing(DESCRIPTOR, NOW.plusSeconds(3)));

        // 领取中的文档不能被第二个请求重复领取（否则两批向量会互相覆盖）
        KnowledgeDocument indexing = parsed();
        indexing.markIndexing(DESCRIPTOR, NOW.plusSeconds(3));
        assertError(KnowledgeErrorCode.ILLEGAL_STATUS_TRANSITION,
                () -> indexing.markIndexing(DESCRIPTOR, NOW.plusSeconds(4)));
        assertThat(indexing.status()).isEqualTo(KnowledgeDocumentStatus.INDEXING);
    }

    @Test
    void completionAndFailureAreRejectedOutsideIndexing() {
        assertError(KnowledgeErrorCode.ILLEGAL_STATUS_TRANSITION, () -> uploaded().markIndexed(NOW));
        assertError(KnowledgeErrorCode.ILLEGAL_STATUS_TRANSITION, () -> parsed().markIndexed(NOW.plusSeconds(3)));
        assertError(KnowledgeErrorCode.ILLEGAL_STATUS_TRANSITION, () -> indexed().markIndexed(NOW.plusSeconds(9)));
        assertError(KnowledgeErrorCode.ILLEGAL_STATUS_TRANSITION,
                () -> uploaded().markIndexFailed(KnowledgeIndexFailureCode.VECTOR_STORAGE_FAILURE, NOW));
        assertError(KnowledgeErrorCode.ILLEGAL_STATUS_TRANSITION,
                () -> parsed().markIndexFailed(KnowledgeIndexFailureCode.VECTOR_STORAGE_FAILURE, NOW));
        assertError(KnowledgeErrorCode.ILLEGAL_STATUS_TRANSITION,
                () -> indexed().markIndexFailed(KnowledgeIndexFailureCode.VECTOR_STORAGE_FAILURE,
                        NOW.plusSeconds(9)));
    }

    @Test
    void aMissingDescriptorOrFailureCodeIsRejected() {
        KnowledgeDocument document = parsed();

        assertError(KnowledgeErrorCode.INVALID_EMBEDDING_DESCRIPTOR,
                () -> document.markIndexing(null, NOW.plusSeconds(3)));

        document.markIndexing(DESCRIPTOR, NOW.plusSeconds(3));
        assertError(KnowledgeErrorCode.INVALID_INDEX_FAILURE_CODE,
                () -> document.markIndexFailed(null, NOW.plusSeconds(4)));
        assertThat(document.status())
                .as("失败码为空时不能改变状态").isEqualTo(KnowledgeDocumentStatus.INDEXING);
    }

    @Test
    void transitionsRejectNullAndBackwardsTimes() {
        KnowledgeDocument document = parsed();

        assertError(KnowledgeErrorCode.INVALID_TIMELINE, () -> document.markIndexing(DESCRIPTOR, null));
        assertError(KnowledgeErrorCode.INVALID_TIMELINE,
                () -> document.markIndexing(DESCRIPTOR, NOW.minusSeconds(1)));

        document.markIndexing(DESCRIPTOR, NOW.plusSeconds(3));
        assertError(KnowledgeErrorCode.INVALID_TIMELINE, () -> document.markIndexed(null));
        assertError(KnowledgeErrorCode.INVALID_TIMELINE, () -> document.markIndexed(NOW.plusSeconds(2)));
        assertError(KnowledgeErrorCode.INVALID_TIMELINE, () -> document.markIndexFailed(
                KnowledgeIndexFailureCode.CHUNK_DATA_INVALID, NOW.plusSeconds(2)));
        assertThat(document.status()).isEqualTo(KnowledgeDocumentStatus.INDEXING);
    }

    @Test
    void equalTimesAreAcceptedAtEveryStep() {
        // 相等时间合法：同一毫秒内完成领取与完成是完全可能的
        KnowledgeDocument document = uploaded();
        document.markParsing(NOW);
        document.markParsed(NOW);
        document.markIndexing(DESCRIPTOR, NOW);
        document.markIndexed(NOW);

        assertThat(document.status()).isEqualTo(KnowledgeDocumentStatus.INDEXED);
        assertThat(document.indexStartedAt()).isEqualTo(NOW);
        assertThat(document.indexedAt()).isEqualTo(NOW);
    }

    // ---------- 恢复：字段状态矩阵 ----------

    @Test
    void restoreAcceptsEveryConsistentSnapshotInTheIndexLifecycle() {
        KnowledgeDocument indexing = restore(KnowledgeDocumentStatus.INDEXING, NOW, NOW.plusSeconds(3),
                NOW.plusSeconds(2), null, null, NOW.plusSeconds(3), null, null, null, DESCRIPTOR);
        assertThat(indexing.status()).isEqualTo(KnowledgeDocumentStatus.INDEXING);
        assertThat(indexing.embeddingDimensions()).isEqualTo(1024);

        KnowledgeDocument indexed = restore(KnowledgeDocumentStatus.INDEXED, NOW, NOW.plusSeconds(4),
                NOW.plusSeconds(2), null, null, NOW.plusSeconds(3), NOW.plusSeconds(4), null, null, DESCRIPTOR);
        assertThat(indexed.status()).isEqualTo(KnowledgeDocumentStatus.INDEXED);
        assertThat(indexed.indexedAt()).isEqualTo(NOW.plusSeconds(4));

        KnowledgeDocument failed = restore(KnowledgeDocumentStatus.INDEX_FAILED, NOW, NOW.plusSeconds(4),
                NOW.plusSeconds(2), null, null, NOW.plusSeconds(3), null, NOW.plusSeconds(4),
                KnowledgeIndexFailureCode.VECTOR_STORAGE_FAILURE, DESCRIPTOR);
        assertThat(failed.status()).isEqualTo(KnowledgeDocumentStatus.INDEX_FAILED);
        assertThat(failed.indexFailureCode()).isEqualTo(KnowledgeIndexFailureCode.VECTOR_STORAGE_FAILURE);

        // 相等边界：领取时间等于解析完成时间、完成时间等于领取时间
        assertThat(restore(KnowledgeDocumentStatus.INDEXING, NOW, NOW, NOW, null, null, NOW, null, null, null,
                DESCRIPTOR).indexStartedAt()).isEqualTo(NOW);
        assertThat(restore(KnowledgeDocumentStatus.INDEXED, NOW, NOW, NOW, null, null, NOW, NOW, null, null,
                DESCRIPTOR).indexedAt()).isEqualTo(NOW);
    }

    @Test
    void restoreRejectsIndexStatesWithMissingFields() {
        // INDEXING 缺索引开始时间
        assertRestoreRejected(KnowledgeDocumentStatus.INDEXING, NOW, NOW.plusSeconds(3), NOW.plusSeconds(2),
                null, null, null, null, null, null, DESCRIPTOR);
        // INDEXING 缺描述符
        assertRestoreRejected(KnowledgeDocumentStatus.INDEXING, NOW, NOW.plusSeconds(3), NOW.plusSeconds(2),
                null, null, NOW.plusSeconds(3), null, null, null, null);
        // INDEXING 缺解析完成时间
        assertRestoreRejected(KnowledgeDocumentStatus.INDEXING, NOW, NOW.plusSeconds(3), null,
                null, null, NOW.plusSeconds(3), null, null, null, DESCRIPTOR);
        // INDEXED 缺完成时间
        assertRestoreRejected(KnowledgeDocumentStatus.INDEXED, NOW, NOW.plusSeconds(4), NOW.plusSeconds(2),
                null, null, NOW.plusSeconds(3), null, null, null, DESCRIPTOR);
        // INDEX_FAILED 缺失败码
        assertRestoreRejected(KnowledgeDocumentStatus.INDEX_FAILED, NOW, NOW.plusSeconds(4), NOW.plusSeconds(2),
                null, null, NOW.plusSeconds(3), null, NOW.plusSeconds(4), null, DESCRIPTOR);
        // INDEX_FAILED 缺失败时间
        assertRestoreRejected(KnowledgeDocumentStatus.INDEX_FAILED, NOW, NOW.plusSeconds(4), NOW.plusSeconds(2),
                null, null, NOW.plusSeconds(3), null, null,
                KnowledgeIndexFailureCode.CHUNK_DATA_INVALID, DESCRIPTOR);
    }

    @Test
    void restoreRejectsStatesCarryingFieldsThatDoNotBelongToThem() {
        // INDEXING 不得带完成/失败字段
        assertRestoreRejected(KnowledgeDocumentStatus.INDEXING, NOW, NOW.plusSeconds(4), NOW.plusSeconds(2),
                null, null, NOW.plusSeconds(3), NOW.plusSeconds(4), null, null, DESCRIPTOR);
        assertRestoreRejected(KnowledgeDocumentStatus.INDEXING, NOW, NOW.plusSeconds(4), NOW.plusSeconds(2),
                null, null, NOW.plusSeconds(3), null, NOW.plusSeconds(4),
                KnowledgeIndexFailureCode.VECTOR_STORAGE_FAILURE, DESCRIPTOR);
        // INDEXED 不得带失败字段
        assertRestoreRejected(KnowledgeDocumentStatus.INDEXED, NOW, NOW.plusSeconds(4), NOW.plusSeconds(2),
                null, null, NOW.plusSeconds(3), NOW.plusSeconds(4), NOW.plusSeconds(4),
                KnowledgeIndexFailureCode.VECTOR_STORAGE_FAILURE, DESCRIPTOR);
        // INDEX_FAILED 不得带完成时间
        assertRestoreRejected(KnowledgeDocumentStatus.INDEX_FAILED, NOW, NOW.plusSeconds(4), NOW.plusSeconds(2),
                null, null, NOW.plusSeconds(3), NOW.plusSeconds(4), NOW.plusSeconds(4),
                KnowledgeIndexFailureCode.VECTOR_STORAGE_FAILURE, DESCRIPTOR);
        // 非索引状态不得带任何索引字段
        assertRestoreRejected(KnowledgeDocumentStatus.UPLOADED, NOW, NOW, null, null, null,
                NOW, null, null, null, DESCRIPTOR);
        assertRestoreRejected(KnowledgeDocumentStatus.PARSED, NOW, NOW.plusSeconds(3), NOW.plusSeconds(3),
                null, null, NOW.plusSeconds(3), null, null, null, DESCRIPTOR);
        assertRestoreRejected(KnowledgeDocumentStatus.PARSING, NOW, NOW, null, null, null,
                null, null, null, null, DESCRIPTOR);
    }

    @Test
    void restoreRejectsIndexStatesCarryingParseFailureFields() {
        // 「解析失败」与「已索引」不可能同时成立
        assertRestoreRejected(KnowledgeDocumentStatus.INDEXING, NOW, NOW.plusSeconds(3), null,
                NOW.plusSeconds(2), KnowledgeParseFailureCode.CORRUPTED_DOCUMENT, NOW.plusSeconds(3),
                null, null, null, DESCRIPTOR);
        assertRestoreRejected(KnowledgeDocumentStatus.INDEXED, NOW, NOW.plusSeconds(4), null,
                NOW.plusSeconds(2), KnowledgeParseFailureCode.CORRUPTED_DOCUMENT, NOW.plusSeconds(3),
                NOW.plusSeconds(4), null, null, DESCRIPTOR);
    }

    @Test
    void restoreRejectsIndexTimesOutsideTheirAllowedWindows() {
        // indexStartedAt 早于 parsedAt
        assertRestoreRejected(KnowledgeDocumentStatus.INDEXING, NOW, NOW.plusSeconds(5), NOW.plusSeconds(3),
                null, null, NOW.plusSeconds(2), null, null, null, DESCRIPTOR);
        // indexStartedAt 早于 createdAt
        assertRestoreRejected(KnowledgeDocumentStatus.INDEXING, NOW, NOW.plusSeconds(5), NOW,
                null, null, NOW.minusSeconds(1), null, null, null, DESCRIPTOR);
        // indexStartedAt 晚于 updatedAt
        assertRestoreRejected(KnowledgeDocumentStatus.INDEXING, NOW, NOW.plusSeconds(5), NOW,
                null, null, NOW.plusSeconds(6), null, null, null, DESCRIPTOR);
        // indexedAt 早于 indexStartedAt
        assertRestoreRejected(KnowledgeDocumentStatus.INDEXED, NOW, NOW.plusSeconds(5), NOW,
                null, null, NOW.plusSeconds(3), NOW.plusSeconds(2), null, null, DESCRIPTOR);
        // indexFailedAt 早于 indexStartedAt
        assertRestoreRejected(KnowledgeDocumentStatus.INDEX_FAILED, NOW, NOW.plusSeconds(5), NOW,
                null, null, NOW.plusSeconds(3), null, NOW.plusSeconds(2),
                KnowledgeIndexFailureCode.VECTOR_STORAGE_FAILURE, DESCRIPTOR);
    }

    @Test
    void everyRestoredSnapshotSatisfiesTheWholeTimelineChain() {
        for (KnowledgeDocumentStatus status : KnowledgeDocumentStatus.values()) {
            KnowledgeDocument snapshot = consistentSnapshot(status);
            assertThat(snapshot.createdAt()).isNotNull();
            assertThat(snapshot.updatedAt()).isNotNull();
            assertThat(snapshot.createdAt()).isBeforeOrEqualTo(snapshot.updatedAt());
            if (snapshot.parsedAt() != null) {
                assertThat(snapshot.parsedAt()).isBetween(snapshot.createdAt(), snapshot.updatedAt());
            }
            if (snapshot.indexStartedAt() != null) {
                assertThat(snapshot.indexStartedAt()).isBetween(snapshot.createdAt(), snapshot.updatedAt());
                if (snapshot.parsedAt() != null) {
                    assertThat(snapshot.indexStartedAt()).isAfterOrEqualTo(snapshot.parsedAt());
                }
            }
        }
    }

    // ---------- 辅助 ----------

    private static KnowledgeDocument consistentSnapshot(KnowledgeDocumentStatus status) {
        return switch (status) {
            case UPLOADED -> restore(KnowledgeDocumentStatus.UPLOADED, NOW, NOW,
                    null, null, null, null, null, null, null, null);
            case PARSING -> restore(KnowledgeDocumentStatus.PARSING, NOW, NOW.plusSeconds(1),
                    null, null, null, null, null, null, null, null);
            case PARSED -> restore(KnowledgeDocumentStatus.PARSED, NOW, NOW.plusSeconds(2),
                    NOW.plusSeconds(2), null, null, null, null, null, null, null);
            case PARSE_FAILED -> restore(KnowledgeDocumentStatus.PARSE_FAILED, NOW, NOW.plusSeconds(2),
                    null, NOW.plusSeconds(2), KnowledgeParseFailureCode.PARSER_FAILURE,
                    null, null, null, null, null);
            case INDEXING -> restore(KnowledgeDocumentStatus.INDEXING, NOW, NOW.plusSeconds(3),
                    NOW.plusSeconds(2), null, null, NOW.plusSeconds(3), null, null, null, DESCRIPTOR);
            case INDEXED -> restore(KnowledgeDocumentStatus.INDEXED, NOW, NOW.plusSeconds(4),
                    NOW.plusSeconds(2), null, null, NOW.plusSeconds(3), NOW.plusSeconds(4), null, null,
                    DESCRIPTOR);
            case INDEX_FAILED -> restore(KnowledgeDocumentStatus.INDEX_FAILED, NOW, NOW.plusSeconds(4),
                    NOW.plusSeconds(2), null, null, NOW.plusSeconds(3), null, NOW.plusSeconds(4),
                    KnowledgeIndexFailureCode.EMBEDDING_PROVIDER_FAILURE, DESCRIPTOR);
        };
    }

    private static KnowledgeDocument restore(KnowledgeDocumentStatus status, Instant createdAt,
            Instant updatedAt, Instant parsedAt, Instant parseFailedAt,
            KnowledgeParseFailureCode parseFailureCode, Instant indexStartedAt, Instant indexedAt,
            Instant indexFailedAt, KnowledgeIndexFailureCode indexFailureCode,
            EmbeddingDescriptor embedding) {

        return KnowledgeDocument.restore(ID, "季度运维报告", "report.pdf", DocumentFormat.PDF,
                "application/pdf", 1024L, Sha256Digest.of(DIGEST), "content-abc123", status, createdAt,
                updatedAt, parsedAt, parseFailedAt, parseFailureCode, indexStartedAt, indexedAt,
                indexFailedAt, indexFailureCode, embedding);
    }

    private static void assertRestoreRejected(KnowledgeDocumentStatus status, Instant createdAt,
            Instant updatedAt, Instant parsedAt, Instant parseFailedAt,
            KnowledgeParseFailureCode parseFailureCode, Instant indexStartedAt, Instant indexedAt,
            Instant indexFailedAt, KnowledgeIndexFailureCode indexFailureCode,
            EmbeddingDescriptor embedding) {

        assertError(KnowledgeErrorCode.INVALID_RESTORED_STATE,
                () -> restore(status, createdAt, updatedAt, parsedAt, parseFailedAt, parseFailureCode,
                        indexStartedAt, indexedAt, indexFailedAt, indexFailureCode, embedding));
    }

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

    private static KnowledgeDocument indexed() {
        KnowledgeDocument document = parsed();
        document.markIndexing(DESCRIPTOR, NOW.plusSeconds(3));
        document.markIndexed(NOW.plusSeconds(4));
        return document;
    }

    private static void assertError(KnowledgeErrorCode expected, Runnable callable) {
        assertThatThrownBy(callable::run)
                .isInstanceOf(KnowledgeDomainException.class)
                .extracting(thrown -> ((KnowledgeDomainException) thrown).errorCode())
                .isEqualTo(expected);
    }
}
