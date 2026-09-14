package com.flowdesk.application.knowledge;

import static com.flowdesk.application.knowledge.KnowledgeTestSupport.DOCUMENT_ID;
import static com.flowdesk.application.knowledge.KnowledgeTestSupport.UPLOADED_AT;
import static com.flowdesk.application.knowledge.KnowledgeTestSupport.assertApplicationError;
import static com.flowdesk.application.knowledge.KnowledgeTestSupport.capture;
import static org.assertj.core.api.Assertions.assertThat;

import com.flowdesk.application.knowledge.command.ParseKnowledgeDocumentCommand;
import com.flowdesk.application.knowledge.parse.DocumentChunkingException;
import com.flowdesk.application.knowledge.parse.DocumentParsingException;
import com.flowdesk.application.knowledge.service.KnowledgeDocumentParsingService;
import com.flowdesk.application.knowledge.view.ParsedDocumentView;
import com.flowdesk.domain.knowledge.DocumentFormat;
import com.flowdesk.domain.knowledge.DocumentTitle;
import com.flowdesk.domain.knowledge.KnowledgeDocument;
import com.flowdesk.domain.knowledge.KnowledgeDocumentStatus;
import com.flowdesk.domain.knowledge.KnowledgeParseFailureCode;
import com.flowdesk.domain.knowledge.OriginalFilename;
import com.flowdesk.domain.knowledge.Sha256Digest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link KnowledgeDocumentParsingService} 单元测试（FD-0009）。
 *
 * <p>覆盖四件事：状态机与 CAS 时序、解析产物的确定性、失败路径的补偿，以及
 * 「解析与切片不在任何数据库写操作之间发生」这一事务边界约定。</p>
 */
class KnowledgeDocumentParsingServiceTest {

    private static final String CONTENT_KEY = "kdoc-abc123";

    private static final String DIGEST = "0123456789abcdef".repeat(4);

    private RecordingKnowledgePorts.RecordingDocumentRepository repository;

    private RecordingParsingPorts.RecordingContentReader reader;

    private RecordingParsingPorts.RecordingTextParser parser;

    private RecordingParsingPorts.RecordingChunker chunker;

    private RecordingParsingPorts.RecordingChunkStore chunkStore;

    private RecordingKnowledgePorts.RecordingTimeProvider clock;

    private KnowledgeDocumentParsingService service;

    @BeforeEach
    void setUp() {
        this.repository = new RecordingKnowledgePorts.RecordingDocumentRepository();
        this.reader = new RecordingParsingPorts.RecordingContentReader();
        this.parser = new RecordingParsingPorts.RecordingTextParser();
        this.chunker = new RecordingParsingPorts.RecordingChunker();
        this.chunkStore = new RecordingParsingPorts.RecordingChunkStore();
        this.clock = new RecordingKnowledgePorts.RecordingTimeProvider(UPLOADED_AT.plusSeconds(10),
                Duration.ofSeconds(1));
        this.service = new KnowledgeDocumentParsingService(this.repository, this.reader, this.parser,
                this.chunker, this.chunkStore, this.clock);
    }

    // ---------- ① 成功路径 ----------

    @Test
    void parsesAnUploadedDocumentAndReturnsTheNewVersion() {
        uploadedDocument(0L);
        this.reader.willReturn("第一段。第二段。");
        this.parser.willReturn("第一段。第二段。");
        this.chunker.willReturn(List.of("第一段。", "第二段。"));
        this.chunkStore.willReturnVersion(2L);

        ParsedDocumentView view = this.service.parse(command(0L));

        assertThat(view.documentId()).isEqualTo(DOCUMENT_ID.value());
        assertThat(view.title()).isEqualTo("季度运维报告");
        assertThat(view.status()).isEqualTo(KnowledgeDocumentStatus.PARSED);
        assertThat(view.version()).as("领取 +1、完成 +1").isEqualTo(2L);
        assertThat(view.chunkCount()).isEqualTo(2L);
        assertThat(view.parsedAt()).as("成功时返回解析完成时间").isNotNull();
        assertThat(view.failureCode()).isNull();
    }

    @Test
    void readsTheStoredContentByKeyAndClosesTheStream() {
        uploadedDocument(0L);

        this.service.parse(command(0L));

        assertThat(this.reader.openedKeys())
                .as("读取端口只接受内容键，不接受任何路径")
                .containsExactly(CONTENT_KEY);
        assertThat(this.reader.streamClosed()).as("原始内容流必须由调用方关闭").isTrue();
    }

