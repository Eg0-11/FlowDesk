package com.flowdesk.infrastructure.knowledge.parsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowdesk.application.knowledge.parse.DocumentParsingException;
import com.flowdesk.domain.knowledge.DocumentFormat;
import com.flowdesk.domain.knowledge.KnowledgeParseFailureCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.microsoft.ooxml.OOXMLParser;
import org.apache.tika.sax.BodyContentHandler;
import org.junit.jupiter.api.Test;

/**
 * DOCX <b>包类型验证</b>测试（FD-0009-R1）。
 *
 * <p>FD-0009 只检查了 ZIP 文件头（{@code PK\x03\x04}），而 Tika 的 {@code OOXMLParser} 会按
 * <b>包自身的内容类型</b>选择子解析器：一份真实的 XLSX 因此会被当作 DOCX 收下，
 * 并提取出单元格文本（类型混淆）。这里锁定修复后的行为：</p>
 * <ul>
 *   <li>XLSX / PPTX / 普通 ZIP → {@code UNSUPPORTED_DOCUMENT_CONTENT}；</li>
 *   <li>「内容类型说是 DOCX、关系却指向别的部件」的双面包 → 同样拒绝；</li>
 *   <li>真的声明为 DOCX、但主文档部件损坏 → 仍然是 {@code CORRUPTED_DOCUMENT}；</li>
 *   <li>类型判定只看包内容，且发生在<b>正文提取之前</b>；</li>
 *   <li>临时资源用后即删，调用方的流始终不被关闭。</li>
 * </ul>
 */
class TikaDocumentTextParserTypeValidationTest {

    private static final String SPREADSHEET_SECRET = "SPREADSHEET_SECRET";

    private static final String SLIDE_SECRET = "SLIDE_SECRET";

    private static final int GENEROUS_LIMIT = 100_000;

    private final TikaDocumentTextParser parser = new TikaDocumentTextParser(GENEROUS_LIMIT);

    // ---------- ① 类型混淆必须被拒绝 ----------

    @Test
    void aRealSpreadsheetIsRejectedWhenDeclaredAsDocx() {
        assertUnsupported(DocumentFixtures.xlsx(SPREADSHEET_SECRET));
    }

    @Test
    void aRealPresentationIsRejectedWhenDeclaredAsDocx() {
        assertUnsupported(DocumentFixtures.pptx(SLIDE_SECRET));
    }

    @Test
    void aPlainZipIsRejectedWhenDeclaredAsDocx() {
        assertUnsupported(DocumentFixtures.plainZip());
    }

    @Test
    void aPackageThatDeclaresWordButRelatesToAnotherPartIsRejected() {
        // 内容类型里写着 WordprocessingML，但 officeDocument 关系指向 Excel 工作簿：
        // 只检查内容类型的实现会放它过去，并让 POI 去解析工作簿
        assertUnsupported(DocumentFixtures.docxDeclaringWordButRelatingToWorkbook());
    }

    @Test
    void aSpreadsheetFarBeyondTheExtractionLimitIsStillRejectedAsUnsupported() {
        // 上限只有 10：如果实现先去提取正文，就会得到 EXTRACTED_TEXT_TOO_LARGE 而不是类型错误
        TikaDocumentTextParser tinyLimit = new TikaDocumentTextParser(10);

        assertThatThrownBy(() -> tinyLimit.parse(
                new ByteArrayInputStream(DocumentFixtures.xlsx("X".repeat(5000))), DocumentFormat.DOCX))
                .as("类型验证必须发生在正文提取之前")
                .isInstanceOf(DocumentParsingException.class)
                .extracting(thrown -> ((DocumentParsingException) thrown).failureCode())
                .isEqualTo(KnowledgeParseFailureCode.UNSUPPORTED_DOCUMENT_CONTENT);
    }

    @Test
    void anOversizedContentTypesPartIsRejectedWithoutUnboundedReads() {
        // 元数据部件解压后 1.2 MB（压缩后很小）：验证阶段最多只读 1 MiB，因此直接判为「不是 DOCX」
        assertUnsupported(DocumentFixtures.docxWithOversizedContentTypesPart());
    }

    // ---------- ② 真正的 DOCX 行为不变 ----------

    @Test
    void aValidDocxStillParses() {
        String extracted = parse(DocumentFixtures.docx(), DocumentFormat.DOCX);

        assertThat(extracted).contains(DocumentFixtures.FIRST_PARAGRAPH);
        assertThat(extracted).contains("Second paragraph.");
    }

    @Test
    void aDocxThatDeclaresItsMainPartButDoesNotContainItIsReportedAsCorrupted() {
        assertThatThrownBy(() -> parse(DocumentFixtures.docxDeclaringWordButMissingItsMainPart(),
                DocumentFormat.DOCX))
                .as("包已经声明自己是 DOCX：主文档部件缺失属于「损坏」，而不是「类型不符」")
                .isInstanceOf(DocumentParsingException.class)
                .extracting(thrown -> ((DocumentParsingException) thrown).failureCode())
                .isEqualTo(KnowledgeParseFailureCode.CORRUPTED_DOCUMENT);
    }

