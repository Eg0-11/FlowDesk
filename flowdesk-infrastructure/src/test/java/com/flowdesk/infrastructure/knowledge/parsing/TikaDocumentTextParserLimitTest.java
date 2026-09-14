package com.flowdesk.infrastructure.knowledge.parsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowdesk.application.knowledge.parse.DocumentParsingException;
import com.flowdesk.domain.knowledge.DocumentFormat;
import com.flowdesk.domain.knowledge.KnowledgeParseFailureCode;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * 提取上限的边界测试（FD-0009-R1）。
 *
 * <p>上限的语义是「<b>最终输出</b>的 code point 数不超过配置值」：</p>
 * <ul>
 *   <li>开头的 UTF-8 BOM 不进入输出，因此<b>不占配额</b>；</li>
 *   <li>跨 8192 char 解码缓冲区的 supplementary code point 只计一次；</li>
 *   <li>二进制（PDF/DOCX）与文本（TXT/Markdown）走同一条计数规则。</li>
 * </ul>
 */
class TikaDocumentTextParserLimitTest {

    private static final String BOM = "\uFEFF";

    // ---------- BOM 不占配额 ----------

    @Test
    void aByteOrderMarkPlusExactlyTheLimitSucceeds() {
        TikaDocumentTextParser parser = new TikaDocumentTextParser(5);

        String extracted = parser.parse(
                new ByteArrayInputStream((BOM + "ABCDE").getBytes(StandardCharsets.UTF_8)),
                DocumentFormat.TEXT);

        assertThat(extracted).isEqualTo("ABCDE");
        assertThat(extracted.codePointCount(0, extracted.length())).isEqualTo(5);
    }

    @Test
    void aByteOrderMarkPlusOneMoreCodePointFails() {
        TikaDocumentTextParser parser = new TikaDocumentTextParser(5);

        assertThatThrownBy(() -> parser.parse(
                new ByteArrayInputStream((BOM + "ABCDEF").getBytes(StandardCharsets.UTF_8)),
                DocumentFormat.TEXT))
                .as("BOM 不占配额，因此 6 个正文 code point 必须超限")
                .isInstanceOf(DocumentParsingException.class)
                .extracting(thrown -> ((DocumentParsingException) thrown).failureCode())
                .isEqualTo(KnowledgeParseFailureCode.EXTRACTED_TEXT_TOO_LARGE);
    }

    @Test
    void aByteOrderMarkOnItsOwnProducesEmptyText() {
        TikaDocumentTextParser parser = new TikaDocumentTextParser(1);

        assertThat(parser.parse(new ByteArrayInputStream(BOM.getBytes(StandardCharsets.UTF_8)),
                DocumentFormat.TEXT)).isEmpty();
    }

    @Test
    void markdownIsSubjectToTheSameBomRule() {
        TikaDocumentTextParser parser = new TikaDocumentTextParser(4);

        assertThat(parser.parse(new ByteArrayInputStream((BOM + "#标题\n").getBytes(StandardCharsets.UTF_8)),
                DocumentFormat.MARKDOWN)).isEqualTo("#标题\n");
    }

    // ---------- 跨解码缓冲区边界 ----------

    @Test
    void aSupplementaryCodePointAcrossTheDecodeBufferIsCountedOnce() {
        // 8191 个 BMP 字符 + 1 个 emoji = 8192 个 code point、8193 个 char：
        // 代理对正好跨在 8192 char 的读取边界上
        String text = "a".repeat(8191) + "\uD83D\uDCF4";

        assertThat(new TikaDocumentTextParser(8192)
                .parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), DocumentFormat.TEXT))
                .as("上限 8192 时应当成功")
                .hasSize(8193);

        assertThatThrownBy(() -> new TikaDocumentTextParser(8191)
                .parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), DocumentFormat.TEXT))
                .as("实际是 8192 个 code point，上限 8191 必须失败")
                .isInstanceOf(DocumentParsingException.class)
                .extracting(thrown -> ((DocumentParsingException) thrown).failureCode())
                .isEqualTo(KnowledgeParseFailureCode.EXTRACTED_TEXT_TOO_LARGE);
    }

    @Test
    void aSupplementaryCodePointAtTheVeryEndIsNotDoubleCounted() {
        String text = "\uD83D\uDCF4";

        // 只有 1 个 code point：上限 1 必须成功，上限 1 之下没有更小的合法值
        assertThat(new TikaDocumentTextParser(1)
                .parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), DocumentFormat.TEXT))
                .isEqualTo(text);
    }

    // ---------- 二进制路径同样按最终输出计 ----------

    @Test
    void binaryExtractionSucceedsExactlyAtTheLimitAndFailsOneBelow() {
        byte[] pdf = DocumentFixtures.pdf(DocumentFixtures.PDF_TEXT);
        String extracted = new TikaDocumentTextParser(100_000).parse(new ByteArrayInputStream(pdf),
                DocumentFormat.PDF);
        int codePoints = extracted.codePointCount(0, extracted.length());
        assertThat(codePoints).isPositive();

        assertThat(new TikaDocumentTextParser(codePoints)
                .parse(new ByteArrayInputStream(pdf), DocumentFormat.PDF))
                .as("恰好等于上限时必须成功（上限对最终输出生效）")
                .isEqualTo(extracted);

        assertThatThrownBy(() -> new TikaDocumentTextParser(codePoints - 1)
                .parse(new ByteArrayInputStream(pdf), DocumentFormat.PDF))
                .isInstanceOf(DocumentParsingException.class)
                .extracting(thrown -> ((DocumentParsingException) thrown).failureCode())
                .isEqualTo(KnowledgeParseFailureCode.EXTRACTED_TEXT_TOO_LARGE);
    }

    @Test
    void docxExtractionCountsEveryEmittedCodePointIncludingStructuralNewlines() {
        byte[] docx = DocumentFixtures.docx();
        String extracted = new TikaDocumentTextParser(100_000)
                .parse(new ByteArrayInputStream(docx), DocumentFormat.DOCX);
        int codePoints = extracted.codePointCount(0, extracted.length());

        assertThat(codePoints).isPositive();
        assertThat(new TikaDocumentTextParser(codePoints)
                .parse(new ByteArrayInputStream(docx), DocumentFormat.DOCX))
                .isEqualTo(extracted);

        assertThatThrownBy(() -> new TikaDocumentTextParser(codePoints - 1)
                .parse(new ByteArrayInputStream(docx), DocumentFormat.DOCX))
                .isInstanceOf(DocumentParsingException.class)
                .extracting(thrown -> ((DocumentParsingException) thrown).failureCode())
                .isEqualTo(KnowledgeParseFailureCode.EXTRACTED_TEXT_TOO_LARGE);
    }
}