    @Test
    void handsTheStoredFormatToTheParserRegardlessOfFileExtension() {
        uploadedDocument(0L, DocumentFormat.MARKDOWN, "notes.txt");

        this.service.parse(command(0L));

        assertThat(this.parser.formats()).as("格式来自元数据而不是文件名").containsExactly(DocumentFormat.MARKDOWN);
        assertThat(this.parser.seenBody()).isEqualTo("知识库文档内容");
    }

    @Test
    void passesTheExtractedTextToTheChunkerUnchanged() {
        uploadedDocument(0L);
        this.parser.willReturn("  保留空白与换行\n\n第二段  ");
        this.chunker.willReturn(List.of("  保留空白与换行", "第二段  "));

        this.service.parse(command(0L));

        assertThat(this.chunker.seenTexts())
                .as("规范化属于切片器内部职责，应用层不得悄悄改写文本")
                .containsExactly("  保留空白与换行\n\n第二段  ");
    }

    @Test
    void buildsChunksWithContinuousIndexesCodePointCountsAndDigests() {
        uploadedDocument(0L);
        String first = "第一季度📄";
        String second = "第二季度🚀报告";
        this.chunker.willReturn(List.of(first, second));

        this.service.parse(command(0L));

        List<com.flowdesk.domain.knowledge.KnowledgeDocumentChunk> chunks = this.chunkStore.receivedChunks();
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).chunkIndex()).isZero();
        assertThat(chunks.get(1).chunkIndex()).isEqualTo(1);
        assertThat(chunks.get(0).content()).isEqualTo(first);
        assertThat(chunks.get(0).codePointCount()).isEqualTo(first.codePointCount(0, first.length()));
        assertThat(chunks.get(1).codePointCount()).isEqualTo(second.codePointCount(0, second.length()));
        assertThat(chunks.get(0).sha256().value()).isEqualTo(sha256Hex(first));
        assertThat(chunks.get(1).sha256().value()).isEqualTo(sha256Hex(second));
        assertThat(chunks.get(0).documentId()).isEqualTo(DOCUMENT_ID);
        assertThat(chunks.get(0).createdAt()).isEqualTo(chunks.get(1).createdAt());
    }

    @Test
    void theSameInputProducesByteIdenticalChunks() {
        uploadedDocument(0L);
        this.chunker.willReturn(List.of("切片一", "切片二"));

        this.service.parse(command(0L));
        List<com.flowdesk.domain.knowledge.KnowledgeDocumentChunk> first = this.chunkStore.receivedChunks();

        // 第二次解析：同样的输入、同样的时钟，必须得到逐字段相同的结果
        setUp();
        uploadedDocument(0L);
        this.chunker.willReturn(List.of("切片一", "切片二"));
        this.service.parse(command(0L));

        assertThat(this.chunkStore.receivedChunks()).isEqualTo(first);
    }

    @Test
    void completionSeesTheDocumentAlreadyMarkedParsed() {
        uploadedDocument(0L);

        this.service.parse(command(0L));

        assertThat(this.chunkStore.completeParsingCalls()).isEqualTo(1);
        assertThat(this.chunkStore.statusAtCall()).isEqualTo(KnowledgeDocumentStatus.PARSED);
        assertThat(this.chunkStore.parsedAtAtCall()).as("完成时间必须已经写进聚合").isNotNull();
        assertThat(this.chunkStore.completeParsingExpectedVersion())
                .as("完成阶段必须用「领取后的版本」做 CAS，而不是领取前的版本")
                .isEqualTo(1L);
    }

    // ---------- ② 事务边界与调用顺序 ----------

    @Test
    void parsingAndChunkingHappenOutsideAnyRepositoryWrite() {
        uploadedDocument(0L);

        this.service.parse(command(0L));

        // 领取（update）之后，直到 completeParsing 之前，没有任何仓储写操作：
        // 应用层因此不可能在解析期间持有数据库事务
        assertThat(this.repository.calls()).containsExactly("findById", "update");
        assertThat(this.chunker.calls()).isEqualTo(1);
        assertThat(this.chunkStore.completeParsingCalls()).isEqualTo(1);
    }

    @Test
    void claimsWithTheVersionReadFromTheRepository() {
        uploadedDocument(7L);
        this.chunkStore.willReturnVersion(9L);

        this.service.parse(command(7L));

        assertThat(this.repository.updatedExpectedVersions()).containsExactly(7L);
        assertThat(this.chunkStore.completeParsingExpectedVersion()).isEqualTo(8L);
    }

    // ---------- ③ 前置条件 ----------

    @Test
    void rejectsMissingOrMalformedCommandsWithoutTouchingTheRepository() {
        assertApplicationError(() -> this.service.parse(null),
                KnowledgeApplicationErrorCode.INVALID_PARSE_COMMAND);
        assertApplicationError(() -> this.service.parse(new ParseKnowledgeDocumentCommand(null, 0L)),
                KnowledgeApplicationErrorCode.INVALID_PARSE_COMMAND);
        assertApplicationError(() -> this.service.parse(new ParseKnowledgeDocumentCommand(DOCUMENT_ID, -1L)),
                KnowledgeApplicationErrorCode.INVALID_PARSE_COMMAND);

        assertThat(this.repository.calls()).isEmpty();
    }

    @Test
    void unknownDocumentIsReportedAsNotFound() {
        // 仓储里什么都没有
        assertApplicationError(() -> this.service.parse(command(0L)),
                KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_NOT_FOUND);

        assertThat(this.repository.updateCalls()).isZero();
    }

    @Test
    void staleIfMatchIsRejectedBeforeAnyWrite() {
        uploadedDocument(3L);

        assertApplicationError(() -> this.service.parse(command(2L)),
                KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_VERSION_CONFLICT);
        assertApplicationError(() -> this.service.parse(command(4L)),
                KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_VERSION_CONFLICT);

        assertThat(this.repository.updateCalls()).as("版本过期时绝不能发生写入").isZero();
        assertThat(this.reader.openedKeys()).isEmpty();
        assertThat(this.chunkStore.completeParsingCalls()).isZero();
    }

    @Test
    void concurrentClaimLosesWithAVersionConflict() {
        uploadedDocument(0L);
        // 模拟「另一个请求抢先领取」：CAS 期望版本已经不匹配
        this.repository.failEveryUpdateWith(new KnowledgeApplicationException(
                KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_VERSION_CONFLICT, "文档版本已变化"));

        assertApplicationError(() -> this.service.parse(command(0L)),
                KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_VERSION_CONFLICT);

        assertThat(this.reader.openedKeys()).as("领取失败后不得读取与解析内容").isEmpty();
        assertThat(this.chunkStore.completeParsingCalls()).isZero();
    }

    @Test
    void documentsInNonClaimableStatesAreRejected() {
        for (KnowledgeDocumentStatus status : List.of(KnowledgeDocumentStatus.PARSING,
                KnowledgeDocumentStatus.PARSED)) {
            setUp();
            this.repository.willFind(documentIn(status), 1L);

            assertApplicationError(() -> this.service.parse(command(1L)),
                    KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_NOT_PARSABLE);

            assertThat(this.repository.updateCalls()).as(status + " 不得被领取").isZero();
            assertThat(this.reader.openedKeys()).isEmpty();
        }
    }

    @Test
    void aFailedDocumentCanBeClaimedAgainAndLosesItsFailureTrace() {
        this.repository.willFind(documentIn(KnowledgeDocumentStatus.PARSE_FAILED), 2L);
        this.chunker.willReturn(List.of("重试后的切片"));
        this.chunkStore.willReturnVersion(4L);

        ParsedDocumentView view = this.service.parse(command(2L));

        assertThat(view.chunkCount()).isEqualTo(1);
        KnowledgeDocument claimed = this.repository.lastUpdated();
        assertThat(claimed.status()).isEqualTo(KnowledgeDocumentStatus.PARSED);
        assertThat(claimed.parseFailureCode()).as("重新解析成功后不得残留上一次的失败码").isNull();
        assertThat(claimed.parseFailedAt()).isNull();
    }

    // ---------- ④ 失败路径 ----------

    @Test
    void emptyExtractedTextIsReportedAsAParseFailureAndRecordedOnTheDocument() {
        uploadedDocument(0L);
        this.parser.willReturn("");

        RuntimeException thrown = capture(() -> this.service.parse(command(0L)));

        assertThat(thrown).isInstanceOf(DocumentParsingException.class);
        assertThat(((DocumentParsingException) thrown).failureCode())
                .isEqualTo(KnowledgeParseFailureCode.EMPTY_EXTRACTED_TEXT);
        assertThat(this.chunkStore.completeParsingCalls()).isZero();
        assertThat(failedDocument().parseFailureCode())
                .isEqualTo(KnowledgeParseFailureCode.EMPTY_EXTRACTED_TEXT);
    }

    @Test
    void chunkerReturningNothingIsTreatedAsEmptyText() {
        uploadedDocument(0L);
        this.parser.willReturn("   ");
        this.chunker.willReturn(List.of());

        RuntimeException thrown = capture(() -> this.service.parse(command(0L)));

        assertThat(((DocumentParsingException) thrown).failureCode())
                .isEqualTo(KnowledgeParseFailureCode.EMPTY_EXTRACTED_TEXT);
    }

    @Test
    void parserFailuresPropagateWithTheirStableCodeAndAreRecorded() {
        for (KnowledgeParseFailureCode code : List.of(KnowledgeParseFailureCode.CORRUPTED_DOCUMENT,
                KnowledgeParseFailureCode.ENCRYPTED_DOCUMENT,
                KnowledgeParseFailureCode.UNSUPPORTED_DOCUMENT_CONTENT,
                KnowledgeParseFailureCode.EXTRACTED_TEXT_TOO_LARGE)) {

            setUp();
            uploadedDocument(0L);
            this.parser.failWith(new DocumentParsingException(code, "服务端诊断信息"));

            RuntimeException thrown = capture(() -> this.service.parse(command(0L)));

            assertThat(((DocumentParsingException) thrown).failureCode()).isEqualTo(code);
            assertThat(failedDocument().parseFailureCode()).isEqualTo(code);
        }
    }

    @Test
    void chunkerFailuresPropagateWithTheirStableCodeAndAreRecorded() {
        uploadedDocument(0L);
        this.chunker.failWith(new DocumentChunkingException(KnowledgeParseFailureCode.TOO_MANY_CHUNKS,
                "切片过多"));

        RuntimeException thrown = capture(() -> this.service.parse(command(0L)));

        assertThat(thrown).isInstanceOf(DocumentChunkingException.class);
        assertThat(((DocumentChunkingException) thrown).failureCode())
                .isEqualTo(KnowledgeParseFailureCode.TOO_MANY_CHUNKS);
        assertThat(failedDocument().parseFailureCode()).isEqualTo(KnowledgeParseFailureCode.TOO_MANY_CHUNKS);
    }

    @Test
    void unreadableContentIsReportedAsAnInfrastructureFailure() {
        uploadedDocument(0L);
        this.reader.failWith(new KnowledgeApplicationException(
                KnowledgeApplicationErrorCode.DOCUMENT_CONTENT_UNREADABLE, "原始内容不可读"));

        assertApplicationError(() -> this.service.parse(command(0L)),
                KnowledgeApplicationErrorCode.DOCUMENT_CONTENT_UNREADABLE);

        assertThat(failedDocument().parseFailureCode())
                .as("内容不可读对调用方而言是服务端问题，但文档仍要落到可重试的失败态")
                .isEqualTo(KnowledgeParseFailureCode.PARSER_FAILURE);
    }

    @Test
    void completionFailureIsMappedToAStorageFailureAndTheDocumentStaysRetryable() {
        uploadedDocument(0L);
        this.chunkStore.failWith(new IllegalStateException("数据库连接断了"));

        RuntimeException thrown = capture(() -> this.service.parse(command(0L)));

        assertThat(thrown).isInstanceOf(KnowledgeApplicationException.class);
        assertThat(((KnowledgeApplicationException) thrown).errorCode())
                .isEqualTo(KnowledgeApplicationErrorCode.METADATA_STORAGE_FAILURE);
        assertThat(thrown.getCause()).as("原始失败必须保留为 cause").isInstanceOf(IllegalStateException.class);

        KnowledgeDocument failed = failedDocument();
        assertThat(failed.status())
                .as("完成写入整体回滚后，文档必须被标记为可重试的 PARSE_FAILED，而不是停在 PARSING")
                .isEqualTo(KnowledgeDocumentStatus.PARSE_FAILED);
        assertThat(failed.parseFailureCode()).isEqualTo(KnowledgeParseFailureCode.PARSER_FAILURE);
        assertThat(failed.parsedAt()).as("失败的文档不得留下解析完成时间").isNull();
    }

    @Test
    void completionFailureWithAnApplicationCodeKeepsThatCode() {
        uploadedDocument(0L);
        this.chunkStore.failWith(new KnowledgeApplicationException(
                KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_VERSION_CONFLICT, "并发更新冲突"));

        assertApplicationError(() -> this.service.parse(command(0L)),
                KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_VERSION_CONFLICT);
        assertThat(failedDocument().status()).isEqualTo(KnowledgeDocumentStatus.PARSE_FAILED);
    }

    @Test
    void compensationFailureNeverReplacesTheRootCause() {
        uploadedDocument(0L);
        this.parser.failWith(new DocumentParsingException(KnowledgeParseFailureCode.CORRUPTED_DOCUMENT,
                "文档损坏"));
        // 领取成功之后，补偿写库也失败（只有补偿那次写入会失败）
        this.repository.failUpdateForStatus(KnowledgeDocumentStatus.PARSE_FAILED,
                new IllegalStateException("补偿写库失败"));

        RuntimeException thrown = capture(() -> this.service.parse(command(0L)));

        assertThat(thrown).isInstanceOf(DocumentParsingException.class);
        assertThat(((DocumentParsingException) thrown).failureCode())
                .isEqualTo(KnowledgeParseFailureCode.CORRUPTED_DOCUMENT);
        assertThat(thrown.getSuppressed())
                .as("补偿失败只作为 suppressed 附加")
                .hasSize(1)
                .allSatisfy(suppressed -> assertThat(suppressed).isInstanceOf(IllegalStateException.class));
    }

    @Test
    void failureCompensationUsesTheClaimedVersionAsCasExpectation() {
        uploadedDocument(0L);
        this.parser.failWith(new DocumentParsingException(KnowledgeParseFailureCode.CORRUPTED_DOCUMENT, "损坏"));

        capture(() -> this.service.parse(command(0L)));

        assertThat(this.repository.updatedExpectedVersions())
                .as("领取用旧版本，补偿必须用领取后的新版本")
                .containsExactly(0L, 1L);
    }

    @Test
    void failuresNeverLeakParserDetailsIntoTheDocument() {
        uploadedDocument(0L);
        this.parser.failWith(new DocumentParsingException(KnowledgeParseFailureCode.CORRUPTED_DOCUMENT,
                "sentinel-C:/secret/path/report.pdf"));

        capture(() -> this.service.parse(command(0L)));

        KnowledgeDocument failed = failedDocument();
        assertThat(failed.parseFailureCode()).isEqualTo(KnowledgeParseFailureCode.CORRUPTED_DOCUMENT);
        assertThat(failed.toString()).doesNotContain("sentinel").doesNotContain("secret");
    }

    // ---------- 辅助 ----------

    private ParseKnowledgeDocumentCommand command(long expectedVersion) {
        return new ParseKnowledgeDocumentCommand(DOCUMENT_ID, expectedVersion);
    }

    private void uploadedDocument(long version) {
        uploadedDocument(version, DocumentFormat.PDF, "report.pdf");
    }

    private void uploadedDocument(long version, DocumentFormat format, String fileName) {
        this.repository.willFind(KnowledgeDocument.create(DOCUMENT_ID, DocumentTitle.of("季度运维报告"),
                OriginalFilename.of(fileName), format, "application/pdf", 1024L, Sha256Digest.of(DIGEST),
                CONTENT_KEY, UPLOADED_AT), version);
    }

    private static KnowledgeDocument documentIn(KnowledgeDocumentStatus status) {
        KnowledgeDocument document = KnowledgeDocument.create(DOCUMENT_ID, DocumentTitle.of("季度运维报告"),
                OriginalFilename.of("report.pdf"), DocumentFormat.PDF, "application/pdf", 1024L,
                Sha256Digest.of(DIGEST), CONTENT_KEY, UPLOADED_AT);
        Instant claimedAt = UPLOADED_AT.plusSeconds(1);
        switch (status) {
            case UPLOADED -> {
                return document;
            }
            case PARSING -> document.markParsing(claimedAt);
            case PARSED -> {
                document.markParsing(claimedAt);
                document.markParsed(claimedAt.plusSeconds(1));
            }
            case PARSE_FAILED -> {
                document.markParsing(claimedAt);
                document.markParseFailed(KnowledgeParseFailureCode.CORRUPTED_DOCUMENT, claimedAt.plusSeconds(1));
            }
        }
        return document;
    }

    /** 补偿路径上被写回的文档（替身记录的是传给 {@code update} 的聚合）。 */
    private KnowledgeDocument failedDocument() {
        KnowledgeDocument updated = this.repository.lastUpdated();
        assertThat(updated).as("补偿必须真的调用了仓储 update").isNotNull();
        return updated;
    }

    private static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        }
        catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
