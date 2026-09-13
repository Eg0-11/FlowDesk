package com.flowdesk.infrastructure.knowledge.storage;

import static com.flowdesk.infrastructure.knowledge.KnowledgeTestContent.sha256Hex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.application.knowledge.port.out.StoredContent;
import com.flowdesk.domain.knowledge.KnowledgeDocumentId;
import com.flowdesk.infrastructure.knowledge.KnowledgeTestContent.ArrayContentSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 本地文件系统内容存储测试。
 *
 * <p>全部在真实临时目录上运行：真实写入、真实原子移动、真实清理。
 * 断言的是文件系统上可观察的事实，而不是「方法被调用过」。</p>
 */
class LocalFileSystemKnowledgeContentStoreTest {

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

    // ---------- 正常写入 ----------

    @Test
    void storesContentAndReportsTheRealSizeAndDigest() throws IOException {
        byte[] content = "知识库原始文件内容\n".getBytes(StandardCharsets.UTF_8);

        StoredContent stored = this.store.store(DOCUMENT_ID, ArrayContentSource.of(content, "报告.txt"));

        assertThat(stored.contentKey()).isEqualTo("kdoc-" + DOCUMENT_ID.value());
        assertThat(stored.sizeBytes()).isEqualTo(content.length);
        assertThat(stored.sha256().value()).isEqualTo(sha256Hex(content));

        Path objectPath = this.root.resolve("documents").resolve(stored.contentKey());
        assertThat(Files.exists(objectPath)).isTrue();
        assertThat(Files.readAllBytes(objectPath)).as("落盘内容必须与上传字节完全一致").isEqualTo(content);
    }

    @Test
    void contentKeyDependsOnlyOnTheDocumentId() throws IOException {
        // 即使文件名恶意，存储路径也只由文档标识决定
        StoredContent stored = this.store.store(DOCUMENT_ID,
                ArrayContentSource.of("x".getBytes(StandardCharsets.UTF_8), "../../../etc/passwd"));

        assertThat(stored.contentKey()).isEqualTo("kdoc-" + DOCUMENT_ID.value());
        assertThat(stored.contentKey()).doesNotContain("/").doesNotContain("\\").doesNotContain("passwd");

        Path expected = this.root.resolve("documents").resolve("kdoc-" + DOCUMENT_ID.value());
        assertThat(Files.exists(expected)).isTrue();

        // 根目录之外没有产生任何文件
        try (Stream<Path> outside = Files.walk(this.tempDirectory)) {
            List<Path> outsideRoot = outside
                    .filter(Files::isRegularFile)
                    .filter(path -> !path.startsWith(this.root))
                    .toList();
            assertThat(outsideRoot).isEmpty();
        }
    }

    @Test
    void leavesNoTemporaryFileBehindOnSuccess() throws IOException {
        this.store.store(DOCUMENT_ID, ArrayContentSource.of("abc".getBytes(StandardCharsets.UTF_8), "a.txt"));

        assertThat(tempFiles()).isEmpty();
    }

    @Test
    void createsDirectoriesOnDemand() {
        assertThat(Files.exists(this.root)).isFalse();

        this.store.store(DOCUMENT_ID, ArrayContentSource.of("abc".getBytes(StandardCharsets.UTF_8), "a.txt"));

        assertThat(Files.isDirectory(this.root.resolve("documents"))).isTrue();
        assertThat(Files.isDirectory(this.root.resolve("tmp"))).isTrue();
    }

    @Test
    void handlesContentLargerThanTheCopyBuffer() throws IOException {
        byte[] content = new byte[200 * 1024];
        for (int index = 0; index < content.length; index++) {
            content[index] = (byte) (index % 251);
        }

        StoredContent stored = this.store.store(DOCUMENT_ID, ArrayContentSource.of(content, "big.bin"));

        assertThat(stored.sizeBytes()).isEqualTo(content.length);
        assertThat(stored.sha256().value()).isEqualTo(sha256Hex(content));
        assertThat(Files.readAllBytes(this.root.resolve("documents").resolve(stored.contentKey())))
                .isEqualTo(content);
    }

    @Test
    void identicalContentCanBeStoredUnderDifferentDocumentIds() throws IOException {
        // 相同内容允许多次上传：内容键由文档标识派生，因此不会互相覆盖
        byte[] content = "same bytes".getBytes(StandardCharsets.UTF_8);
        KnowledgeDocumentId second = KnowledgeDocumentId.of(UUID.randomUUID());

        StoredContent first = this.store.store(DOCUMENT_ID, ArrayContentSource.of(content, "a.txt"));
        StoredContent other = this.store.store(second, ArrayContentSource.of(content, "b.txt"));

        assertThat(first.contentKey()).isNotEqualTo(other.contentKey());
        assertThat(first.sha256()).as("摘要相同但对象是两个").isEqualTo(other.sha256());
        assertThat(Files.exists(this.root.resolve("documents").resolve(first.contentKey()))).isTrue();
        assertThat(Files.exists(this.root.resolve("documents").resolve(other.contentKey()))).isTrue();
    }

    // ---------- 拒绝与清理 ----------

