package com.flowdesk.application.knowledge;

import static com.flowdesk.application.knowledge.KnowledgeTestSupport.TEXT_CONTENT;
import static com.flowdesk.application.knowledge.KnowledgeTestSupport.assertApplicationError;
import static org.assertj.core.api.Assertions.assertThat;

import com.flowdesk.application.knowledge.command.UploadKnowledgeDocumentCommand;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentIdGenerator;
import com.flowdesk.application.knowledge.port.out.KnowledgeTimeProvider;
import com.flowdesk.application.knowledge.port.out.StoredContent;
import com.flowdesk.application.knowledge.port.out.VersionedKnowledgeDocument;
import com.flowdesk.application.knowledge.service.KnowledgeDocumentApplicationService;
import com.flowdesk.domain.knowledge.DocumentFormat;
import com.flowdesk.domain.knowledge.DocumentTitle;
import com.flowdesk.domain.knowledge.KnowledgeDocument;
import com.flowdesk.domain.knowledge.KnowledgeDocumentId;
import com.flowdesk.domain.knowledge.KnowledgeErrorCode;
import com.flowdesk.domain.knowledge.OriginalFilename;
import com.flowdesk.domain.knowledge.Sha256Digest;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * 上传链路的<b>资源与补偿边界</b>测试（FD-0008-R1）。
 *
 * <p>覆盖两类真实缺陷：</p>
 * <ol>
 *   <li>内容源在<b>所有</b>成功/失败路径上都必须恰好关闭一次（前置校验、端口失败也算）；</li>
 *   <li>内容已发布之后、元数据确认写入之前的<b>任意</b>运行时失败都必须补偿删除一次，
 *       而元数据写入成功之后的失败绝不能删除内容。</li>
 * </ol>
 */
class KnowledgeUploadResourceBoundaryTest {

    private static final long LIMIT = 1024L * 1024L;

    private static final Instant UPLOADED_AT = KnowledgeTestSupport.UPLOADED_AT;

    private final RecordingKnowledgePorts.RecordingDocumentRepository repository =
            new RecordingKnowledgePorts.RecordingDocumentRepository();

    private final RecordingKnowledgePorts.RecordingContentStore contentStore =
            new RecordingKnowledgePorts.RecordingContentStore();

    private final RecordingKnowledgePorts.RecordingIdGenerator idGenerator =
            new RecordingKnowledgePorts.RecordingIdGenerator();

    private final RecordingKnowledgePorts.RecordingTimeProvider timeProvider =
            new RecordingKnowledgePorts.RecordingTimeProvider(UPLOADED_AT);

    private KnowledgeDocumentApplicationService service() {
        return new KnowledgeDocumentApplicationService(this.repository, this.contentStore, this.idGenerator,
                this.timeProvider, LIMIT);
    }

    // ---------- ① 前置校验失败：不打开流，但必须关闭内容源 ----------

    @Test
    void blankTitleIsRejectedWithoutOpeningTheFileButClosesTheSource() {
        assertRejectedBeforeStorage("   ", "notes.txt", "text/plain", TEXT_CONTENT,
                KnowledgeErrorCode.INVALID_TITLE);
    }

    @Test
    void overlongTitleIsRejectedWithoutOpeningTheFileButClosesTheSource() {
        assertRejectedBeforeStorage("t".repeat(201), "notes.txt", "text/plain", TEXT_CONTENT,
                KnowledgeErrorCode.INVALID_TITLE);
    }

    @Test
    void missingFileNameIsRejectedWithoutOpeningTheFileButClosesTheSource() {
        assertRejectedBeforeStorage("标题", null, "text/plain", TEXT_CONTENT,
                KnowledgeErrorCode.INVALID_ORIGINAL_FILENAME);
    }

    @Test
    void fileNameWithControlCharacterIsRejectedWithoutOpeningTheFileButClosesTheSource() {
        assertRejectedBeforeStorage("标题", "bad\u0000name.txt", "text/plain", TEXT_CONTENT,
                KnowledgeErrorCode.INVALID_ORIGINAL_FILENAME);
    }

    @Test
    void unsupportedExtensionIsRejectedWithoutOpeningTheFileButClosesTheSource() {
        assertRejectedBeforeStorage("标题", "archive.zip", "application/zip", TEXT_CONTENT,
                KnowledgeApplicationErrorCode.UNSUPPORTED_DOCUMENT_FORMAT);
    }