    @Test
    void aDocxWithAGarbageBodyNeverLeaksOrSucceedsSilently() {
        // 部分解析器对畸形部件相当宽容：这里只要求「不把它当成别的类型」，也不产生异常类型泄漏
        try {
            String extracted = parse(DocumentFixtures.docxDeclaringWordButWithGarbageBody(),
                    DocumentFormat.DOCX);
            assertThat(extracted).as("宽容解析时只能得到无效文本，不会凭空多出内容").doesNotContain("<w:");
        }
        catch (DocumentParsingException ex) {
            assertThat(ex.failureCode()).isIn(KnowledgeParseFailureCode.CORRUPTED_DOCUMENT,
                    KnowledgeParseFailureCode.PARSER_FAILURE);
        }
    }

    @Test
    void aTruncatedContainerIsReportedAsCorrupted() {
        assertThatThrownBy(() -> parse(DocumentFixtures.truncatedDocx(), DocumentFormat.DOCX))
                .isInstanceOf(DocumentParsingException.class)
                .extracting(thrown -> ((DocumentParsingException) thrown).failureCode())
                .isEqualTo(KnowledgeParseFailureCode.CORRUPTED_DOCUMENT);
    }

    // ---------- ③ 机制证据：这些夹具确实是「别的类型」 ----------

    @Test
    void theSpreadsheetFixtureIsAGenuineSpreadsheetPackageThatTikaWouldOtherwiseParse() throws Exception {
        // 直接调用 Tika 的 OOXMLParser（不经过我们的适配器）：它会按包内容类型分派到 Excel 提取器，
        // 从而提取出单元格文本 —— 这正是「只查 ZIP 头」时会发生的事。
        String extracted = extractWithRawTikaOoxmlParser(DocumentFixtures.xlsx(SPREADSHEET_SECRET));

        assertThat(extracted)
                .as("夹具本身是一份可被解析的真实电子表格包，因此上面的拒绝才是有意义的")
                .contains(SPREADSHEET_SECRET);
    }

    @Test
    void thePresentationFixtureIsAlsoAGenuinePresentationPackage() throws Exception {
        String extracted = extractWithRawTikaOoxmlParser(DocumentFixtures.pptx(SLIDE_SECRET));

        assertThat(extracted).contains(SLIDE_SECRET);
    }

    // ---------- ④ 资源边界 ----------

    @Test
    void spooledTemporaryFilesAreDeletedAfterParsing() throws IOException {
        List<Path> before = spooledTempFiles();

        parse(DocumentFixtures.docx(), DocumentFormat.DOCX);
        assertThatThrownBy(() -> parse(DocumentFixtures.xlsx(SPREADSHEET_SECRET), DocumentFormat.DOCX))
                .isInstanceOf(DocumentParsingException.class);

        // 成功与失败两条路径都不留临时文件
        assertThat(spooledTempFiles()).isEqualTo(before);
    }

    @Test
    void theCallersStreamIsNotClosedByDocxParsing() {
        assertThat(closedAfterParse(DocumentFixtures.docx())).isFalse();
        assertThat(closedAfterParse(DocumentFixtures.xlsx(SPREADSHEET_SECRET))).isFalse();
    }

    // ---------- 辅助 ----------

    private void assertUnsupported(byte[] content) {
        assertThatThrownBy(() -> parse(content, DocumentFormat.DOCX))
                .isInstanceOf(DocumentParsingException.class)
                .extracting(thrown -> ((DocumentParsingException) thrown).failureCode())
                .isEqualTo(KnowledgeParseFailureCode.UNSUPPORTED_DOCUMENT_CONTENT);
    }

    private String parse(byte[] content, DocumentFormat format) {
        return this.parser.parse(new ByteArrayInputStream(content), format);
    }

    private boolean closedAfterParse(byte[] content) {
        boolean[] closed = { false };
        InputStream tracking = new ByteArrayInputStream(content) {

            @Override
            public void close() throws IOException {
                closed[0] = true;
                super.close();
            }
        };
        try {
            this.parser.parse(tracking, DocumentFormat.DOCX);
        }
        catch (DocumentParsingException ex) {
            // 失败路径同样不允许关闭调用方的流
        }
        return closed[0];
    }

    /**
     * 绕过本项目的适配器，直接用 Tika 的 OOXML 解析器提取文本。
     */
    private static String extractWithRawTikaOoxmlParser(byte[] content) throws Exception {
        BodyContentHandler handler = new BodyContentHandler(-1);
        ParseContext context = new ParseContext();
        new OOXMLParser().parse(new ByteArrayInputStream(content), handler, new Metadata(), context);
        return handler.toString();
    }

    /**
     * 快照系统临时目录里由本适配器创建的落盘文件。
     */
    private static List<Path> spooledTempFiles() throws IOException {
        Path tempRoot = Path.of(System.getProperty("java.io.tmpdir"));
        try (Stream<Path> files = Files.list(tempRoot)) {
            return files.filter(path -> path.getFileName().toString().startsWith("flowdesk-knowledge-"))
                    .sorted()
                    .toList();
        }
    }
}
