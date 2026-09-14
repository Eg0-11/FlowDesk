package com.flowdesk.infrastructure.knowledge.parsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowdesk.application.knowledge.parse.DocumentParsingException;
import com.flowdesk.domain.knowledge.DocumentFormat;
import com.flowdesk.domain.knowledge.KnowledgeParseFailureCode;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;

/**
 * OPC 关系与内容类型的<b>证明强度</b>测试（FD-0009-R2）。
 *
 * <p>FD-0009-R1 的验证只做了「找名字叫 Relationship 的元素」这一级检查，
 * 因此下面这些包都能蒙混过关：关系被标成包外的 {@code TargetMode="External"}、
 * 关系部件位于别的命名空间、伪关系被嵌在包装元素里、以及用大小写不同的
 * {@code Target} 指向别的部件。</p>
 *
 * <p>本类逐条锁定修复后的判定：<b>根元素命名空间 + 直属子元素 + 关系模式 + 精确目标</b>，
 * 任何「无法唯一、无歧义证明内部主文档关系」的情况都返回
 * {@code UNSUPPORTED_DOCUMENT_CONTENT}，且必须发生在正文提取之前。</p>
 */
class TikaDocumentTextParserOpcValidationTest {

    private static final int GENEROUS_LIMIT = 100_000;

    private final TikaDocumentTextParser parser = new TikaDocumentTextParser(GENEROUS_LIMIT);

    // ---------- 一、TargetMode ----------

    @Test
    void anExternalOfficeDocumentRelationshipIsRejected() {
        // 目标部件确实存在，但关系声明它是「包外资源」：这不是包内的主文档部件
        assertUnsupported(DocumentFixtures.docxWithTargetMode("TargetMode=\"External\""));
    }

    @Test
    void anExplicitInternalTargetModeIsAccepted() {
        assertParses(DocumentFixtures.docxWithTargetMode("TargetMode=\"Internal\""));
    }

    @Test
    void aMissingTargetModeIsAccepted() {
        assertParses(DocumentFixtures.docxWithTargetMode(""));
    }

    @Test
    void blankTargetModeVariantsAreRejected() {
        assertUnsupported(DocumentFixtures.docxWithTargetMode("TargetMode=\"\""));
        assertUnsupported(DocumentFixtures.docxWithTargetMode("TargetMode=\" \""));
        assertUnsupported(DocumentFixtures.docxWithTargetMode("TargetMode=\"  Internal  \""));
    }

    @Test
    void caseVariantsOfInternalAreRejected() {
        assertUnsupported(DocumentFixtures.docxWithTargetMode("TargetMode=\"internal\""));
        assertUnsupported(DocumentFixtures.docxWithTargetMode("TargetMode=\"INTERNAL\""));
        assertUnsupported(DocumentFixtures.docxWithTargetMode("TargetMode=\"Internal \""));
    }

    @Test
    void otherTargetModeValuesAreRejected() {
        assertUnsupported(DocumentFixtures.docxWithTargetMode("TargetMode=\"ExternalOnly\""));
        assertUnsupported(DocumentFixtures.docxWithTargetMode("TargetMode=\"1\""));
    }

    // ---------- 二、命名空间与层级 ----------

    @Test
    void aRelationshipsRootInTheWrongNamespaceIsRejected() {
        assertUnsupported(DocumentFixtures.docxWithWrongRelationshipsNamespace());
    }

    @Test
    void aRelationshipElementInTheWrongNamespaceIsRejected() {
        assertUnsupported(DocumentFixtures.docxWithRelationshipInWrongNamespace());
    }

    @Test
    void aFakeRelationshipNestedUnderTheCorrectRootIsRejected() {
        assertUnsupported(DocumentFixtures.docxWithNestedFakeRelationship());
    }

    @Test
    void aContentTypesRootInTheWrongNamespaceIsRejected() {
        assertUnsupported(DocumentFixtures.docxWithWrongContentTypesNamespace());
    }

    @Test
    void aContentTypeOverrideInTheWrongNamespaceIsRejected() {
        assertUnsupported(DocumentFixtures.docxWithContentTypeOverrideInWrongNamespace());
    }

    @Test
    void aFakeContentTypeOverrideNestedUnderTheCorrectRootIsRejected() {
        assertUnsupported(DocumentFixtures.docxWithNestedFakeContentTypeOverride());
    }

    // ---------- 三、Target ----------

    @Test
    void anUppercaseTargetIsRejectedEvenThoughThePartNameDiffersOnlyInCase() {
        // 包里只有小写的 word/document.xml：区分大小写的判定必须拒绝这个目标
        assertUnsupported(DocumentFixtures.docxWithTarget("WORD/DOCUMENT.XML"));
        assertUnsupported(DocumentFixtures.docxWithTarget("Word/Document.xml"));
    }

    @Test
    void aSingleLeadingSlashIsStillAccepted() {
        assertParses(DocumentFixtures.docxWithTarget("/word/document.xml"));
    }

