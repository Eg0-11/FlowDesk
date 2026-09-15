package com.flowdesk.application.knowledge;

import static com.flowdesk.application.knowledge.KnowledgeTestSupport.DOCUMENT_ID;
import static com.flowdesk.application.knowledge.KnowledgeTestSupport.UPLOADED_AT;
import static com.flowdesk.application.knowledge.KnowledgeTestSupport.assertApplicationError;
import static com.flowdesk.application.knowledge.KnowledgeTestSupport.capture;
import static org.assertj.core.api.Assertions.assertThat;

import com.flowdesk.application.knowledge.command.IndexKnowledgeDocumentCommand;
import com.flowdesk.application.knowledge.index.DocumentIndexingException;
import com.flowdesk.application.knowledge.service.KnowledgeDocumentIndexingService;
import com.flowdesk.application.knowledge.view.IndexedDocumentView;
import com.flowdesk.domain.knowledge.DocumentFormat;
import com.flowdesk.domain.knowledge.DocumentTitle;
import com.flowdesk.domain.knowledge.EmbeddingDescriptor;
import com.flowdesk.domain.knowledge.KnowledgeDocument;
import com.flowdesk.domain.knowledge.KnowledgeDocumentChunkEmbedding;
import com.flowdesk.domain.knowledge.KnowledgeDocumentStatus;
import com.flowdesk.domain.knowledge.KnowledgeIndexFailureCode;
import com.flowdesk.domain.knowledge.KnowledgeParseFailureCode;
import com.flowdesk.domain.knowledge.OriginalFilename;
import com.flowdesk.domain.knowledge.Sha256Digest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link KnowledgeDocumentIndexingService} 单元测试（FD-0010）。
 *
 * <p>覆盖五件事：命令与状态前置条件、分批与顺序、响应校验、原子完成的调用时机，
 * 以及失败补偿的语义（哪些失败该写 {@code INDEX_FAILED}、哪些不该）。</p>
 */
class KnowledgeDocumentIndexingServiceTest {

    private static final String CONTENT_KEY = "kdoc-abc123";

    private static final String DIGEST = "0123456789abcdef".repeat(4);

    private static final EmbeddingDescriptor DESCRIPTOR =
            EmbeddingDescriptor.of("dashscope", "text-embedding-v4");

    private RecordingKnowledgePorts.RecordingDocumentRepository repository;

    private RecordingIndexingPorts.RecordingChunkPages chunkPages;

    private RecordingIndexingPorts.RecordingEmbeddingPort embeddingPort;

    private RecordingIndexingPorts.RecordingEmbeddingStore embeddingStore;

    private RecordingKnowledgePorts.RecordingTimeProvider clock;

    private KnowledgeDocumentIndexingService service;

    @BeforeEach
    void setUp() {
        this.repository = new RecordingKnowledgePorts.RecordingDocumentRepository();
        this.chunkPages = new RecordingIndexingPorts.RecordingChunkPages();
        this.embeddingPort = new RecordingIndexingPorts.RecordingEmbeddingPort();
        this.embeddingStore = new RecordingIndexingPorts.RecordingEmbeddingStore();
        this.clock = new RecordingKnowledgePorts.RecordingTimeProvider(UPLOADED_AT.plusSeconds(10),
                Duration.ofSeconds(1));
        this.service = new KnowledgeDocumentIndexingService(this.repository, this.chunkPages,
                this.embeddingPort, this.embeddingStore, this.clock, true, DESCRIPTOR, 10);
    }

    // ---------- ① 命令与前置条件 ----------

    @Test
    void rejectsInvalidCommandsBeforeTouchingAnyPort() {
        assertApplicationError(() -> this.service.index(null),
                KnowledgeApplicationErrorCode.INVALID_INDEX_COMMAND);
        assertApplicationError(() -> this.service.index(new IndexKnowledgeDocumentCommand(null, 0L)),
                KnowledgeApplicationErrorCode.INVALID_INDEX_COMMAND);
        assertApplicationError(() -> this.service.index(new IndexKnowledgeDocumentCommand(DOCUMENT_ID, -1L)),
                KnowledgeApplicationErrorCode.INVALID_INDEX_COMMAND);

        assertThat(this.repository.calls()).isEmpty();
        assertThat(this.embeddingPort.batchCount()).isZero();
        assertThat(this.embeddingStore.calls()).isZero();
    }

