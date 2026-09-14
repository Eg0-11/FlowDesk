package com.flowdesk.infrastructure.knowledge.parsing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 解析测试用的真二进制夹具（FD-0009）。
 *
 * <p>全部由测试自己生成，<b>不使用任何第三方文档素材</b>：</p>
 * <ul>
 *   <li>DOCX 用 JDK 的 {@link ZipOutputStream} 拼出最小可用的 OOXML 包；</li>
 *   <li>PDF 按 PDF 1.7 语法手写对象、xref 表与 trailer（含正确的字节偏移），
 *       因此 PDFBox 会把它当成真实 PDF 解析。</li>
 * </ul>
 */
final class DocumentFixtures {

    private DocumentFixtures() {
    }

    /** DOCX/Markdown/文本夹具里使用的第一段中文。 */
    static final String FIRST_PARAGRAPH = "第一段内容：季度运维报告。";

    /** DOCX 夹具里使用的第二段英文。 */
    static final String SECOND_PARAGRAPH = "Second paragraph.";

    /** PDF 夹具里使用的文本（PDF 基础字体只覆盖拉丁字符，因此这里只用 ASCII）。 */
    static final String PDF_TEXT = "Hello FlowDesk";

    /**
     * @return 最小可用的 DOCX 包（含两个段落）
     */
    static byte[] docx() {
        return docx(FIRST_PARAGRAPH, SECOND_PARAGRAPH);
    }

    /**
     * @param paragraphs 段落文本
     * @return 最小可用的 DOCX 包
     */
    static byte[] docx(String... paragraphs) {
        StringBuilder body = new StringBuilder();
        for (String paragraph : paragraphs) {
            body.append("<w:p><w:r><w:t xml:space=\"preserve\">").append(escapeXml(paragraph))
                    .append("</w:t></w:r></w:p>");
        }

        String documentXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:body>" + body + "</w:body></w:document>";

        String contentTypes = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-"
                + "officedocument.wordprocessingml.document.main+xml\"/>"
                + "</Types>";

        String relationships = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/"
                + "relationships/officeDocument\" Target=\"word/document.xml\"/>"
                + "</Relationships>";

        return zip(java.util.Map.of(
                "[Content_Types].xml", contentTypes,
                "_rels/.rels", relationships,
                "word/document.xml", documentXml));
    }

