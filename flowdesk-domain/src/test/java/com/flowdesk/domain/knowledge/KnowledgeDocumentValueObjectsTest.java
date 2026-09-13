package com.flowdesk.domain.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 领域值对象测试：标题、原始文件名、摘要、格式。
 */
class KnowledgeDocumentValueObjectsTest {

    // ---------- DocumentTitle ----------

    @Test
    void acceptsNormalizedTitle() {
        assertThat(DocumentTitle.of("季度运维报告").value()).isEqualTo("季度运维报告");
        assertThat(DocumentTitle.of("a".repeat(DocumentTitle.MAX_LENGTH)).value())
                .hasSize(DocumentTitle.MAX_LENGTH);
    }

    @Test
    void rejectsNullOrEmptyTitle() {
        assertError(() -> DocumentTitle.of(null), KnowledgeErrorCode.INVALID_TITLE);
        assertError(() -> DocumentTitle.of(""), KnowledgeErrorCode.INVALID_TITLE);
    }

    @Test
    void rejectsTitleWithSurroundingWhitespaceInsteadOfSilentlyStripping() {
        // 刻意不做静默 strip：值对象代表「已规范化」的输入，悄悄改正会让问题一直看不出来
        assertError(() -> DocumentTitle.of(" 报告"), KnowledgeErrorCode.INVALID_TITLE);
        assertError(() -> DocumentTitle.of("报告 "), KnowledgeErrorCode.INVALID_TITLE);
        assertError(() -> DocumentTitle.of("\u3000报告\u3000"), KnowledgeErrorCode.INVALID_TITLE);
    }

    @Test
    void rejectsOverlongTitle() {
        assertError(() -> DocumentTitle.of("a".repeat(DocumentTitle.MAX_LENGTH + 1)),
                KnowledgeErrorCode.INVALID_TITLE);
    }

    // ---------- OriginalFilename ----------

    @Test
    void acceptsPlainFileName() {
        assertThat(OriginalFilename.of("report.pdf").value()).isEqualTo("report.pdf");
        assertThat(OriginalFilename.of("带空格 的名字.md").value()).isEqualTo("带空格 的名字.md");
        assertThat(OriginalFilename.of("a".repeat(OriginalFilename.MAX_LENGTH)).value())
                .hasSize(OriginalFilename.MAX_LENGTH);
    }

    @Test
    void rejectsNullOrEmptyOrUnstrippedFileName() {
        assertError(() -> OriginalFilename.of(null), KnowledgeErrorCode.INVALID_ORIGINAL_FILENAME);
        assertError(() -> OriginalFilename.of(""), KnowledgeErrorCode.INVALID_ORIGINAL_FILENAME);
        assertError(() -> OriginalFilename.of(" report.txt"), KnowledgeErrorCode.INVALID_ORIGINAL_FILENAME);
        assertError(() -> OriginalFilename.of("report.txt "), KnowledgeErrorCode.INVALID_ORIGINAL_FILENAME);
    }

    @ParameterizedTest(name = "[{0}] 必须被拒绝")
    @ValueSource(strings = {
            "../etc/passwd",
            "..\\..\\windows\\system32\\config",
            "docs/report.pdf",
            "docs\\report.pdf",
            "/absolute/path.txt",
            "C:\\fakepath\\report.txt",
            "report\u0000.txt",
            "report\u0001.txt",
            "report\n.txt",
            "report\r.txt",
            "report\t.txt",
    })
    void rejectsAnythingThatIsNotABareFileName(String candidate) {
        // 这是安全不变量：文件名会被展示与传递，允许分隔符或控制字符就等于把路径穿越的种子存进库里
        assertError(() -> OriginalFilename.of(candidate), KnowledgeErrorCode.INVALID_ORIGINAL_FILENAME);
    }

    @Test
    void rejectsOverlongFileName() {
        assertError(() -> OriginalFilename.of("a".repeat(OriginalFilename.MAX_LENGTH + 1)),
                KnowledgeErrorCode.INVALID_ORIGINAL_FILENAME);
    }

    @Test
    void extractsExtensionCaseInsensitively() {
        assertThat(OriginalFilename.of("REPORT.PDF").extension()).isEqualTo("pdf");
        assertThat(OriginalFilename.of("notes.Md").extension()).isEqualTo("md");
        assertThat(OriginalFilename.of("no-extension").extension()).isEmpty();
        assertThat(OriginalFilename.of("trailing.").extension()).isEmpty();
    }

    @Test
    void fileNameErrorsNeverEchoTheSubmittedValue() {
        String sentinel = "sentinel-../../evil";

        assertThatThrownBy(() -> OriginalFilename.of(sentinel)).hasMessageNotContaining(sentinel);
    }

    // ---------- Sha256Digest ----------