    @Test
    void targetVariantsThatRelyOnNormalisationAreRejected() {
        assertUnsupported(DocumentFixtures.docxWithTarget("./word/document.xml"));
        assertUnsupported(DocumentFixtures.docxWithTarget("word/../word/document.xml"));
        assertUnsupported(DocumentFixtures.docxWithTarget("word\\.\\document.xml"));
        assertUnsupported(DocumentFixtures.docxWithTarget("word\\document.xml"));
        assertUnsupported(DocumentFixtures.docxWithTarget("word/document.xml?x=1"));
        assertUnsupported(DocumentFixtures.docxWithTarget("word/document.xml#fragment"));
        assertUnsupported(DocumentFixtures.docxWithTarget(" word/document.xml"));
        assertUnsupported(DocumentFixtures.docxWithTarget("word//document.xml"));
        assertUnsupported(DocumentFixtures.docxWithTarget("/word/document.xml/"));
        assertUnsupported(DocumentFixtures.docxWithTarget("//word/document.xml"));
        assertUnsupported(DocumentFixtures.docxWithTarget(""));
        assertUnsupported(DocumentFixtures.docxWithTarget("document.xml"));
    }

    // ---------- 四、唯一性 ----------

    @Test
    void aPackageWithoutAnyOfficeDocumentRelationshipIsRejected() {
        assertUnsupported(DocumentFixtures.docxWithoutOfficeDocumentRelationship());
    }

    @Test
    void aPackageWithTwoOfficeDocumentRelationshipsIsRejected() {
        assertUnsupported(DocumentFixtures.docxWithTwoOfficeDocumentRelationships());
    }

    @Test
    void additionalRelationshipTypesDoNotAffectTheVerdict() {
        // 真实 DOCX 的 _rels/.rels 里除了 officeDocument 还有 core-properties 等关系：
        // 只有 officeDocument 关系参与判定，其它类型不得导致误拒
        assertParses(DocumentFixtures.docxWithAdditionalRelationshipTypes());
    }

    // ---------- 五、类型错误优先于提取 ----------

    @Test
    void typeErrorsWinOverTheExtractionLimitForLongBodies() {
        // 正文 5 万个字符、上限只有 10：如果实现先去提取正文，就会得到 EXTRACTED_TEXT_TOO_LARGE
        TikaDocumentTextParser tinyLimit = new TikaDocumentTextParser(10);
        int bodyCharacters = 50_000;

        assertUnsupported(tinyLimit, DocumentFixtures.docxPackageWithLongBody(
                DocumentFixtures.correctContentTypes(),
                DocumentFixtures.correctRelationships(
                        DocumentFixtures.officeDocumentRelationship("TargetMode=\"External\"",
                                "word/document.xml")),
                bodyCharacters));

        assertUnsupported(tinyLimit, DocumentFixtures.docxPackageWithLongBody(
                DocumentFixtures.correctContentTypes(),
                DocumentFixtures.correctRelationships(
                        DocumentFixtures.officeDocumentRelationship("", "WORD/DOCUMENT.XML")),
                bodyCharacters));

        assertUnsupported(tinyLimit, DocumentFixtures.docxPackageWithLongBody(
                DocumentFixtures.correctContentTypes(),
                DocumentFixtures.correctRelationships("<Wrapper>"
                        + DocumentFixtures.officeDocumentRelationship("", "word/document.xml")
                        + "</Wrapper>"),
                bodyCharacters));
    }

    @Test
    void rejectedPackagesNeverProduceSilentEmptyText() {
        // 反例必须显式失败，而不是「成功但提取到空文本」
        for (byte[] content : new byte[][] {
                DocumentFixtures.docxWithTargetMode("TargetMode=\"External\""),
                DocumentFixtures.docxWithWrongRelationshipsNamespace(),
                DocumentFixtures.docxWithNestedFakeRelationship(),
                DocumentFixtures.docxWithWrongContentTypesNamespace(),
                DocumentFixtures.docxWithTarget("WORD/DOCUMENT.XML") }) {

            assertThatThrownBy(() -> parse(content))
                    .isInstanceOf(DocumentParsingException.class)
                    .extracting(thrown -> ((DocumentParsingException) thrown).failureCode())
                    .isEqualTo(KnowledgeParseFailureCode.UNSUPPORTED_DOCUMENT_CONTENT);
        }
    }

    // ---------- 辅助 ----------

    private void assertUnsupported(byte[] content) {
        assertUnsupported(this.parser, content);
    }

    private static void assertUnsupported(TikaDocumentTextParser parser, byte[] content) {
        assertThatThrownBy(() -> parser.parse(new ByteArrayInputStream(content), DocumentFormat.DOCX))
                .isInstanceOf(DocumentParsingException.class)
                .extracting(thrown -> ((DocumentParsingException) thrown).failureCode())
                .isEqualTo(KnowledgeParseFailureCode.UNSUPPORTED_DOCUMENT_CONTENT);
    }

    private void assertParses(byte[] content) {
        String extracted = parse(content);
        assertThat(extracted).as("合法包应当照常解析").contains("第一段内容");
    }

    private String parse(byte[] content) {
        return this.parser.parse(new ByteArrayInputStream(content), DocumentFormat.DOCX);
    }
}