    @Test
    void incompatibleDeclaredTypeIsRejectedWithoutOpeningTheFileButClosesTheSource() {
        assertRejectedBeforeStorage("标题", "notes.txt", "application/pdf", TEXT_CONTENT,
                KnowledgeApplicationErrorCode.UNSUPPORTED_DOCUMENT_FORMAT);
    }

    @Test
    void declaredSizeAboveLimitIsRejectedWithoutOpeningTheFileButClosesTheSource() {
        KnowledgeTestSupport.ByteArrayContentSource source =
                KnowledgeTestSupport.ByteArrayContentSource.lyingAboutSize(TEXT_CONTENT, "notes.txt",
                        "text/plain", LIMIT + 1);

        assertApplicationError(() -> service().upload(new UploadKnowledgeDocumentCommand("标题", source)),
                KnowledgeApplicationErrorCode.DOCUMENT_TOO_LARGE);

        assertThat(source.opens()).as("声明大小已超限时不应打开文件").isZero();
        assertThat(source.closes()).as("内容源仍必须被关闭").isEqualTo(1);
        assertNothingTouched();
    }

    // ---------- ② 标识/时间端口失败：不碰存储，仍关闭内容源 ----------

    @Test
    void failingIdGeneratorIsMappedToInternalErrorAndTouchesNoStorage() {
        KnowledgeDocumentIdGenerator broken = new KnowledgeDocumentIdGenerator() {

            @Override
            public KnowledgeDocumentId nextId() {
                throw new IllegalStateException("内部生成器故障，含敏感细节");
            }
        };

        assertInternalPortFailure(broken, this.timeProvider);
    }

    @Test
    void nullIdFromGeneratorIsMappedToInternalErrorAndTouchesNoStorage() {
        assertInternalPortFailure(() -> null, this.timeProvider);
    }

    @Test
    void failingTimeProviderIsMappedToInternalErrorAndTouchesNoStorage() {
        KnowledgeTimeProvider broken = new KnowledgeTimeProvider() {

            @Override
            public Instant now() {
                throw new IllegalStateException("时钟不可用，含敏感细节");
            }
        };

        assertInternalPortFailure(this.idGenerator, broken);
    }

    @Test
    void nullTimeFromProviderIsMappedToInternalErrorAndTouchesNoStorage() {
        assertInternalPortFailure(this.idGenerator, () -> null);
    }

    // ---------- ③ 阶段 B：内容已发布但元数据未确认 → 补偿一次 ----------

    @Test
    void invalidStoreResultIsCompensatedAndMappedToInternalError() {
        // 端口的「安全键」只保证可以当路径片段用；长度上限属于领域不变量，
        // 因此超长但仍安全的键能通过端口、被领域拒绝 —— 这正是阶段 B 要处理的内部错误
        String oversizedKey = "k".repeat(KnowledgeDocument.MAX_CONTENT_KEY_LENGTH + 1);
        this.contentStore.useContentKey(oversizedKey);

        KnowledgeTestSupport.ByteArrayContentSource source =
                KnowledgeTestSupport.ByteArrayContentSource.text(TEXT_CONTENT, "notes.txt");

        KnowledgeApplicationException thrown = (KnowledgeApplicationException) org.assertj.core.api.Assertions
                .catchThrowable(() -> service().upload(new UploadKnowledgeDocumentCommand("标题", source)));

        assertThat(thrown.errorCode()).as("端口返回值不合法属于服务端内部错误")
                .isEqualTo(KnowledgeApplicationErrorCode.KNOWLEDGE_INTERNAL_ERROR);
        assertThat(this.contentStore.deleteCalls()).as("必须补偿删除恰好一次").isEqualTo(1);
        assertThat(this.contentStore.deletedKey()).isEqualTo(oversizedKey);
        assertThat(this.repository.insertCalls()).as("聚合都没构造成功，不得写元数据").isZero();
        assertThat(source.closes()).isEqualTo(1);
    }

    @Test
    void domainFailureFromPortValuesNeverFallsIntoTheBadRequestMapping() {
        this.contentStore.useContentKey("k".repeat(KnowledgeDocument.MAX_CONTENT_KEY_LENGTH + 1));

        KnowledgeApplicationException thrown = (KnowledgeApplicationException) org.assertj.core.api.Assertions
                .catchThrowable(() -> service().upload(new UploadKnowledgeDocumentCommand("标题",
                        KnowledgeTestSupport.ByteArrayContentSource.text(TEXT_CONTENT, "notes.txt"))));

        // 领域异常如果原样抛出，会被映射成 400（调用方输入问题）—— 那会把服务端故障报成客户端错误
        assertThat(thrown.errorCode())
                .isNotEqualTo(KnowledgeApplicationErrorCode.INVALID_UPLOAD_COMMAND);
        assertThat(thrown.getCause()).isInstanceOf(com.flowdesk.domain.knowledge.KnowledgeDomainException.class);
        assertThat(this.contentStore.deleteCalls()).isEqualTo(1);
    }

