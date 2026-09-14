package com.flowdesk.infrastructure.knowledge.parsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowdesk.application.knowledge.parse.DocumentParsingException;
import com.flowdesk.domain.knowledge.DocumentFormat;
import com.flowdesk.domain.knowledge.KnowledgeParseFailureCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link TikaDocumentTextParser} 测试（FD-0009）。
 *
 * <p>用<b>真</b> PDF/DOCX（测试自己按规范拼出来的二进制）驱动真实 Tika 解析器，
 * 而不是替身：这样「格式判断、加密/损坏映射、外部实体、限量」这些安全边界
 * 才是被真实执行过的。</p>
 */
class TikaDocumentTextParserTest {

    private static final int GENEROUS_LIMIT = 100_000;

    private final TikaDocumentTextParser parser = new TikaDocumentTextParser(GENEROUS_LIMIT);

    @TempDir
    Path tempDirectory;

    // ---------- 文本与 Markdown ----------

    @Test
    void extractsPlainTextVerbatim() {
        String text = "第一段内容。\nSecond paragraph.\n";

        assertThat(parse(text.getBytes(StandardCharsets.UTF_8), DocumentFormat.TEXT)).isEqualTo(text);
    }

    @Test
    void extractsMarkdownWithoutTreatingSyntaxSpecially() {
        String markdown = "# 标题\n\n- 列表项 📄\n";

        assertThat(parse(markdown.getBytes(StandardCharsets.UTF_8), DocumentFormat.MARKDOWN))
                .as("Markdown 直接按 UTF-8 解码，不去掉标记")
                .isEqualTo(markdown);
    }

    @Test
    void stripsTheUtf8ByteOrderMark() {
        byte[] withBom = DocumentFixtures.textWithByteOrderMark("季度运维报告");

        assertThat(parse(withBom, DocumentFormat.TEXT))
                .as("BOM 不应进入提取文本，否则第一个切片会以不可见字符开头")
                .isEqualTo("季度运维报告");
    }

    @Test
    void emptyContentDecodesToAnEmptyString() {
        assertThat(parse(new byte[0], DocumentFormat.TEXT)).isEmpty();
        assertThat(parse(new byte[0], DocumentFormat.MARKDOWN)).isEmpty();
    }

    @Test
    void invalidUtf8IsReportedAsUnsupportedContent() {
        assertThatThrownBy(() -> parse(new byte[] { (byte) 0xC3, 0x28 }, DocumentFormat.TEXT))
                .as("严格解码：非法字节必须报错，而不是悄悄替换成 U+FFFD")
                .isInstanceOf(DocumentParsingException.class)
                .extracting(thrown -> ((DocumentParsingException) thrown).failureCode())
                .isEqualTo(KnowledgeParseFailureCode.UNSUPPORTED_DOCUMENT_CONTENT);
    }

    // ---------- 真实二进制格式 ----------

    @Test
    void extractsTextFromARealPdf() {
        String extracted = parse(DocumentFixtures.pdf(DocumentFixtures.PDF_TEXT), DocumentFormat.PDF);

        assertThat(extracted).contains(DocumentFixtures.PDF_TEXT);
    }

    @Test
    void extractsTextFromARealDocx() {
        String extracted = parse(DocumentFixtures.docx(), DocumentFormat.DOCX);

        assertThat(extracted).contains(DocumentFixtures.FIRST_PARAGRAPH);
        assertThat(extracted).contains("Second paragraph.");
    }

    @Test
    void extractsChineseFromDocxWithoutLosingCharacters() {
        String chinese = "知识运营平台：解析与切片。";
        String extracted = parse(DocumentFixtures.docx(chinese), DocumentFormat.DOCX);

        assertThat(extracted).contains(chinese);
    }

    // ---------- 伪造与损坏 ----------

    @Test
    void contentThatDoesNotMatchTheDeclaredFormatIsRejectedBeforeParsing() {
        byte[] textBytes = "plain text pretending to be a pdf".getBytes(StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> parse(textBytes, DocumentFormat.PDF))
                .isInstanceOf(DocumentParsingException.class)
                .extracting(thrown -> ((DocumentParsingException) thrown).failureCode())
                .isEqualTo(KnowledgeParseFailureCode.UNSUPPORTED_DOCUMENT_CONTENT);

        assertThatThrownBy(() -> parse(textBytes, DocumentFormat.DOCX))
                .isInstanceOf(DocumentParsingException.class)
                .extracting(thrown -> ((DocumentParsingException) thrown).failureCode())
                .isEqualTo(KnowledgeParseFailureCode.UNSUPPORTED_DOCUMENT_CONTENT);
    }

    @Test
    void aTruncatedPdfIsReportedAsCorrupted() {
        assertThatThrownBy(() -> parse(DocumentFixtures.truncatedPdf(), DocumentFormat.PDF))
                .isInstanceOf(DocumentParsingException.class)
                .extracting(thrown -> ((DocumentParsingException) thrown).failureCode())
                .isEqualTo(KnowledgeParseFailureCode.CORRUPTED_DOCUMENT);
    }

    @Test
    void aTruncatedDocxIsReportedAsCorrupted() {
        assertThatThrownBy(() -> parse(DocumentFixtures.truncatedDocx(), DocumentFormat.DOCX))
                .isInstanceOf(DocumentParsingException.class)
                .extracting(thrown -> ((DocumentParsingException) thrown).failureCode())
                .isEqualTo(KnowledgeParseFailureCode.CORRUPTED_DOCUMENT);
    }