    /**
     * 构造一个「正文里引用外部实体」的 DOCX：用于验证解析不会去读本地文件。
     *
     * @param systemId 外部实体的系统标识，例如 {@code file:///C:/secret.txt}
     * @return DOCX 字节
     */
    static byte[] docxWithExternalEntity(String systemId) {
        String documentXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<!DOCTYPE w:document [ <!ENTITY xxe SYSTEM \"" + escapeXml(systemId) + "\"> ]>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:body><w:p><w:r><w:t>&xxe;</w:t></w:r></w:p></w:body></w:document>";

        String contentTypes = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-"
                + "officedocument.wordprocessingml.document.main+xml\"/>"
                + "</Types>";

        String relationships = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/"
                + "relationships/officeDocument\" Target=\"word/document.xml\"/>"
                + "</Relationships>";

        return zip(java.util.Map.of(
                "[Content_Types].xml", contentTypes,
                "_rels/.rels", relationships,
                "word/document.xml", documentXml));
    }

    /**
     * @param text 页面上的文本（只能包含 ASCII：夹具用的是标准 Type1 字体）
     * @return 结构完整、xref 偏移正确的单页 PDF
     */    static byte[] pdf(String text) {
        StringBuilder pdf = new StringBuilder();
        List<Integer> offsets = new ArrayList<>();

        pdf.append("%PDF-1.7\n");
        offsets.add(pdf.length());
        pdf.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");

        offsets.add(pdf.length());
        pdf.append("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");

        offsets.add(pdf.length());
        pdf.append("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
                + "/Resources << /Font << /F1 4 0 R >> >> /Contents 6 0 R >>\nendobj\n");

        offsets.add(pdf.length());
        pdf.append("4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n");

        offsets.add(pdf.length());
        pdf.append("5 0 obj\n<< /Producer (FlowDesk test fixture) >>\nendobj\n");

        String stream = "BT /F1 24 Tf 72 700 Td (" + escapePdfText(text) + ") Tj ET\n";
        offsets.add(pdf.length());
        pdf.append("6 0 obj\n<< /Length ").append(stream.getBytes(StandardCharsets.US_ASCII).length)
                .append(" >>\nstream\n").append(stream).append("endstream\nendobj\n");

        int xrefOffset = pdf.length();
        pdf.append("xref\n0 7\n");
        pdf.append("0000000000 65535 f \n");
        for (int offset : offsets) {
            pdf.append(String.format("%010d 00000 n \n", offset));
        }
        pdf.append("trailer\n<< /Size 7 /Root 1 0 R /Info 5 0 R >>\nstartxref\n")
                .append(xrefOffset).append("\n%%EOF\n");

        return pdf.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * 一个声明了标准安全处理器、但没有正确口令的 PDF：用于验证「加密文档」映射。
     *
     * @return 加密 PDF 字节
     */
    static byte[] encryptedPdf() {
        StringBuilder pdf = new StringBuilder();
        List<Integer> offsets = new ArrayList<>();

        pdf.append("%PDF-1.7\n");
        offsets.add(pdf.length());
        pdf.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");

        offsets.add(pdf.length());
        pdf.append("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");

        offsets.add(pdf.length());
        pdf.append("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>\nendobj\n");

        // /V 1 /R 2：40 位 RC4 标准安全处理器；/O 与 /U 必须各 32 字节
        offsets.add(pdf.length());
        pdf.append("4 0 obj\n<< /Filter /Standard /V 1 /R 2 "
                + "/O (0123456789abcdef0123456789abcdef) "
                + "/U (fedcba9876543210fedcba9876543210) /P -1 >>\nendobj\n");

        int xrefOffset = pdf.length();
        pdf.append("xref\n0 5\n");
        pdf.append("0000000000 65535 f \n");
        for (int offset : offsets) {
            pdf.append(String.format("%010d 00000 n \n", offset));
        }
        pdf.append("trailer\n<< /Size 5 /Root 1 0 R /Encrypt 4 0 R /ID [<0102030405060708> "
                + "<0102030405060708>] >>\nstartxref\n").append(xrefOffset).append("\n%%EOF\n");

        return pdf.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * @return 文件头合法、其余内容全是垃圾的 PDF
     */
    static byte[] truncatedPdf() {
        return "%PDF-1.7\nthis is not a pdf body at all\n".getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * @return ZIP 文件头合法、但不是合法 OOXML 包的字节
     */
    static byte[] truncatedDocx() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] { 0x50, 0x4B, 0x03, 0x04 });
        out.writeBytes("not a real zip central directory".getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    /**
     * @return 带 UTF-8 BOM 的文本
     */
    static byte[] textWithByteOrderMark(String text) {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[body.length + 3];
        result[0] = (byte) 0xEF;
        result[1] = (byte) 0xBB;
        result[2] = (byte) 0xBF;
        System.arraycopy(body, 0, result, 3, body.length);
        return result;
    }

    // ---------- 类型混淆夹具（FD-0009-R1） ----------

    /**
     * 一个真实的 XLSX（SpreadsheetML）OOXML 包：内容类型、关系、工作簿、工作表齐备，
     * 单元格里放一个哨兵文本。
     *
     * <p>它不是「用 DOCX 的壳装 Excel 数据」，而是一份<b>货真价实的电子表格包</b> ——
     * 因此可以被 Tika 的 {@code OOXMLParser} 按自身类型正常解析（见类型混淆测试）。</p>
     *
     * @param cellText 单元格文本
     * @return XLSX 字节
     */
    static byte[] xlsx(String cellText) {
        return zip(java.util.Map.of(
                "[Content_Types].xml", contentTypes(
                        override("/xl/workbook.xml",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"),
                        override("/xl/worksheets/sheet1.xml",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml")),
                "_rels/.rels", relationships(OFFICE_DOCUMENT_RELATIONSHIP, "xl/workbook.xml"),
                "xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                        + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                        + "<sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
                "xl/_rels/workbook.xml.rels", relationships(
                        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet",
                        "worksheets/sheet1.xml"),
                "xl/worksheets/sheet1.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                        + "<sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>"
                        + escapeXml(cellText) + "</t></is></c></row></sheetData></worksheet>"));
    }

    /**
     * 一个真实的 PPTX（PresentationML）OOXML 包。
     *
     * @param slideText 幻灯片文本
     * @return PPTX 字节
     */
    static byte[] pptx(String slideText) {
        return zip(java.util.Map.of(
                "[Content_Types].xml", contentTypes(
                        override("/ppt/presentation.xml",
                                "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"),
                        override("/ppt/slides/slide1.xml",
                                "application/vnd.openxmlformats-officedocument.presentationml.slide+xml")),
                "_rels/.rels", relationships(OFFICE_DOCUMENT_RELATIONSHIP, "ppt/presentation.xml"),
                "ppt/presentation.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<p:presentation xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" "
                        + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                        + "<p:sldIdLst><p:sldId id=\"256\" r:id=\"rId1\"/></p:sldIdLst></p:presentation>",
                "ppt/_rels/presentation.xml.rels", relationships(
                        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide",
                        "slides/slide1.xml"),
                "ppt/slides/slide1.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<p:sld xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
                        + "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">"
                        + "<p:cSld><p:spTree><p:sp><p:txBody><a:p><a:r><a:t>"
                        + escapeXml(slideText) + "</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>"));
    }

    /**
     * @return 一个结构合法、但与 OOXML 无关的普通 ZIP（只有几个文本文件）
     */
    static byte[] plainZip() {
        return zip(java.util.Map.of(
                "readme.txt", "这不是一个 Office 文档",
                "notes/inner.txt", "PLAIN_ZIP_CONTENT"));
    }

    /**
     * 「声明是 DOCX、内部却坏了」的包：内容类型与关系都指向 WordprocessingML，
     * 但被声明的主文档部件在包里根本不存在。
     *
     * @return DOCX 字节
     */
    static byte[] docxDeclaringWordButMissingItsMainPart() {
        return zip(java.util.Map.of(
                "[Content_Types].xml", contentTypes(
                        override("/word/document.xml", DOCX_MAIN_CONTENT_TYPE)),
                "_rels/.rels", relationships(OFFICE_DOCUMENT_RELATIONSHIP, "word/document.xml"),
                "docProps/app.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/"
                        + "extended-properties\"><Application>FlowDesk</Application></Properties>"));
    }

    /**
     * 「声明是 DOCX、主文档部件却是垃圾 XML」的包。
     *
     * @return DOCX 字节
     */
    static byte[] docxDeclaringWordButWithGarbageBody() {
        return zip(java.util.Map.of(
                "[Content_Types].xml", contentTypes(
                        override("/word/document.xml", DOCX_MAIN_CONTENT_TYPE)),
                "_rels/.rels", relationships(OFFICE_DOCUMENT_RELATIONSHIP, "word/document.xml"),
                "word/document.xml", "<w:document><w:body><w:p>没有闭合的标签"));
    }

    /**
     * 「内容类型说是 DOCX、关系却指向 Excel 工作簿」的双面包：
     * 用于验证包类型验证不只看内容类型，还要求 {@code officeDocument} 关系指向 {@code word/document.xml}。
     *
     * @return DOCX 字节（内容类型声明为 DOCX）
     */
    static byte[] docxDeclaringWordButRelatingToWorkbook() {
        return zip(java.util.Map.of(
                "[Content_Types].xml", contentTypes(
                        override("/word/document.xml", DOCX_MAIN_CONTENT_TYPE),
                        override("/xl/workbook.xml",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml")),
                "_rels/.rels", relationships(OFFICE_DOCUMENT_RELATIONSHIP, "xl/workbook.xml"),
                "word/document.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                        + "<w:body><w:p><w:r><w:t>看起来像 Word</w:t></w:r></w:p></w:body></w:document>"));
    }

    /**
     * 一个 {@code [Content_Types].xml} 解压后远超元数据读取上限的包
     * （压缩后很小，用于验证「验证阶段不会无界读取」）。
     *
     * @return DOCX 形状的字节
     */
    static byte[] docxWithOversizedContentTypesPart() {
        // 1 MiB 上限 + 余量：用可压缩的注释填充，避免测试本身占用大量磁盘
        String padding = "-".repeat(1_200_000);
        String contentTypes = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<!-- "
                + padding + " -->\n" + typesElement(
                        override("/word/document.xml", DOCX_MAIN_CONTENT_TYPE));
        return zip(java.util.Map.of(
                "[Content_Types].xml", contentTypes,
                "_rels/.rels", relationships(OFFICE_DOCUMENT_RELATIONSHIP, "word/document.xml"),
                "word/document.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                        + "<w:body><w:p><w:r><w:t>内容</w:t></w:r></w:p></w:body></w:document>"));
    }

    private static final String OFFICE_DOCUMENT_RELATIONSHIP =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument";

    private static final String DOCX_MAIN_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml";

    /** OPC 内容类型部件的正确命名空间。 */
    static final String OPC_CONTENT_TYPES_NAMESPACE =
            "http://schemas.openxmlformats.org/package/2006/content-types";

    /** OPC 关系部件的正确命名空间。 */
    static final String OPC_RELATIONSHIPS_NAMESPACE =
            "http://schemas.openxmlformats.org/package/2006/relationships";

    // ---------- OPC 关系/内容类型反例夹具（FD-0009-R2） ----------

    /**
     * 用给定的内容类型部件、关系部件与主文档正文拼一个 OOXML 包。
     *
     * @param contentTypesXml {@code [Content_Types].xml} 的完整内容
     * @param relationshipsXml {@code _rels/.rels} 的完整内容
     * @param documentXml     {@code word/document.xml} 的完整内容
     * @return DOCX 形状的字节
     */
    static byte[] docxPackage(String contentTypesXml, String relationshipsXml, String documentXml) {
        return zip(java.util.Map.of(
                "[Content_Types].xml", contentTypesXml,
                "_rels/.rels", relationshipsXml,
                "word/document.xml", documentXml));
    }

    /**
     * @return 正确命名空间、正确类型的 {@code [Content_Types].xml}
     */
    static String correctContentTypes() {
        return contentTypes(override("/word/document.xml", DOCX_MAIN_CONTENT_TYPE));
    }

    /**
     * @param innerXml 关系元素（可多个）
     * @return 正确命名空间的 {@code Relationships} 根元素
     */
    static String correctRelationships(String innerXml) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"" + OPC_RELATIONSHIPS_NAMESPACE + "\">" + innerXml
                + "</Relationships>";
    }

    /**
     * @param extraAttributes 追加在 Relationship 上的属性（例如 {@code TargetMode="External"}）
     * @param target          关系目标
     * @return officeDocument 关系元素
     */
    static String officeDocumentRelationship(String extraAttributes, String target) {
        return "<Relationship Id=\"rId1\" Type=\"" + OFFICE_DOCUMENT_RELATIONSHIP + "\""
                + (extraAttributes.isEmpty() ? "" : " " + extraAttributes)
                + " Target=\"" + escapeXml(target) + "\"/>";
    }

    /**
     * @param text 段落文本
     * @return 单段落的 WordprocessingML 正文
     */
    static String documentBody(String text) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:body><w:p><w:r><w:t>" + escapeXml(text) + "</w:t></w:r></w:p></w:body></w:document>";
    }

    /**
     * 一个「officeDocument 关系带指定 TargetMode」的包。
     *
     * @param targetModeAttribute {@code TargetMode} 属性原文，例如 {@code TargetMode="External"}
     * @return DOCX 形状的字节
     */
    static byte[] docxWithTargetMode(String targetModeAttribute) {
        return docxPackage(correctContentTypes(),
                correctRelationships(officeDocumentRelationship(targetModeAttribute, "word/document.xml")),
                documentBody("第一段内容"));
    }

    /**
     * 一个「officeDocument 关系指向指定 Target」的包（本地仍有小写 {@code word/document.xml}）。
     *
     * @param target 关系目标原文
     * @return DOCX 形状的字节
     */
    static byte[] docxWithTarget(String target) {
        return docxPackage(correctContentTypes(),
                correctRelationships(officeDocumentRelationship("", target)),
                documentBody("第一段内容"));
    }

    /**
     * 一个「关系根元素位于错误命名空间」的包。
     *
     * @return DOCX 形状的字节
     */
    static byte[] docxWithWrongRelationshipsNamespace() {
        return docxPackage(correctContentTypes(),
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<Relationships xmlns=\"urn:flowdesk:not-opc\">"
                        + officeDocumentRelationship("", "word/document.xml") + "</Relationships>",
                documentBody("第一段内容"));
    }

    /**
     * 一个「Relationship 元素位于错误命名空间」的包（根元素正确）。
     *
     * @return DOCX 形状的字节
     */
    static byte[] docxWithRelationshipInWrongNamespace() {
        return docxPackage(correctContentTypes(),
                correctRelationships("<Relationship xmlns=\"urn:flowdesk:not-opc\" Id=\"rId1\" Type=\""
                        + OFFICE_DOCUMENT_RELATIONSHIP + "\" Target=\"word/document.xml\"/>"),
                documentBody("第一段内容"));
    }

    /**
     * 一个「伪 Relationship 被嵌套在包装元素里」的包（根元素与包装元素都正确）。
     *
     * @return DOCX 形状的字节
     */
    static byte[] docxWithNestedFakeRelationship() {
        return docxPackage(correctContentTypes(),
                correctRelationships("<Wrapper>" + officeDocumentRelationship("", "word/document.xml")
                        + "</Wrapper>"),
                documentBody("第一段内容"));
    }

    /**
     * 一个「没有任何 officeDocument 关系」的包。
     *
     * @return DOCX 形状的字节
     */
    static byte[] docxWithoutOfficeDocumentRelationship() {
        return docxPackage(correctContentTypes(),
                correctRelationships("<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/"
                        + "officeDocument/2006/relationships/extended-properties\" Target=\"docProps/app.xml\"/>"),
                documentBody("第一段内容"));
    }

    /**
     * 一个「有两个 officeDocument 关系」的包（自相矛盾，无法唯一证明）。
     *
     * @return DOCX 形状的字节
     */
    static byte[] docxWithTwoOfficeDocumentRelationships() {
        return docxPackage(correctContentTypes(),
                correctRelationships(officeDocumentRelationship("", "word/document.xml")
                        + officeDocumentRelationship("", "word/document.xml").replace("rId1", "rId2")),
                documentBody("第一段内容"));
    }

    /**
     * 一个「除 officeDocument 之外还有其它关系类型」的包：真实 DOCX 就是这样，必须照常解析。
     *
     * @return DOCX 形状的字节
     */
    static byte[] docxWithAdditionalRelationshipTypes() {
        return docxPackage(correctContentTypes(),
                correctRelationships(officeDocumentRelationship("", "word/document.xml")
                        + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/"
                        + "relationships/extended-properties\" Target=\"docProps/app.xml\"/>"
                        + "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/package/2006/"
                        + "relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>"),
                documentBody("第一段内容"));
    }

    /**
     * 一个「内容类型根元素位于错误命名空间」的包。
     *
     * @return DOCX 形状的字节
     */
    static byte[] docxWithWrongContentTypesNamespace() {
        return docxPackage("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<Types xmlns=\"urn:flowdesk:not-opc\">"
                        + override("/word/document.xml", DOCX_MAIN_CONTENT_TYPE) + "</Types>",
                correctRelationships(officeDocumentRelationship("", "word/document.xml")),
                documentBody("第一段内容"));
    }

    /**
     * 一个「Override 元素位于错误命名空间」的包（根元素正确）。
     *
     * @return DOCX 形状的字节
     */
    static byte[] docxWithContentTypeOverrideInWrongNamespace() {
        return docxPackage(typesElement("<Override xmlns=\"urn:flowdesk:not-opc\" PartName=\"/word/document.xml\""
                        + " ContentType=\"" + DOCX_MAIN_CONTENT_TYPE + "\"/>"),
                correctRelationships(officeDocumentRelationship("", "word/document.xml")),
                documentBody("第一段内容"));
    }

    /**
     * 一个「伪 Override 被嵌套在包装元素里」的包（根元素与包装元素都正确）。
     *
     * @return DOCX 形状的字节
     */
    static byte[] docxWithNestedFakeContentTypeOverride() {
        return docxPackage(typesElement("<Wrapper>"
                        + override("/word/document.xml", DOCX_MAIN_CONTENT_TYPE) + "</Wrapper>"),
                correctRelationships(officeDocumentRelationship("", "word/document.xml")),
                documentBody("第一段内容"));
    }

    /**
     * 一个「正文非常长」的包，用于验证类型错误优先于提取上限。
     *
     * @param contentTypesXml  {@code [Content_Types].xml} 的内容
     * @param relationshipsXml {@code _rels/.rels} 的内容
     * @param bodyCharacters   正文字符数
     * @return DOCX 形状的字节
     */
    static byte[] docxPackageWithLongBody(String contentTypesXml, String relationshipsXml, int bodyCharacters) {
        return docxPackage(contentTypesXml, relationshipsXml, documentBody("长".repeat(bodyCharacters)));
    }

    private static String override(String partName, String contentType) {
        return "<Override PartName=\"" + partName + "\" ContentType=\"" + contentType + "\"/>";
    }

    private static String contentTypes(String... overrides) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" + typesElement(overrides);
    }

    private static String typesElement(String... overrides) {
        return "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package."
                + "relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + String.join("", overrides) + "</Types>";
    }

    private static String relationships(String relationshipType, String target) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"" + relationshipType + "\" Target=\"" + target + "\"/>"
                + "</Relationships>";
    }

    /**
     * @param entries 文件名 → 内容
     * @return ZIP 字节
     */
    private static byte[] zip(java.util.Map<String, String> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (java.util.Map.Entry<String, String> entry : new java.util.TreeMap<>(entries).entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        catch (IOException ex) {
            throw new IllegalStateException("夹具构造失败", ex);
        }
        return out.toByteArray();
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String escapePdfText(String value) {
        return value.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
    }
}