    @Test
    void rejectsAStaleVersionBeforeCallingTheProvider() {
        parsedDocument(3L);

        assertApplicationError(() -> this.service.index(command(2L)),
                KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_VERSION_CONFLICT);

        assertThat(this.repository.updateCalls()).as("版本过期时不得发生任何写入").isZero();
        assertThat(this.embeddingPort.batchCount()).isZero();
        assertThat(this.embeddingStore.calls()).isZero();
    }

    @Test
    void rejectsDocumentsThatAreNotParsedOrIndexFailedWithoutCallingTheProvider() {
        for (KnowledgeDocumentStatus status : List.of(KnowledgeDocumentStatus.UPLOADED,
                KnowledgeDocumentStatus.PARSING, KnowledgeDocumentStatus.PARSE_FAILED,
                KnowledgeDocumentStatus.INDEXING, KnowledgeDocumentStatus.INDEXED)) {

            setUp();
            this.repository.willFind(documentIn(status), 1L);

            assertApplicationError(() -> this.service.index(command(1L)),
                    KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_NOT_INDEXABLE);

            assertThat(this.embeddingPort.batchCount()).as(status + " 不得触发模型调用").isZero();
            assertThat(this.repository.updateCalls()).isZero();
        }
    }

    @Test
    void disabledEmbeddingDoesNotReadWriteOrCallTheModel() {
        KnowledgeDocumentIndexingService disabled = new KnowledgeDocumentIndexingService(this.repository,
                this.chunkPages, this.embeddingPort, this.embeddingStore, this.clock, false, DESCRIPTOR, 10);
        parsedDocument(0L);

        assertApplicationError(() -> disabled.index(command(0L)),
                KnowledgeApplicationErrorCode.KNOWLEDGE_EMBEDDING_DISABLED);

        assertThat(this.repository.calls()).as("关闭状态下连读都不应该发生").isEmpty();
        assertThat(this.embeddingPort.batchCount()).isZero();
        assertThat(this.embeddingStore.calls()).isZero();
    }

    // ---------- ② 分批与顺序 ----------

    @Test
    void splitsTwentyThreeChunksIntoBatchesOfTenTenAndThree() {
        parsedDocument(0L);
        this.chunkPages.serve(RecordingIndexingPorts.chunks(DOCUMENT_ID, 23));

        this.service.index(command(0L));

        assertThat(this.embeddingPort.batchCount()).isEqualTo(3);
        assertThat(this.embeddingPort.batches()).extracting(List::size).containsExactly(10, 10, 3);
        assertThat(this.chunkPages.reads()).containsExactly("0:10", "10:10", "20:3");
        assertThat(this.embeddingStore.received()).hasSize(23);
    }

    @Test
    void keepsRequestAndResponseOrderAlignedWithChunkIndexes() {
        parsedDocument(0L);
        this.chunkPages.serve(RecordingIndexingPorts.chunks(DOCUMENT_ID, 23));

        this.service.index(command(0L));

        // 请求文本必须严格按 chunkIndex 顺序
        assertThat(this.embeddingPort.allRequestedTexts())
                .containsExactlyElementsOf(expectedTexts(23));
        // 向量与切片一一对应：第 i 个向量的 chunkIndex 必须是 i，摘要必须来自对应切片内容
        List<KnowledgeDocumentChunkEmbedding> received = this.embeddingStore.received();
        for (int index = 0; index < received.size(); index++) {
            assertThat(received.get(index).chunkIndex()).isEqualTo(index);
            assertThat(received.get(index).chunkSha256().value())
                    .isEqualTo(RecordingIndexingPorts.sha256Hex("chunk-" + index));
        }
    }

    @Test
    void everyBatchCarriesTheConfiguredDescriptor() {
        parsedDocument(0L);
        this.chunkPages.serve(RecordingIndexingPorts.chunks(DOCUMENT_ID, 12));

        this.service.index(command(0L));

        assertThat(this.embeddingPort.descriptors()).hasSize(2)
                .allSatisfy(descriptor -> assertThat(descriptor).isEqualTo(DESCRIPTOR));
    }