    @Test
    void repositoryFailureIsCompensatedExactlyOnce() {
        this.repository.failInsertWith(new IllegalStateException("数据库写入失败"));

        assertApplicationError(() -> service().upload(new UploadKnowledgeDocumentCommand("标题",
                KnowledgeTestSupport.ByteArrayContentSource.text(TEXT_CONTENT, "notes.txt"))),
                KnowledgeApplicationErrorCode.METADATA_STORAGE_FAILURE);

        assertThat(this.contentStore.deleteCalls()).as("恰好补偿一次").isEqualTo(1);
        assertThat(this.contentStore.calls()).containsExactly("store", "delete");
    }

    @Test
    void repositoryReturningAnIllegalResultIsAlsoCompensated() {
        // 端口返回 version < 0 的非法结果：适配器构造时就会失败，服务端必须当作阶段 B 失败处理
        this.repository.failInsertWith(new IllegalArgumentException("version 不能为负数"));

        KnowledgeApplicationException thrown = (KnowledgeApplicationException) org.assertj.core.api.Assertions
                .catchThrowable(() -> service().upload(new UploadKnowledgeDocumentCommand("标题",
                        KnowledgeTestSupport.ByteArrayContentSource.text(TEXT_CONTENT, "notes.txt"))));

        assertThat(thrown.errorCode()).isEqualTo(KnowledgeApplicationErrorCode.METADATA_STORAGE_FAILURE);
        assertThat(this.contentStore.deleteCalls()).isEqualTo(1);
    }

    @Test
    void compensationFailureDoesNotOverrideThePrimaryFailure() {
        this.repository.failInsertWith(new IllegalStateException("元数据写入失败"));
        this.contentStore.failDeleteWith(new IllegalStateException("补偿删除也失败了"));

        KnowledgeApplicationException thrown = (KnowledgeApplicationException) org.assertj.core.api.Assertions
                .catchThrowable(() -> service().upload(new UploadKnowledgeDocumentCommand("标题",
                        KnowledgeTestSupport.ByteArrayContentSource.text(TEXT_CONTENT, "notes.txt"))));

        assertThat(thrown.errorCode()).isEqualTo(KnowledgeApplicationErrorCode.METADATA_STORAGE_FAILURE);
        assertThat(thrown.getMessage()).as("对外仍是固定安全文案").isEqualTo("元数据写入失败");
        assertThat(thrown.getSuppressed()).as("补偿失败作为 suppressed 保留给服务端诊断").hasSize(1);
        assertThat(thrown.getSuppressed()[0]).hasMessage("补偿删除也失败了");
        assertThat(this.contentStore.deleteCalls()).isEqualTo(1);
    }

    // ---------- ④ 阶段 A / C：不该补偿的地方绝不多删 ----------

    @Test
    void storeFailureBeforePublishIsNotCompensated() {
        this.contentStore.failStoreWith(new KnowledgeApplicationException(
                KnowledgeApplicationErrorCode.EMPTY_DOCUMENT_CONTENT, "上传内容为空"));

        assertApplicationError(() -> service().upload(new UploadKnowledgeDocumentCommand("标题",
                KnowledgeTestSupport.ByteArrayContentSource.text(TEXT_CONTENT, "notes.txt"))),
                KnowledgeApplicationErrorCode.EMPTY_DOCUMENT_CONTENT);

        assertThat(this.contentStore.deleteCalls()).as("内容尚未发布，没有可补偿的对象").isZero();
    }

    @Test
    void successfulUploadNeverDeletesContent() {
        service().upload(new UploadKnowledgeDocumentCommand("标题",
                KnowledgeTestSupport.ByteArrayContentSource.text(TEXT_CONTENT, "notes.txt")));

        assertThat(this.contentStore.calls()).as("成功路径只有 store，没有 delete").containsExactly("store");
        assertThat(this.repository.calls()).containsExactly("insert");
    }