    @Test
    void rejectsEmptyContentAndLeavesNoTrace() throws IOException {
        assertError(KnowledgeApplicationErrorCode.EMPTY_DOCUMENT_CONTENT,
                () -> this.store.store(DOCUMENT_ID, ArrayContentSource.of(new byte[0], "empty.txt")));

        assertThat(Files.exists(this.root.resolve("documents").resolve("kdoc-" + DOCUMENT_ID.value())))
                .as("空内容不能留下对象").isFalse();
        assertThat(tempFiles()).as("临时文件必须被清理").isEmpty();
    }

    @Test
    void refusesToOverwriteAnExistingObject() throws IOException {
        byte[] original = "original".getBytes(StandardCharsets.UTF_8);
        StoredContent first = this.store.store(DOCUMENT_ID, ArrayContentSource.of(original, "a.txt"));

        assertError(KnowledgeApplicationErrorCode.CONTENT_STORAGE_FAILURE,
                () -> this.store.store(DOCUMENT_ID, ArrayContentSource.of("replacement".getBytes(
                        StandardCharsets.UTF_8), "a.txt")));

        assertThat(Files.readAllBytes(this.root.resolve("documents").resolve(first.contentKey())))
                .as("原对象必须保持不变").isEqualTo(original);
        assertThat(tempFiles()).isEmpty();
    }

    @Test
    void cleansUpTemporaryFileWhenTheStreamFails() throws IOException {
        ArrayContentSource source = ArrayContentSource.failingAfter(
                "partial content that will fail".getBytes(StandardCharsets.UTF_8), 8);

        assertError(KnowledgeApplicationErrorCode.CONTENT_STORAGE_FAILURE,
                () -> this.store.store(DOCUMENT_ID, source));

        assertThat(tempFiles()).as("读取失败后临时文件必须被清理").isEmpty();
        assertThat(Files.exists(this.root.resolve("documents").resolve("kdoc-" + DOCUMENT_ID.value())))
                .isFalse();
    }

    @Test
    void cleansUpTemporaryFileWhenTheApplicationStreamRejectsTheContent() throws IOException {
        ArrayContentSource source = ArrayContentSource.failingWith(new KnowledgeApplicationException(
                KnowledgeApplicationErrorCode.DOCUMENT_TOO_LARGE, "超限"));

        assertError(KnowledgeApplicationErrorCode.DOCUMENT_TOO_LARGE,
                () -> this.store.store(DOCUMENT_ID, source));

        assertThat(tempFiles()).isEmpty();
    }

    @Test
    void rejectsNullArguments() {
        assertThatThrownBy(() -> this.store.store(null, ArrayContentSource.of(new byte[] { 1 }, "a")))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> this.store.store(DOCUMENT_ID, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LocalFileSystemKnowledgeContentStore(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ---------- 删除与路径安全 ----------

    @Test
    void deletesStoredContent() throws IOException {
        StoredContent stored = this.store.store(DOCUMENT_ID,
                ArrayContentSource.of("abc".getBytes(StandardCharsets.UTF_8), "a.txt"));
        Path objectPath = this.root.resolve("documents").resolve(stored.contentKey());
        assertThat(Files.exists(objectPath)).isTrue();

        assertThat(this.store.delete(stored.contentKey())).isTrue();
        assertThat(Files.exists(objectPath)).isFalse();
        assertThat(this.store.delete(stored.contentKey())).as("再次删除返回 false 而不是失败").isFalse();
    }

    @Test
    void rejectsContentKeysThatTryToEscapeTheRoot() {
        for (String dangerous : new String[] {
                "../escape",
                "..",
                ".",
                "a/b",
                "a\\b",
                "/etc/passwd",
                "C:\\fakepath\\x",
                "",
                "  ",
                "-leading-dash",
                "key\u0000",
        }) {
            assertError(KnowledgeApplicationErrorCode.CONTENT_STORAGE_FAILURE, () -> this.store.delete(dangerous));
        }
    }

    @Test
    void rejectsContentKeysThatTryToEscapeTheRootWhenStoringNothingIsReadableOutside() throws IOException {
        // 直接构造越界键是不可能的（键由标识生成），这里验证解析路径的守卫本身
        assertThatThrownBy(() -> this.store.delete(".." + java.io.File.separator + "escaped"))
                .isInstanceOf(KnowledgeApplicationException.class);

        try (Stream<Path> outside = Files.walk(this.tempDirectory)) {
            assertThat(outside.filter(Files::isRegularFile).toList()).isEmpty();
        }
    }

    @Test
    void rootIsNormalizedAndExposed() {
        assertThat(this.store.root()).isEqualTo(this.root.toAbsolutePath().normalize());
    }

    // ---------- 辅助 ----------

    private List<Path> tempFiles() throws IOException {
        Path tempRoot = this.root.resolve("tmp");
        if (!Files.isDirectory(tempRoot)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(tempRoot)) {
            return files.toList();
        }
    }

    private static void assertError(KnowledgeApplicationErrorCode expected, Runnable callable) {
        assertThatThrownBy(callable::run)
                .isInstanceOf(KnowledgeApplicationException.class)
                .extracting(thrown -> ((KnowledgeApplicationException) thrown).errorCode())
                .isEqualTo(expected);
    }
}