    @Test
    void aDocumentWithoutChunksIsRejectedAsInvalidChunkData() {
        parsedDocument(0L);
        this.chunkPages.serve(List.of());

        assertIndexingFailure(KnowledgeIndexFailureCode.CHUNK_DATA_INVALID, () -> this.service.index(command(0L)));

        assertThat(this.embeddingPort.batchCount()).isZero();
        assertThat(this.embeddingStore.calls()).isZero();
    }

    // ---------- ③ 响应校验 ----------

    @Test
    void rejectsResponsesWhoseSizeDoesNotMatchTheRequest() {
        assertInvalidResponse(port -> port.returnTooFewForNextCall());
        assertInvalidResponse(port -> port.returnTooManyForNextCall());
        assertInvalidResponse(port -> port.returnNullListForNextCall());
        assertInvalidResponse(port -> port.returnNullElementForNextCall());
    }

    @Test
    void rejectsVectorsWithWrongDimensions() {
        assertInvalidResponse(port -> port.useVectorLength(1023));
        assertInvalidResponse(port -> port.useVectorLength(1025));
        assertThat(this.embeddingStore.calls()).as("非法响应绝不触发写入").isZero();
    }

    @Test
    void rejectsNonFiniteAndAllZeroVectors() {
        assertInvalidResponse(port -> port.useFillValue(Float.NaN));
        assertInvalidResponse(port -> port.useFillValue(Float.POSITIVE_INFINITY));
        assertInvalidResponse(port -> port.useFillValue(Float.NEGATIVE_INFINITY));
        assertInvalidResponse(port -> port.useFillValue(0.0f));
    }

    // ---------- ④ 原子完成 ----------

    @Test
    void completesExactlyOnceAfterEveryBatchSucceeded() {
        parsedDocument(0L);
        this.chunkPages.serve(RecordingIndexingPorts.chunks(DOCUMENT_ID, 23));

        IndexedDocumentView view = this.service.index(command(0L));

        assertThat(this.embeddingStore.calls()).as("全部批次成功后只调用一次原子完成").isEqualTo(1);
        assertThat(this.embeddingStore.expectedVersions())
                .as("完成阶段用「领取后的版本」做 CAS").containsExactly(1L);
        assertThat(view.chunkCount()).isEqualTo(23);
        assertThat(view.status()).isEqualTo(KnowledgeDocumentStatus.INDEXED);
        assertThat(view.embeddingProvider()).isEqualTo("dashscope");
        assertThat(view.embeddingModel()).isEqualTo("text-embedding-v4");
        assertThat(view.embeddingDimensions()).isEqualTo(1024);
    }

    @Test
    void theReturnedVersionReflectsBothLifecycleTransitions() {
        parsedDocument(2L);
        this.chunkPages.serve(RecordingIndexingPorts.chunks(DOCUMENT_ID, 3));
        this.embeddingStore.willReturnVersion(4L);

        IndexedDocumentView view = this.service.index(command(2L));

        // 领取 +1、完成 +1
        assertThat(view.version()).isEqualTo(4L);
        assertThat(this.repository.updatedExpectedVersions()).containsExactly(2L);
        assertThat(this.embeddingStore.expectedVersions()).containsExactly(3L);
    }

    @Test
    void aLaterBatchFailureLeavesNoVectorsBehind() {
        parsedDocument(0L);
        this.chunkPages.serve(RecordingIndexingPorts.chunks(DOCUMENT_ID, 23));
        this.embeddingPort.failAtBatch(1, RecordingIndexingPorts.providerFailure());

        assertIndexingFailure(KnowledgeIndexFailureCode.EMBEDDING_PROVIDER_FAILURE,
                () -> this.service.index(command(0L)));

        assertThat(this.embeddingStore.calls()).as("第二批失败后绝不能写入任何向量").isZero();
    }

    @Test
    void chunkPageAnomaliesAreRejectedBeforeAnyVectorIsWritten() {
        List<java.util.function.Consumer<RecordingIndexingPorts.RecordingChunkPages>> anomalies = List.of(
                RecordingIndexingPorts.RecordingChunkPages::failWithWrongOwner,
                RecordingIndexingPorts.RecordingChunkPages::failWithReversedOrder,
                RecordingIndexingPorts.RecordingChunkPages::failBySkippingTheFirstChunk);

        for (java.util.function.Consumer<RecordingIndexingPorts.RecordingChunkPages> anomaly : anomalies) {

            setUp();
            parsedDocument(0L);
            this.chunkPages.serve(RecordingIndexingPorts.chunks(DOCUMENT_ID, 5));
            anomaly.accept(this.chunkPages);

            assertIndexingFailure(KnowledgeIndexFailureCode.CHUNK_DATA_INVALID,
                    () -> this.service.index(command(0L)));

            assertThat(this.embeddingStore.calls()).isZero();
        }
    }

