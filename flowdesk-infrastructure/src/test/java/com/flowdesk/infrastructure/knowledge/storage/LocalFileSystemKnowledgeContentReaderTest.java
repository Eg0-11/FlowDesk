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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 原始内容读取端口测试（FD-0009；符号链接安全边界收口见 FD-0020-F）。
 *
 * <p>读取是解析的第一步，也是唯一会把「存储内部坐标」变成数据的地方，
 * 因此这里重点验证：读到的就是当初写进去的字节、路径逃逸/符号链接/缺失对象一律拒绝、
 * 失败信息不含真实路径。</p>
 *
 * <p><b>符号链接用例的前提核实（FD-0020-F）</b>：实测个别 Windows 环境下
 * {@code Files.createSymbolicLink} 返回成功，但落盘的是一个 0 字节普通文件
 * （{@code fsutil reparsepoint query} 报「不是一个重分析点」）。
 * 因此符号链接用例在构造后必须核实实际创建的对象确实是符号链接，
 * 否则<b>如实跳过</b>——不能把「读到了一个普通文件」当成「拒绝了符号链接」。</p>
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
        this.createSymlinkOrSkip(documentsRoot.resolve("kdoc-link"), outside);

        assertApplicationError(() -> this.store.openStream("kdoc-link"),
                KnowledgeApplicationErrorCode.DOCUMENT_CONTENT_UNREADABLE);
    }

    @Test
    void aSymlinkWithAnAbsoluteTargetOutsideTheStorageRootIsNotFollowed() throws IOException {
        Path outside = this.tempDirectory.resolve("absolute-secret.txt");
        Files.writeString(outside, "ABSOLUTE-OUTSIDE-SECRET", StandardCharsets.UTF_8);

        Path documentsRoot = this.root.resolve("documents");
        Files.createDirectories(documentsRoot);
        this.createSymlinkOrSkip(documentsRoot.resolve("kdoc-abs-link"), outside.toAbsolutePath());

        assertApplicationError(() -> this.store.openStream("kdoc-abs-link"),
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

    /**
     * 构造符号链接场景：创建之后<b>必须核实实际创建出来的对象</b>，
     * 不能只凭 {@link Files#createSymbolicLink} 返回成功就认定场景已构造（FD-0020-F）。
     *
     * <p>实测有个别 Windows 环境下该方法<b>返回成功</b>，但落盘的是一个 0 字节普通文件 ——
     * Win32 权威核查 {@code fsutil reparsepoint query} 报「不是一个重分析点」（错误 4390），
     * JDK 的 NOFOLLOW 属性也报 {@code isSymbolicLink=false}。此时符号链接场景根本不存在，
     * 测试无法覆盖，只能<b>如实跳过</b>，而不是把「读了个普通文件」当成「拒绝符号链接」通过。</p>
     */
    private Path createSymlinkOrSkip(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        }
        catch (IOException | UnsupportedOperationException | SecurityException ex) {
            // Windows 上创建符号链接需要特权：无法构造该场景时跳过，而不是假装通过了测试
            assumeTrue(false, "当前环境不支持创建符号链接（" + ex.getClass().getSimpleName() + "），跳过");
        }
        BasicFileAttributes attributes =
                Files.readAttributes(link, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        assumeTrue(attributes.isSymbolicLink(),
                "createSymbolicLink 返回成功，但实际创建的对象不是符号链接（NOFOLLOW 属性 isSymbolicLink=false，"
                        + "isRegularFile=" + attributes.isRegularFile() + "，size=" + attributes.size()
                        + "）：平台无法构造符号链接场景，跳过");
        return link;
    }
}
