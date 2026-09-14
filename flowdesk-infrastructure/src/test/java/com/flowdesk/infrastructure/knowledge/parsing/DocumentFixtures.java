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
     */
    static byte[] pdf(String text) {
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