    // ---------- ⑤ 失败补偿 ----------

    @Test
    void aProviderFailureIsRecordedAsIndexFailed() {
        parsedDocument(0L);
        this.chunkPages.serve(RecordingIndexingPorts.chunks(DOCUMENT_ID, 2));
        this.embeddingPort.failEveryCallWith(RecordingIndexingPorts.providerFailure());

        assertIndexingFailure(KnowledgeIndexFailureCode.EMBEDDING_PROVIDER_FAILURE,
                () -> this.service.index(command(0L)));

        KnowledgeDocument compensated = this.repository.lastUpdated();
        assertThat(compensated.status()).isEqualTo(KnowledgeDocumentStatus.INDEX_FAILED);
        assertThat(compensated.indexFailureCode())
                .isEqualTo(KnowledgeIndexFailureCode.EMBEDDING_PROVIDER_FAILURE);
        assertThat(compensated.parsedAt()).as("失败补偿不得抹掉解析结果").isEqualTo(UPLOADED_AT.plusSeconds(2));
        assertThat(compensated.indexedAt()).isNull();
        assertThat(this.repository.updatedExpectedVersions()).as("补偿用领取后的版本做 CAS")
                .containsExactly(0L, 1L);
    }

    @Test
    void aStorageFailureBecomesAVectorStorageFailureWithAFailureCode() {
        parsedDocument(0L);
        this.chunkPages.serve(RecordingIndexingPorts.chunks(DOCUMENT_ID, 2));
        this.embeddingStore.failWith(new IllegalStateException("数据库连接断了"));

        // FD-0010-R1：非预期的写入异常必须带稳定失败码，否则 HTTP 层会返回一个没有 failureCode 的 500
        assertIndexingFailure(KnowledgeIndexFailureCode.VECTOR_STORAGE_FAILURE,
                () -> this.service.index(command(0L)));

        assertThat(this.repository.lastUpdated().status()).isEqualTo(KnowledgeDocumentStatus.INDEX_FAILED);
        assertThat(this.repository.lastUpdated().indexFailureCode())
                .as("持久化的失败码与对外失败码一致")
                .isEqualTo(KnowledgeIndexFailureCode.VECTOR_STORAGE_FAILURE);
    }

    @Test
    void aCompletionFailureThatAlreadyCarriesAFailureCodeIsReRaisedUnchanged() {
        parsedDocument(0L);
        this.chunkPages.serve(RecordingIndexingPorts.chunks(DOCUMENT_ID, 2));
        DocumentIndexingException fromStore = new DocumentIndexingException(
                KnowledgeIndexFailureCode.CHUNK_DATA_INVALID, "摘要不一致");
        this.embeddingStore.failWith(fromStore);

        // 原样保留（同一个异常实例），绝不重新包装成 METADATA_STORAGE_FAILURE
        assertThat(capture(() -> this.service.index(command(0L)))).isSameAs(fromStore);

        assertThat(this.repository.lastUpdated().status()).isEqualTo(KnowledgeDocumentStatus.INDEX_FAILED);
        assertThat(this.repository.lastUpdated().indexFailureCode())
                .isEqualTo(KnowledgeIndexFailureCode.CHUNK_DATA_INVALID);
    }

    @Test
    void aStorageFailureWithAnApplicationCodeKeepsTheFailureCodeContract() {
        parsedDocument(0L);
        this.chunkPages.serve(RecordingIndexingPorts.chunks(DOCUMENT_ID, 2));
        this.embeddingStore.failWith(new KnowledgeApplicationException(
                KnowledgeApplicationErrorCode.KNOWLEDGE_INTERNAL_ERROR, "向量与切片不一致"));

        // 内部一致性类失败对索引而言就是「写入没成功」：补一个稳定失败码，绝不出现裸 500
        assertIndexingFailure(KnowledgeIndexFailureCode.VECTOR_STORAGE_FAILURE,
                () -> this.service.index(command(0L)));

        assertThat(this.repository.lastUpdated().status()).isEqualTo(KnowledgeDocumentStatus.INDEX_FAILED);
        assertThat(this.repository.lastUpdated().indexFailureCode())
                .isEqualTo(KnowledgeIndexFailureCode.VECTOR_STORAGE_FAILURE);
    }