    @Test
    void acceptsLowercaseHexAndNormalizesCase() {
        String digest = "a".repeat(64);

        assertThat(Sha256Digest.of(digest).value()).isEqualTo(digest);
        assertThat(Sha256Digest.of(digest.toUpperCase()).value()).as("统一转小写").isEqualTo(digest);
        assertThat(Sha256Digest.of("0123456789abcdef".repeat(4)).value()).hasSize(64);
    }

    @Test
    void rejectsInvalidDigests() {
        assertError(() -> Sha256Digest.of(null), KnowledgeErrorCode.INVALID_DIGEST);
        assertError(() -> Sha256Digest.of(""), KnowledgeErrorCode.INVALID_DIGEST);
        assertError(() -> Sha256Digest.of("a".repeat(63)), KnowledgeErrorCode.INVALID_DIGEST);
        assertError(() -> Sha256Digest.of("a".repeat(65)), KnowledgeErrorCode.INVALID_DIGEST);
        assertError(() -> Sha256Digest.of("z".repeat(64)), KnowledgeErrorCode.INVALID_DIGEST);
        assertError(() -> Sha256Digest.of("g".repeat(64)), KnowledgeErrorCode.INVALID_DIGEST);
        assertError(() -> Sha256Digest.of("a".repeat(63) + " "), KnowledgeErrorCode.INVALID_DIGEST);
    }

    // ---------- DocumentFormat ----------

    @Test
    void recognizesSupportedExtensionsCaseInsensitively() {
        assertThat(DocumentFormat.fromFileName("report.pdf")).contains(DocumentFormat.PDF);
        assertThat(DocumentFormat.fromFileName("Report.PDF")).contains(DocumentFormat.PDF);
        assertThat(DocumentFormat.fromFileName("doc.DocX")).contains(DocumentFormat.DOCX);
        assertThat(DocumentFormat.fromFileName("notes.md")).contains(DocumentFormat.MARKDOWN);
        assertThat(DocumentFormat.fromFileName("notes.txt")).contains(DocumentFormat.TEXT);
    }

    @Test
    void recognizesFromExtensionWithOrWithoutDot() {
        assertThat(DocumentFormat.fromExtension("pdf")).contains(DocumentFormat.PDF);
        assertThat(DocumentFormat.fromExtension(".pdf")).contains(DocumentFormat.PDF);
        assertThat(DocumentFormat.fromExtension(" PDF ")).contains(DocumentFormat.PDF);
    }

    @Test
    void rejectsUnsupportedOrMissingExtensions() {
        assertThat(DocumentFormat.fromFileName("archive.zip")).isEmpty();
        assertThat(DocumentFormat.fromFileName("legacy.doc")).isEmpty();
        assertThat(DocumentFormat.fromFileName("image.png")).isEmpty();
        assertThat(DocumentFormat.fromFileName("README")).isEmpty();
        assertThat(DocumentFormat.fromFileName("trailing.")).isEmpty();
        assertThat(DocumentFormat.fromFileName(null)).isEmpty();
        assertThat(DocumentFormat.fromExtension(null)).isEmpty();
        assertThat(DocumentFormat.fromExtension("")).isEmpty();
    }

    @Test
    void usesTheLastDotOnly() {
        assertThat(DocumentFormat.fromFileName("v1.2.report.pdf")).contains(DocumentFormat.PDF);
        assertThat(DocumentFormat.fromFileName("report.pdf.txt")).contains(DocumentFormat.TEXT);
    }

    @Test
    void exposesCanonicalMediaTypesAndBinaryFlag() {
        assertThat(DocumentFormat.PDF.canonicalMediaType()).isEqualTo("application/pdf");
        assertThat(DocumentFormat.DOCX.canonicalMediaType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(DocumentFormat.MARKDOWN.canonicalMediaType()).isEqualTo("text/markdown");
        assertThat(DocumentFormat.TEXT.canonicalMediaType()).isEqualTo("text/plain");
        assertThat(DocumentFormat.PDF.binary()).isTrue();
        assertThat(DocumentFormat.DOCX.binary()).isTrue();
        assertThat(DocumentFormat.MARKDOWN.binary()).isFalse();
        assertThat(DocumentFormat.TEXT.binary()).isFalse();
    }

    @ParameterizedTest(name = "扩展名 {0} 属于 {1}")
    @CsvSource({ "pdf, PDF", "docx, DOCX", "md, MARKDOWN", "txt, TEXT" })
    void extensionMatchesTheEnumNameContract(String extension, DocumentFormat expected) {
        assertThat(DocumentFormat.fromExtension(extension)).contains(expected);
        assertThat(expected.extension()).isEqualTo(extension);
    }

    // ---------- 辅助 ----------

    private static void assertError(Runnable callable, KnowledgeErrorCode expected) {
        assertThatThrownBy(callable::run)
                .isInstanceOf(KnowledgeDomainException.class)
                .extracting(thrown -> ((KnowledgeDomainException) thrown).errorCode())
                .isEqualTo(expected);
    }
}
