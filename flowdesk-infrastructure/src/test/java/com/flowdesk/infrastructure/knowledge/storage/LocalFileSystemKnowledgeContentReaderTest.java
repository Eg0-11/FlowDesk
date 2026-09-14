package com.flowdesk.infrastructure.knowledge.storage;

import static com.flowdesk.infrastructure.knowledge.KnowledgeTestContent.sha256Hex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.application.knowledge.port.out.StoredContent;
import com.flowdesk.domain.knowledge.KnowledgeDocumentId;
import com.flowdesk.infrastructure.knowledge.KnowledgeTestContent.ArrayContentSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 原始内容读取端口测试（FD-0009）。
 *
 * <p>读取是解析的第一步，也是唯一会把「存储内部坐标」变成数据的地方，
 * 因此这里重点验证：读到的就是当初写进去的字节、路径逃逸/符号链接/缺失对象一律拒绝、
 * 失败信息不含真实路径。</p>
 */
class LocalFileSystemKnowledgeContentReaderTest {

    private static final KnowledgeDocumentId DOCUMENT_ID =
            KnowledgeDocumentId.of(UUID.fromString("11111111-2222-3333-4444-555555555555"));

    @TempDir
    Path tempDirectory;

    private Path root;

    private LocalFileSystemKnowledgeContentStore store;

    @BeforeEach
    void setUp() {
        this.root = this.tempDirectory.resolve("knowledge-root");
        this.store = new LocalFileSystemKnowledgeContentStore(this.root);
    }

    @Test
    void readsBackExactlyTheBytesThatWereStored() throws IOException {
        byte[] content = "知识库原始文件内容\n第二行 📄".getBytes(StandardCharsets.UTF_8);
        StoredContent stored = this.store.store(DOCUMENT_ID, ArrayContentSource.of(content, "报告.txt"));

        try (InputStream input = this.store.openStream(stored.contentKey())) {
            assertThat(input.readAllBytes()).isEqualTo(content);
        }
    }

    @Test
    void readerAndWriterShareTheSameContentKey() {
        byte[] content = "abc".getBytes(StandardCharsets.UTF_8);
        StoredContent stored = this.store.store(DOCUMENT_ID, ArrayContentSource.of(content, "a.txt"));

        assertThat(stored.contentKey()).isEqualTo("kdoc-" + DOCUMENT_ID.value());
        assertThat(stored.sha256().value()).isEqualTo(sha256Hex(content));
    }

    @Test
    void missingObjectIsReportedAsUnreadableContent() {
        assertApplicationError(
                () -> this.store.openStream("kdoc-" + UUID.randomUUID()),
                KnowledgeApplicationErrorCode.DOCUMENT_CONTENT_UNREADABLE);
    }

    @Test
    void pathTraversalKeysAreRejected() {
        for (String key : new String[] { "../outside.txt", "..", "documents/../../etc/passwd",
                "a/b", "a\\b", ".", "", " ", "kdoc-..-../x" }) {

            assertApplicationError(() -> this.store.openStream(key),
                    KnowledgeApplicationErrorCode.DOCUMENT_CONTENT_UNREADABLE);
        }
    }

    @Test
    void absolutePathKeysAreRejectedWithoutTouchingTheFileSystem() throws IOException {
        Path outside = this.tempDirectory.resolve("outside.txt");
        Files.writeString(outside, "OUTSIDE-SECRET", StandardCharsets.UTF_8);

        assertApplicationError(() -> this.store.openStream(outside.toAbsolutePath().toString()),
                KnowledgeApplicationErrorCode.DOCUMENT_CONTENT_UNREADABLE);
        assertApplicationError(() -> this.store.openStream("C:/Windows/win.ini"),
                KnowledgeApplicationErrorCode.DOCUMENT_CONTENT_UNREADABLE);
    }

    @Test
    void nullKeyIsRejected() {
        assertApplicationError(() -> this.store.openStream(null),
                KnowledgeApplicationErrorCode.DOCUMENT_CONTENT_UNREADABLE);
    }

    @Test
    void aDirectoryIsNotReadableContent() {
        assertApplicationError(() -> this.store.openStream("documents"),
                KnowledgeApplicationErrorCode.DOCUMENT_CONTENT_UNREADABLE);
    }

    @Test
    void symbolicLinksAreNotFollowed() throws IOException {
        Path outside = this.tempDirectory.resolve("secret-target.txt");
        Files.writeString(outside, "OUTSIDE-SECRET", StandardCharsets.UTF_8);

        Path documentsRoot = this.root.resolve("documents");
        Files.createDirectories(documentsRoot);
        Path link = documentsRoot.resolve("kdoc-link");

        try {
            Files.createSymbolicLink(link, outside);
        }
        catch (IOException | UnsupportedOperationException | SecurityException ex) {
            // Windows 上创建符号链接需要特权：无法构造该场景时跳过，而不是假装通过了测试
            assumeTrue(false, "当前环境不支持创建符号链接，跳过");
            return;
        }

        assertApplicationError(() -> this.store.openStream("kdoc-link"),
                KnowledgeApplicationErrorCode.DOCUMENT_CONTENT_UNREADABLE);
    }

    @Test
    void failuresNeverRevealTheStorageRootOrAbsolutePaths() {
        KnowledgeApplicationException thrown = (KnowledgeApplicationException) org.assertj.core.api.Assertions
                .catchThrowable(() -> this.store.openStream("../escape"));

        assertThat(thrown.getMessage())
                .doesNotContain(this.root.toAbsolutePath().toString())
                .doesNotContain(this.tempDirectory.toAbsolutePath().toString())
                .doesNotContain("..")
                .doesNotContain("escape");
    }

    @Test
    void theReturnedStreamIsIndependentlyReadableAndClosable() throws IOException {
        byte[] content = "first".getBytes(StandardCharsets.UTF_8);
        StoredContent stored = this.store.store(DOCUMENT_ID, ArrayContentSource.of(content, "a.txt"));

        try (InputStream first = this.store.openStream(stored.contentKey())) {
            assertThat(first.readAllBytes()).isEqualTo(content);
        }
        // 关闭之后再次打开仍然可用：读取不会消耗或删除对象
        try (InputStream second = this.store.openStream(stored.contentKey())) {
            assertThat(second.readAllBytes()).isEqualTo(content);
        }
    }

    private static void assertApplicationError(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable,
            KnowledgeApplicationErrorCode expected) {

        assertThatThrownBy(callable)
                .as(String.valueOf(expected))
                .isInstanceOf(KnowledgeApplicationException.class)
                .extracting(thrown -> ((KnowledgeApplicationException) thrown).errorCode())
                .isEqualTo(expected);
    }
}