    @Test
    void aFailedCompensationIsAttachedAsSuppressedAndNeverReplacesTheRootCause() {
        parsedDocument(0L);
        this.chunkPages.serve(RecordingIndexingPorts.chunks(DOCUMENT_ID, 2));
        this.embeddingPort.failEveryCallWith(RecordingIndexingPorts.providerFailure());
        // 领取成功之后，补偿写库也失败
        this.repository.failUpdateForStatus(KnowledgeDocumentStatus.INDEX_FAILED,
                new IllegalStateException("补偿写库失败"));

        RuntimeException thrown = capture(() -> this.service.index(command(0L)));

        assertThat(thrown).isInstanceOf(DocumentIndexingException.class);
        assertThat(((DocumentIndexingException) thrown).failureCode())
                .isEqualTo(KnowledgeIndexFailureCode.EMBEDDING_PROVIDER_FAILURE);
        assertThat(thrown.getSuppressed()).hasSize(1)
                .allSatisfy(suppressed -> assertThat(suppressed).isInstanceOf(IllegalStateException.class));
    }

    @Test
    void aFailureBeforeTheClaimNeverTouchesTheDocument() {
        parsedDocument(0L);
        this.chunkPages.serve(RecordingIndexingPorts.chunks(DOCUMENT_ID, 1));
        // 未启用向量化时连读都不应该发生（这里直接验证「关闭 → 不读仓储」的语义）
        KnowledgeDocumentIndexingService disabled = new KnowledgeDocumentIndexingService(this.repository,
                this.chunkPages, this.embeddingPort, this.embeddingStore, this.clock, false, DESCRIPTOR, 10);

        assertApplicationError(() -> disabled.index(command(0L)),
                KnowledgeApplicationErrorCode.KNOWLEDGE_EMBEDDING_DISABLED);

        assertThat(this.repository.findCalls()).isZero();
        assertThat(this.repository.updateCalls()).isZero();
    }

    @Test
    void aClaimCasConflictIsNotWrittenAsIndexFailed() {
        parsedDocument(0L);
        this.repository.failEveryUpdateWith(new KnowledgeApplicationException(
                KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_VERSION_CONFLICT, "文档版本已变化"));

        assertApplicationError(() -> this.service.index(command(0L)),
                KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_VERSION_CONFLICT);

        assertThat(this.embeddingPort.batchCount()).as("领取失败后不得调用模型").isZero();
        assertThat(this.repository.found().version()).as("领取失败不得推进版本").isZero();
        assertThat(this.repository.found().document().status())
                .as("领取 CAS 冲突绝不能把文档写成 INDEX_FAILED")
                .isNotEqualTo(KnowledgeDocumentStatus.INDEX_FAILED);
        assertThat(this.repository.found().document().indexFailureCode()).isNull();
        assertThat(this.embeddingStore.calls()).isZero();
    }

    @Test
    void aCompletionCasConflictIsNotRewrittenAsIndexFailed() {
        parsedDocument(0L);
        this.chunkPages.serve(RecordingIndexingPorts.chunks(DOCUMENT_ID, 2));
        // 领取成功，但完成阶段的版本已经不匹配（另一个请求改过文档）
        this.embeddingStore.failWith(new KnowledgeApplicationException(
                KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_VERSION_CONFLICT, "并发更新冲突"));

        assertApplicationError(() -> this.service.index(command(0L)),
                KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_VERSION_CONFLICT);

        assertThat(this.repository.updatedExpectedVersions())
                .as("只允许领取那一次写入：版本冲突不得触发失败补偿")
                .containsExactly(0L);
        assertThat(this.repository.updateCalls()).isEqualTo(1);
    }

