package com.flowdesk.infrastructure.knowledge.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowdesk.application.knowledge.port.out.DocumentTextParser;
import com.flowdesk.infrastructure.knowledge.KnowledgeConfiguration;
import com.flowdesk.infrastructure.knowledge.chunking.KnowledgeChunkingProperties;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.junit.jupiter.api.Test;

/**
 * PDF OCR 必须被显式关闭（FD-0009-R1）。
 *
 * <p>{@code PDFParser} 的默认 OCR 策略是 {@code AUTO}：机器上装了 Tesseract 时，
 * 解析会悄悄走 OCR 路径 —— 结果、耗时与资源消耗都随部署环境变化，违反「不实现 OCR」
 * 与「确定性」两条约定。这里同时锁定<b>单测构造路径</b>与<b>生产 Bean 路径</b>：</p>
 * <ul>
 *   <li>前者保证适配器自己不会退回 AUTO；</li>
 *   <li>后者直接调用 {@code KnowledgeConfiguration} 的 Bean 方法，因此断言的就是
 *       生产装配真正交给用例的那个解析器（不是另一个「应该一样」的实例）。</li>
 * </ul>
 */
class PdfOcrIsDisabledTest {

    @Test
    void theAdapterConfiguresNoOcr() {
        TikaDocumentTextParser parser = new TikaDocumentTextParser(1000);

        assertThat(parser.pdfParserConfig().getOcrStrategy())
                .isEqualTo(PDFParserConfig.OCR_STRATEGY.NO_OCR);
    }

    @Test
    void theProductionBeanAlsoConfiguresNoOcr() {
        DocumentTextParser bean = new KnowledgeConfiguration()
                .documentTextParser(new KnowledgeChunkingProperties());

        assertThat(bean).isInstanceOf(TikaDocumentTextParser.class);
        assertThat(((TikaDocumentTextParser) bean).pdfParserConfig().getOcrStrategy())
                .as("生产 Bean 使用的解析器必须确定为 NO_OCR，而不是依赖环境是否装了 Tesseract")
                .isEqualTo(PDFParserConfig.OCR_STRATEGY.NO_OCR);
    }

    @Test
    void theStrategyIsNotAUTO() {
        assertThat(new KnowledgeConfiguration().documentTextParser(new KnowledgeChunkingProperties()))
                .isInstanceOf(TikaDocumentTextParser.class);

        PDFParserConfig config = ((TikaDocumentTextParser) new KnowledgeConfiguration()
                .documentTextParser(new KnowledgeChunkingProperties())).pdfParserConfig();

        // AUTO 会在检测到 Tesseract 时自动开启 OCR：这正是必须排除的那一种取值
        assertThat(config.getOcrStrategy()).isNotEqualTo(PDFParserConfig.OCR_STRATEGY.AUTO);
        assertThat(config.getOcrStrategy()).isNotEqualTo(PDFParserConfig.OCR_STRATEGY.OCR_ONLY);
    }

    @Test
    void parsingDoesNotMutateTheSharedParserConfiguration() {
        TikaDocumentTextParser parser = new TikaDocumentTextParser(100_000);
        PDFParserConfig before = parser.pdfParserConfig();

        parser.parse(new java.io.ByteArrayInputStream(DocumentFixtures.pdf(DocumentFixtures.PDF_TEXT)),
                com.flowdesk.domain.knowledge.DocumentFormat.PDF);
        parser.parse(new java.io.ByteArrayInputStream(DocumentFixtures.pdf(DocumentFixtures.PDF_TEXT)),
                com.flowdesk.domain.knowledge.DocumentFormat.PDF);

        assertThat(parser.pdfParserConfig()).as("共享配置对象在解析过程中不得被替换").isSameAs(before);
        assertThat(parser.pdfParserConfig().getOcrStrategy())
                .as("解析前后 OCR 策略必须保持 NO_OCR")
                .isEqualTo(PDFParserConfig.OCR_STRATEGY.NO_OCR);
    }
}