    @Test
    void anEncryptedPdfIsReportedAsEncryptedRatherThanCorrupted() {
        assertThatThrownBy(() -> parse(DocumentFixtures.encryptedPdf(), DocumentFormat.PDF))
                .as("加密与损坏是两种不同的用户可操作结论，必须区分")
                .isInstanceOf(DocumentParsingException.class)
                .extracting(thrown -> ((DocumentParsingException) thrown).failureCode())
                .isEqualTo(KnowledgeParseFailureCode.ENCRYPTED_DOCUMENT);
    }

    // ---------- 安全边界 ----------

    @Test
    void externalEntitiesAreNeverResolved() throws IOException {
        Path secret = this.tempDirectory.resolve("secret.txt");
        Files.writeString(secret, "FLOWDESK-SECRET-CONTENT", StandardCharsets.UTF_8);

        byte[] malicious = DocumentFixtures.docxWithExternalEntity(secret.toUri().toString());

        String extracted;
        try {
            extracted = parse(malicious, DocumentFormat.DOCX);
        }
        catch (DocumentParsingException ex) {
            // 拒绝解析同样是可接受的结果：只要失败码是稳定的，且没有读到外部资源
            assertThat(ex.failureCode()).isIn(KnowledgeParseFailureCode.CORRUPTED_DOCUMENT,
                    KnowledgeParseFailureCode.PARSER_FAILURE, KnowledgeParseFailureCode.UNSUPPORTED_DOCUMENT_CONTENT);
            extracted = "";
        }

        assertThat(extracted)
                .as("无论成功还是失败，都绝不能把本地文件内容带进提取文本")
                .doesNotContain("FLOWDESK-SECRET-CONTENT");
    }

    @Test
    void textExtractionStopsAtTheConfiguredLimit() {
        TikaDocumentTextParser limited = new TikaDocumentTextParser(10);
        byte[] content = "一二三四五六七八九十十一".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> limited.parse(new ByteArrayInputStream(content), DocumentFormat.TEXT))
                .isInstanceOf(DocumentParsingException.class)
                .extracting(thrown -> ((DocumentParsingException) thrown).failureCode())
                .isEqualTo(KnowledgeParseFailureCode.EXTRACTED_TEXT_TOO_LARGE);
    }

    @Test
    void textExtractionAcceptsExactlyTheLimit() {
        TikaDocumentTextParser limited = new TikaDocumentTextParser(10);

        assertThat(limited.parse(new ByteArrayInputStream("一二三四五六七八九十"
                .getBytes(StandardCharsets.UTF_8)), DocumentFormat.TEXT)).hasSize(10);
    }

    @Test
    void binaryExtractionAlsoHonoursTheLimit() {
        // PDF 夹具的正文比 5 个 code point 长得多
        TikaDocumentTextParser limited = new TikaDocumentTextParser(5);

        assertThatThrownBy(() -> limited.parse(
                new ByteArrayInputStream(DocumentFixtures.pdf(DocumentFixtures.PDF_TEXT)), DocumentFormat.PDF))
                .isInstanceOf(DocumentParsingException.class)
                .extracting(thrown -> ((DocumentParsingException) thrown).failureCode())
                .isEqualTo(KnowledgeParseFailureCode.EXTRACTED_TEXT_TOO_LARGE);
    }

    @Test
    void theCallerStreamIsNeverClosedByTheParser() {
        assertThat(closedAfterParse(new byte[] { 'a', 'b' }, DocumentFormat.TEXT)).isFalse();
        assertThat(closedAfterParse(DocumentFixtures.pdf(DocumentFixtures.PDF_TEXT), DocumentFormat.PDF)).isFalse();
        assertThat(closedAfterParse(DocumentFixtures.docx(), DocumentFormat.DOCX)).isFalse();
    }

    @Test
    void rejectsIllegalConstructorArgumentsAndNulls() {
        assertThatThrownBy(() -> new TikaDocumentTextParser(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> this.parser.parse(null, DocumentFormat.TEXT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> this.parser.parse(new ByteArrayInputStream(new byte[0]), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void parserFailuresNeverExposeThirdPartyMessagesThroughTheFailureCode() {
        DocumentParsingException failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> parse(DocumentFixtures.truncatedPdf(), DocumentFormat.PDF), DocumentParsingException.class);

        assertThat(failure.failureCode()).isEqualTo(KnowledgeParseFailureCode.CORRUPTED_DOCUMENT);
        assertThat(failure).hasCauseInstanceOf(Throwable.class);
    }

    // ---------- 辅助 ----------

    private String parse(byte[] content, DocumentFormat format) {
        return this.parser.parse(new ByteArrayInputStream(content), format);
    }

    private boolean closedAfterParse(byte[] content, DocumentFormat format) {
        AtomicBoolean closed = new AtomicBoolean(false);
        InputStream tracking = new ByteArrayInputStream(content) {

            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };

        try {
            this.parser.parse(tracking, format);
        }
        catch (DocumentParsingException ex) {
            // 解析失败也照样不能关闭调用方的流
        }
        return closed.get();
    }
}