    @Test
    void aRetryAfterIndexFailedCanSucceed() {
        this.repository.willFind(documentIn(KnowledgeDocumentStatus.INDEX_FAILED), 2L);
        this.chunkPages.serve(RecordingIndexingPorts.chunks(DOCUMENT_ID, 1));
        this.embeddingStore.willReturnVersion(4L);

        IndexedDocumentView view = this.service.index(command(2L));

        assertThat(view.status()).isEqualTo(KnowledgeDocumentStatus.INDEXED);
        assertThat(this.repository.lastUpdated().status()).isEqualTo(KnowledgeDocumentStatus.INDEXED);
        assertThat(this.repository.lastUpdated().indexFailureCode()).isNull();
        assertThat(this.repository.lastUpdated().indexStartedAt())
                .as("重试必须刷新索引开始时间").isEqualTo(UPLOADED_AT.plusSeconds(10));
    }

    // ---------- 辅助 ----------

    private IndexKnowledgeDocumentCommand command(long expectedVersion) {
        return new IndexKnowledgeDocumentCommand(DOCUMENT_ID, expectedVersion);
    }

    private void parsedDocument(long version) {
        KnowledgeDocument document = KnowledgeDocument.create(DOCUMENT_ID, DocumentTitle.of("季度运维报告"),
                OriginalFilename.of("report.pdf"), DocumentFormat.PDF, "application/pdf", 1024L,
                Sha256Digest.of(DIGEST), CONTENT_KEY, UPLOADED_AT);
        document.markParsing(UPLOADED_AT.plusSeconds(1));
        document.markParsed(UPLOADED_AT.plusSeconds(2));
        this.repository.willFind(document, version);
    }

    private static KnowledgeDocument documentIn(KnowledgeDocumentStatus status) {
        KnowledgeDocument document = KnowledgeDocument.create(DOCUMENT_ID, DocumentTitle.of("季度运维报告"),
                OriginalFilename.of("report.pdf"), DocumentFormat.PDF, "application/pdf", 1024L,
                Sha256Digest.of(DIGEST), CONTENT_KEY, UPLOADED_AT);
        Instant parseAt = UPLOADED_AT.plusSeconds(2);
        switch (status) {
            case UPLOADED -> {
                return document;
            }
            case PARSING -> document.markParsing(UPLOADED_AT.plusSeconds(1));
            case PARSED -> {
                document.markParsing(UPLOADED_AT.plusSeconds(1));
                document.markParsed(parseAt);
            }
            case PARSE_FAILED -> {
                document.markParsing(UPLOADED_AT.plusSeconds(1));
                document.markParseFailed(KnowledgeParseFailureCode.CORRUPTED_DOCUMENT, parseAt);
            }
            case INDEXING -> {
                document.markParsing(UPLOADED_AT.plusSeconds(1));
                document.markParsed(parseAt);
                document.markIndexing(DESCRIPTOR, parseAt.plusSeconds(1));
            }
            case INDEXED -> {
                document.markParsing(UPLOADED_AT.plusSeconds(1));
                document.markParsed(parseAt);
                document.markIndexing(DESCRIPTOR, parseAt.plusSeconds(1));
                document.markIndexed(parseAt.plusSeconds(2));
            }
            case INDEX_FAILED -> {
                document.markParsing(UPLOADED_AT.plusSeconds(1));
                document.markParsed(parseAt);
                document.markIndexing(DESCRIPTOR, parseAt.plusSeconds(1));
                document.markIndexFailed(KnowledgeIndexFailureCode.VECTOR_STORAGE_FAILURE,
                        parseAt.plusSeconds(2));
            }
        }
        return document;
    }

    private void assertInvalidResponse(
            java.util.function.Consumer<RecordingIndexingPorts.RecordingEmbeddingPort> mutation) {

        setUp();
        parsedDocument(0L);
        this.chunkPages.serve(RecordingIndexingPorts.chunks(DOCUMENT_ID, 2));
        mutation.accept(this.embeddingPort);

        assertIndexingFailure(KnowledgeIndexFailureCode.INVALID_EMBEDDING_RESPONSE,
                () -> this.service.index(command(0L)));

        assertThat(this.embeddingStore.calls()).as("非法响应绝不触发向量写入").isZero();
    }

    private void assertIndexingFailure(KnowledgeIndexFailureCode expected,
            org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {

        RuntimeException thrown = capture(callable);
        assertThat(thrown).isInstanceOf(DocumentIndexingException.class);
        assertThat(((DocumentIndexingException) thrown).failureCode()).isEqualTo(expected);
    }

    private static List<String> expectedTexts(int count) {
        List<String> texts = new java.util.ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            texts.add("chunk-" + index);
        }
        return List.copyOf(texts);
    }
}
