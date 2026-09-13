package com.flowdesk.application.knowledge;

import static com.flowdesk.application.knowledge.KnowledgeTestSupport.DOCUMENT_ID;
import static com.flowdesk.application.knowledge.KnowledgeTestSupport.PDF_CONTENT;
import static com.flowdesk.application.knowledge.KnowledgeTestSupport.TEXT_CONTENT;
import static com.flowdesk.application.knowledge.KnowledgeTestSupport.UPLOADED_AT;
import static com.flowdesk.application.knowledge.KnowledgeTestSupport.assertApplicationError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowdesk.application.knowledge.command.UploadKnowledgeDocumentCommand;
import com.flowdesk.application.knowledge.service.KnowledgeDocumentApplicationService;
import com.flowdesk.application.knowledge.view.KnowledgeDocumentView;
import com.flowdesk.domain.knowledge.DocumentFormat;
import com.flowdesk.domain.knowledge.KnowledgeDocument;
import com.flowdesk.domain.knowledge.KnowledgeDocumentStatus;
import com.flowdesk.domain.knowledge.KnowledgeErrorCode;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * 上传用例测试：调用顺序、输入校验的时机、失败补偿与流式限流。
 */
class KnowledgeDocumentApplicationServiceUploadTest {

    /** 默认上限：足够放下测试内容。 */
    private static final long DEFAULT_LIMIT = 1024L * 1024L;

    private final RecordingKnowledgePorts.RecordingDocumentRepository repository =
            new RecordingKnowledgePorts.RecordingDocumentRepository();

    private final RecordingKnowledgePorts.RecordingContentStore contentStore =
            new RecordingKnowledgePorts.RecordingContentStore();

    private final RecordingKnowledgePorts.RecordingIdGenerator idGenerator =
            new RecordingKnowledgePorts.RecordingIdGenerator();

    private final RecordingKnowledgePorts.RecordingTimeProvider timeProvider =
            new RecordingKnowledgePorts.RecordingTimeProvider(UPLOADED_AT);

    private final KnowledgeDocumentApplicationService service = new KnowledgeDocumentApplicationService(
            this.repository, this.contentStore, this.idGenerator, this.timeProvider, DEFAULT_LIMIT);

    // ---------- ① 成功路径与调用顺序 ----------

    @Test
    void uploadsInTheDocumentedOrderAndReturnsTheView() {
        KnowledgeTestSupport.ByteArrayContentSource source =
                KnowledgeTestSupport.ByteArrayContentSource.text(TEXT_CONTENT, "notes.txt");

        KnowledgeDocumentView view = this.service.upload(
                new UploadKnowledgeDocumentCommand("季度运维报告", source));

        // 顺序：生成标识 → 取时间 → 写内容 → 写元数据
        assertThat(this.idGenerator.calls()).isEqualTo(1);
        assertThat(this.timeProvider.calls()).isEqualTo(1);
        assertThat(this.contentStore.calls()).containsExactly("store");
        assertThat(this.repository.calls()).containsExactly("insert");

        assertThat(view.id()).isEqualTo(DOCUMENT_ID.value());
        assertThat(view.title()).isEqualTo("季度运维报告");
        assertThat(view.originalFilename()).isEqualTo("notes.txt");
        assertThat(view.format()).isEqualTo(DocumentFormat.TEXT);
        assertThat(view.mediaType()).isEqualTo("text/plain");
        assertThat(view.sizeBytes()).isEqualTo(TEXT_CONTENT.length);
        assertThat(view.status()).isEqualTo(KnowledgeDocumentStatus.UPLOADED);
        assertThat(view.version()).isZero();
        assertThat(view.createdAt()).isEqualTo(UPLOADED_AT);
        assertThat(view.updatedAt()).isEqualTo(UPLOADED_AT);

        // 内容确实被完整读取，且摘要与真实内容一致
        assertThat(this.contentStore.storedBytes()).isEqualTo(TEXT_CONTENT);
        assertThat(view.sha256().value()).isEqualTo(KnowledgeTestSupport.sha256Hex(TEXT_CONTENT));

        // 内容源只打开一次、且被关闭
        assertThat(source.opens()).isEqualTo(1);
        assertThat(source.closes()).as("用例必须关闭内容源").isEqualTo(1);
    }