    @Test
    void everyPathClosesTheSourceExactlyOnce() {
        // 成功路径
        KnowledgeTestSupport.ByteArrayContentSource success =
                KnowledgeTestSupport.ByteArrayContentSource.text(TEXT_CONTENT, "notes.txt");
        service().upload(new UploadKnowledgeDocumentCommand("标题", success));
        assertThat(success.closes()).isEqualTo(1);

        // 存储失败路径
        this.contentStore.failStoreWith(new KnowledgeApplicationException(
                KnowledgeApplicationErrorCode.CONTENT_STORAGE_FAILURE, "失败"));
        KnowledgeTestSupport.ByteArrayContentSource storeFailure =
                KnowledgeTestSupport.ByteArrayContentSource.text(TEXT_CONTENT, "notes.txt");
        KnowledgeTestSupport.capture(() -> service().upload(
                new UploadKnowledgeDocumentCommand("标题", storeFailure)));
        assertThat(storeFailure.closes()).isEqualTo(1);
    }

    @Test
    void storedContentRejectsIllegalPortResults() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> new StoredContent(null, 1L,
                Sha256Digest.of("a".repeat(64))))).isInstanceOf(IllegalArgumentException.class);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> new StoredContent("  ", 1L,
                Sha256Digest.of("a".repeat(64))))).isInstanceOf(IllegalArgumentException.class);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> new StoredContent("../escape", 1L,
                Sha256Digest.of("a".repeat(64))))).isInstanceOf(IllegalArgumentException.class);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> new StoredContent("key", 0L,
                Sha256Digest.of("a".repeat(64))))).isInstanceOf(IllegalArgumentException.class);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> new StoredContent("key", 1L, null)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void versionedDocumentRejectsIllegalPortResults() {
        KnowledgeDocument document = KnowledgeDocument.create(KnowledgeTestSupport.DOCUMENT_ID,
                DocumentTitle.of("标题"), OriginalFilename.of("notes.txt"), DocumentFormat.TEXT, "text/plain",
                1L, Sha256Digest.of("a".repeat(64)), "key", UPLOADED_AT);

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> new VersionedKnowledgeDocument(null, 0L)))
                .isInstanceOf(NullPointerException.class);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> new VersionedKnowledgeDocument(document, -1L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- 辅助 ----------

    private void assertRejectedBeforeStorage(String title, String fileName, String contentType, byte[] content,
            KnowledgeApplicationErrorCode expected) {

        KnowledgeTestSupport.ByteArrayContentSource source =
                KnowledgeTestSupport.ByteArrayContentSource.of(content, fileName, contentType);

        assertApplicationError(() -> service().upload(new UploadKnowledgeDocumentCommand(title, source)),
                expected);

        assertThat(source.opens()).as("前置校验失败不得打开文件").isZero();
        assertThat(source.closes()).as("前置校验失败仍必须关闭内容源").isEqualTo(1);
        assertNothingTouched();
    }

    private void assertRejectedBeforeStorage(String title, String fileName, String contentType, byte[] content,
            KnowledgeErrorCode expected) {

        KnowledgeTestSupport.ByteArrayContentSource source =
                KnowledgeTestSupport.ByteArrayContentSource.of(content, fileName, contentType);

        KnowledgeTestSupport.assertDomainError(
                () -> service().upload(new UploadKnowledgeDocumentCommand(title, source)), expected);

        assertThat(source.opens()).isZero();
        assertThat(source.closes()).isEqualTo(1);
        assertNothingTouched();
    }

    private void assertInternalPortFailure(KnowledgeDocumentIdGenerator generator,
            KnowledgeTimeProvider provider) {

        KnowledgeDocumentApplicationService brokenService = new KnowledgeDocumentApplicationService(
                this.repository, this.contentStore, generator, provider, LIMIT);

        KnowledgeTestSupport.ByteArrayContentSource source =
                KnowledgeTestSupport.ByteArrayContentSource.text(TEXT_CONTENT, "notes.txt");

        KnowledgeApplicationException thrown = (KnowledgeApplicationException) org.assertj.core.api.Assertions
                .catchThrowable(() -> brokenService.upload(
                        new UploadKnowledgeDocumentCommand("标题", source)));

        assertThat(thrown.errorCode()).isEqualTo(KnowledgeApplicationErrorCode.KNOWLEDGE_INTERNAL_ERROR);
        assertThat(thrown).as("不得把 NPE 或端口内部文案暴露出去")
                .isNotInstanceOf(NullPointerException.class);
        assertThat(thrown.getMessage()).doesNotContain("敏感细节").doesNotContain("NPE");
        assertThat(source.closes()).as("端口失败也必须关闭内容源").isEqualTo(1);
        assertThat(source.opens()).isZero();
        assertNothingTouched();
    }

    private void assertNothingTouched() {
        assertThat(this.contentStore.storeCalls()).isZero();
        assertThat(this.contentStore.deleteCalls()).isZero();
        assertThat(this.repository.insertCalls()).isZero();
    }
}