    @Test
    void normalizesTitleAndFileNameBeforeCreatingTheDocument() {
        KnowledgeTestSupport.ByteArrayContentSource source = KnowledgeTestSupport.ByteArrayContentSource.of(
                PDF_CONTENT, "C:\\fakepath\\季度报告.PDF", "application/pdf");

        KnowledgeDocumentView view = this.service.upload(
                new UploadKnowledgeDocumentCommand("  季度运维报告  ", source));

        assertThat(view.title()).as("标题必须 strip 后入库").isEqualTo("季度运维报告");
        assertThat(view.originalFilename()).as("Windows 伪路径必须收敛为纯文件名")
                .isEqualTo("季度报告.PDF");
        assertThat(view.format()).as("扩展名大小写不敏感").isEqualTo(DocumentFormat.PDF);
        assertThat(view.mediaType()).as("媒体类型采用规范值").isEqualTo("application/pdf");
    }

    @Test
    void acceptsEverySupportedFormatWithValidContent() {
        assertThat(upload("a.pdf", "application/pdf", PDF_CONTENT).format()).isEqualTo(DocumentFormat.PDF);
        assertThat(upload("a.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                KnowledgeTestSupport.DOCX_CONTENT).format()).isEqualTo(DocumentFormat.DOCX);
        assertThat(upload("a.md", "text/markdown", TEXT_CONTENT).format()).isEqualTo(DocumentFormat.MARKDOWN);
        assertThat(upload("a.txt", null, TEXT_CONTENT).format()).isEqualTo(DocumentFormat.TEXT);
    }

    @Test
    void acceptsUnspecifiedOrGenericContentTypeAndRecognizesByExtensionAndContent() {
        assertThat(upload("a.txt", null, TEXT_CONTENT).format()).isEqualTo(DocumentFormat.TEXT);
        assertThat(upload("a.txt", "", TEXT_CONTENT).format()).isEqualTo(DocumentFormat.TEXT);
        assertThat(upload("a.pdf", "application/octet-stream", PDF_CONTENT).format())
                .isEqualTo(DocumentFormat.PDF);
        assertThat(upload("a.md", "text/plain; charset=utf-8", TEXT_CONTENT).format())
                .isEqualTo(DocumentFormat.MARKDOWN);
    }

    // ---------- ② 输入校验失败：不打开文件、不调用存储 ----------

    @Test
    void rejectsInvalidMetadataWithoutTouchingAnything() {
        assertNoSideEffects(() -> this.service.upload(new UploadKnowledgeDocumentCommand("   ",
                KnowledgeTestSupport.ByteArrayContentSource.text(TEXT_CONTENT, "a.txt"))),
                KnowledgeErrorCode.INVALID_TITLE);

        assertNoSideEffects(() -> this.service.upload(new UploadKnowledgeDocumentCommand(
                "t".repeat(201), KnowledgeTestSupport.ByteArrayContentSource.text(TEXT_CONTENT, "a.txt"))),
                KnowledgeErrorCode.INVALID_TITLE);

        assertNoSideEffects(() -> this.service.upload(new UploadKnowledgeDocumentCommand("标题",
                KnowledgeTestSupport.ByteArrayContentSource.of(TEXT_CONTENT, null, "text/plain"))),
                KnowledgeErrorCode.INVALID_ORIGINAL_FILENAME);

        assertNoSideEffects(() -> this.service.upload(new UploadKnowledgeDocumentCommand("标题",
                KnowledgeTestSupport.ByteArrayContentSource.of(TEXT_CONTENT, "bad\u0000name.txt",
                        "text/plain"))),
                KnowledgeErrorCode.INVALID_ORIGINAL_FILENAME);
    }

    @Test
    void rejectsUnsupportedExtensionWithoutTouchingAnything() {
        assertNoSideEffects(() -> this.service.upload(new UploadKnowledgeDocumentCommand("标题",
                KnowledgeTestSupport.ByteArrayContentSource.of(TEXT_CONTENT, "archive.zip", "application/zip"))),
                KnowledgeApplicationErrorCode.UNSUPPORTED_DOCUMENT_FORMAT);
    }

    @Test
    void rejectsIncompatibleDeclaredContentTypeWithoutTouchingAnything() {
        assertNoSideEffects(() -> this.service.upload(new UploadKnowledgeDocumentCommand("标题",
                KnowledgeTestSupport.ByteArrayContentSource.of(PDF_CONTENT, "report.pdf", "image/png"))),
                KnowledgeApplicationErrorCode.UNSUPPORTED_DOCUMENT_FORMAT);

        assertNoSideEffects(() -> this.service.upload(new UploadKnowledgeDocumentCommand("标题",
                KnowledgeTestSupport.ByteArrayContentSource.of(TEXT_CONTENT, "notes.txt", "application/pdf"))),
                KnowledgeApplicationErrorCode.UNSUPPORTED_DOCUMENT_FORMAT);
    }

    @Test
    void rejectsNullCommandAndNullSource() {
        assertApplicationError(() -> this.service.upload(null),
                KnowledgeApplicationErrorCode.INVALID_UPLOAD_COMMAND);
        assertApplicationError(() -> this.service.upload(new UploadKnowledgeDocumentCommand("标题", null)),
                KnowledgeApplicationErrorCode.INVALID_UPLOAD_COMMAND);
    }

    @Test
    void rejectsDeclaredSizeAboveTheLimitWithoutOpeningTheFile() {
        KnowledgeTestSupport.ByteArrayContentSource source =
                KnowledgeTestSupport.ByteArrayContentSource.lyingAboutSize(TEXT_CONTENT, "a.txt", "text/plain",
                        DEFAULT_LIMIT + 1);

        assertApplicationError(() -> this.service.upload(new UploadKnowledgeDocumentCommand("标题", source)),
                KnowledgeApplicationErrorCode.DOCUMENT_TOO_LARGE);

        assertThat(source.opens()).as("声明大小已超限时不应打开文件").isZero();
        assertThat(this.contentStore.storeCalls()).isZero();
        assertThat(this.repository.insertCalls()).isZero();
    }

    // ---------- ③ 内容校验：文件头、UTF-8、NUL ----------

    @Test
    void rejectsPdfWithoutTheExpectedHeader() {
        assertApplicationError(() -> upload("fake.pdf", "application/pdf",
                "not really a pdf".getBytes(StandardCharsets.US_ASCII)),
                KnowledgeApplicationErrorCode.UNSUPPORTED_DOCUMENT_FORMAT);
        assertThat(this.repository.insertCalls()).isZero();
    }

    @Test
    void rejectsDocxWithoutTheZipHeader() {
        assertApplicationError(() -> upload("fake.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "not a zip at all".getBytes(StandardCharsets.US_ASCII)),
                KnowledgeApplicationErrorCode.UNSUPPORTED_DOCUMENT_FORMAT);
    }

    @Test
    void rejectsTruncatedSignature() {
        // 只有一个字节的 "P"：既不够 PDF 前缀，也不够 ZIP 头
        assertApplicationError(() -> upload("short.pdf", "application/pdf", new byte[] { 0x25 }),
                KnowledgeApplicationErrorCode.UNSUPPORTED_DOCUMENT_FORMAT);
    }

    @Test
    void rejectsTextThatIsNotValidUtf8() {
        byte[] invalidUtf8 = { (byte) 0xC3, (byte) 0x28, 0x41 };

        assertApplicationError(() -> upload("broken.txt", "text/plain", invalidUtf8),
                KnowledgeApplicationErrorCode.UNSUPPORTED_DOCUMENT_FORMAT);
        assertApplicationError(() -> upload("broken.md", "text/markdown", invalidUtf8),
                KnowledgeApplicationErrorCode.UNSUPPORTED_DOCUMENT_FORMAT);
    }

    @Test
    void rejectsTextContainingNul() {
        byte[] withNul = { 'a', 0x00, 'b' };

        assertApplicationError(() -> upload("nul.txt", "text/plain", withNul),
                KnowledgeApplicationErrorCode.UNSUPPORTED_DOCUMENT_FORMAT);
    }

    @Test
    void acceptsMultibyteUtf8SplitAcrossReadBoundaries() {
        // 16 KiB 缓冲区边界恰好切开一个 3 字节字符：增量解码器必须能处理，不能误判
        byte[] content = new byte[16 * 1024 + 3];
        java.util.Arrays.fill(content, (byte) 'a');
        byte[] chinese = "中".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(chinese, 0, content, 16 * 1024 - 1, 3);

        KnowledgeDocumentView view = upload("split.txt", "text/plain", content);

        assertThat(view.sizeBytes()).isEqualTo(content.length);
    }

    // ---------- ④ 流式限流：只信实际读取量 ----------

    @Test
    void limitsByActualBytesEvenWhenTheDeclaredSizeLies() {
        long limit = 512L;
        KnowledgeDocumentApplicationService small = serviceWithLimit(limit);
        byte[] tooBig = new byte[(int) limit + 100];
        java.util.Arrays.fill(tooBig, (byte) 'x');
        KnowledgeTestSupport.ByteArrayContentSource source =
                KnowledgeTestSupport.ByteArrayContentSource.lyingAboutSize(tooBig, "big.txt", "text/plain", 10L);

        assertApplicationError(() -> small.upload(
                new UploadKnowledgeDocumentCommand("标题", source)),
                KnowledgeApplicationErrorCode.DOCUMENT_TOO_LARGE);

        assertThat(source.opens()).as("必须真的开始读取才能发现").isEqualTo(1);
        assertThat(this.repository.insertCalls()).as("超限时绝不能写元数据").isZero();
    }

    @Test
    void rejectsEmptyContent() {
        assertApplicationError(() -> upload("empty.txt", "text/plain", new byte[0]),
                KnowledgeApplicationErrorCode.EMPTY_DOCUMENT_CONTENT);
        assertThat(this.repository.insertCalls()).isZero();
    }

    @Test
    void rejectsNonPositiveConfiguredLimit() {
        assertThatThrownBy(() -> serviceWithLimit(0L)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- ⑤ 失败与补偿 ----------

    @Test
    void doesNotWriteMetadataWhenContentStorageFails() {
        this.contentStore.failStoreWith(new KnowledgeApplicationException(
                KnowledgeApplicationErrorCode.CONTENT_STORAGE_FAILURE, "磁盘写入失败"));

        assertApplicationError(() -> upload("a.txt", "text/plain", TEXT_CONTENT),
                KnowledgeApplicationErrorCode.CONTENT_STORAGE_FAILURE);

        assertThat(this.repository.insertCalls()).as("内容没存成，绝不能留下元数据").isZero();
        assertThat(this.contentStore.deleteCalls()).isZero();
    }

    @Test
    void compensatesWhenMetadataInsertFails() {
        this.repository.failInsertWith(new IllegalStateException("数据库连接断了"));

        RuntimeException thrown = KnowledgeTestSupport.capture(() -> upload("a.txt", "text/plain",
                TEXT_CONTENT));

        assertThat(thrown).isInstanceOf(KnowledgeApplicationException.class);
        assertThat(((KnowledgeApplicationException) thrown).errorCode())
                .isEqualTo(KnowledgeApplicationErrorCode.METADATA_STORAGE_FAILURE);
        assertThat(this.contentStore.deleteCalls()).as("必须调用一次补偿删除").isEqualTo(1);
        assertThat(this.contentStore.deletedKey()).isEqualTo(this.contentStore.contentKey());
        assertThat(this.contentStore.calls()).containsExactly("store", "delete");
    }

    @Test
    void compensationUsesTheKeyReturnedByTheStoreSoItCannotDeleteOtherDocuments() {
        this.contentStore.useContentKey("content-key-of-this-upload");
        this.repository.failInsertWith(new IllegalStateException("duplicate"));

        KnowledgeTestSupport.capture(() -> upload("a.txt", "text/plain", TEXT_CONTENT));

        assertThat(this.contentStore.deletedKey())
                .as("补偿删除必须精确指向本次上传的内容键")
                .isEqualTo("content-key-of-this-upload");
    }

    @Test
    void stillFailsSafelyWhenCompensationItselfFails() {
        this.repository.failInsertWith(new IllegalStateException("数据库写入失败"));
        this.contentStore.failDeleteWith(new KnowledgeApplicationException(
                KnowledgeApplicationErrorCode.CONTENT_STORAGE_FAILURE, "删除失败"));

        RuntimeException thrown = KnowledgeTestSupport.capture(() -> upload("a.txt", "text/plain",
                TEXT_CONTENT));

        assertThat(thrown).isInstanceOf(KnowledgeApplicationException.class);
        assertThat(((KnowledgeApplicationException) thrown).errorCode())
                .as("补偿失败只能留下孤立文件，对外仍是固定的服务端错误")
                .isEqualTo(KnowledgeApplicationErrorCode.METADATA_STORAGE_FAILURE);
        assertThat(thrown.getMessage()).doesNotContain("删除失败");
        assertThat(this.contentStore.deleteCalls()).isEqualTo(1);
    }

    @Test
    void closesTheContentSourceEvenWhenEverythingFails() {
        KnowledgeTestSupport.ByteArrayContentSource source =
                KnowledgeTestSupport.ByteArrayContentSource.text(TEXT_CONTENT, "a.txt");
        this.contentStore.failStoreWith(new KnowledgeApplicationException(
                KnowledgeApplicationErrorCode.CONTENT_STORAGE_FAILURE, "失败"));

        KnowledgeTestSupport.capture(() -> this.service.upload(
                new UploadKnowledgeDocumentCommand("标题", source)));

        assertThat(source.closes()).as("失败路径也必须关闭内容源").isEqualTo(1);
    }

    @Test
    void theInsertedDocumentCarriesTheStoreResultVerbatim() {
        this.contentStore.useContentKey("opaque-key-42");

        upload("a.txt", "text/plain", TEXT_CONTENT);

        KnowledgeDocument inserted = this.repository.lastInserted();
        assertThat(inserted.contentKey()).isEqualTo("opaque-key-42");
        assertThat(inserted.sizeBytes()).isEqualTo(this.contentStore.lastSizeBytes());
        assertThat(inserted.id()).isEqualTo(DOCUMENT_ID);
    }

    // ---------- 辅助 ----------

    private KnowledgeDocumentView upload(String fileName, String contentType, byte[] content) {
        return this.service.upload(new UploadKnowledgeDocumentCommand("标题",
                KnowledgeTestSupport.ByteArrayContentSource.of(content, fileName, contentType)));
    }

    private KnowledgeDocumentApplicationService serviceWithLimit(long limit) {
        return new KnowledgeDocumentApplicationService(this.repository, this.contentStore, this.idGenerator,
                this.timeProvider, limit);
    }

    /**
     * 断言：指定失败既不打开文件，也不触碰内容存储与元数据存储。
     */
    private void assertNoSideEffects(Runnable callable, KnowledgeApplicationErrorCode expected) {
        assertApplicationError(callable::run, expected);
        assertNoStorageCalls();
    }

    private void assertNoSideEffects(Runnable callable, KnowledgeErrorCode expected) {
        KnowledgeTestSupport.assertDomainError(callable::run, expected);
        assertNoStorageCalls();
    }

    private void assertNoStorageCalls() {
        assertThat(this.contentStore.storeCalls()).as("校验失败不得触碰内容存储").isZero();
        assertThat(this.contentStore.deleteCalls()).isZero();
        assertThat(this.repository.insertCalls()).as("校验失败不得触碰元数据存储").isZero();
        assertThat(this.idGenerator.calls()).as("校验失败不得生成标识").isZero();
    }
}
