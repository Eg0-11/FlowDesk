package com.flowdesk.bootstrap.knowledge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 解析 HTTP 测试用的真二进制夹具。
 *
 * <p>与基础设施层测试里的同名夹具是<b>刻意重复</b>的一小段构造代码：把它做成跨模块共享的
 * test-jar 需要为了几行测试代码新增模块间依赖与打包配置，代价大于收益。
 * 两份夹具都不使用任何第三方文档素材，全部按规范现场拼装。</p>
 */
final class KnowledgeParseFixtures {

    /** DOCX 第一段。 */
    static final String DOCX_FIRST_PARAGRAPH = "第一段内容：季度运维报告。";

    /** DOCX 第二段。 */
    static final String DOCX_SECOND_PARAGRAPH = "Second paragraph.";

    /** PDF 正文（PDF 基础字体只覆盖拉丁字符，因此只用 ASCII）。 */
    static final String PDF_TEXT = "Hello FlowDesk";

    private KnowledgeParseFixtures() {
    }

    /**
     * @return 最小可用的 DOCX 包
     */
    static byte[] docx() {
        String body = "<w:p><w:r><w:t xml:space=\"preserve\">" + DOCX_FIRST_PARAGRAPH + "</w:t></w:r></w:p>"
                + "<w:p><w:r><w:t xml:space=\"preserve\">" + DOCX_SECOND_PARAGRAPH + "</w:t></w:r></w:p>";
        String documentXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:body>" + body + "</w:body></w:document>";
        String contentTypes = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package."
                + "relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-"
                + "officedocument.wordprocessingml.document.main+xml\"/></Types>";
        String relationships = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/"
                + "relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>";

        return zip(Map.of(
                "[Content_Types].xml", contentTypes,
                "_rels/.rels", relationships,
                "word/document.xml", documentXml));
    }

    /**
     * @param text 页面文本（仅 ASCII）
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
                + "/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>\nendobj\n");
        offsets.add(pdf.length());
        pdf.append("4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n");

        String stream = "BT /F1 24 Tf 72 700 Td (" + text + ") Tj ET\n";
        offsets.add(pdf.length());
        pdf.append("5 0 obj\n<< /Length ").append(stream.getBytes(StandardCharsets.US_ASCII).length)
                .append(" >>\nstream\n").append(stream).append("endstream\nendobj\n");

        int xrefOffset = pdf.length();
        pdf.append("xref\n0 6\n0000000000 65535 f \n");
        for (int offset : offsets) {
            pdf.append(String.format("%010d 00000 n \n", offset));
        }
        pdf.append("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n").append(xrefOffset).append("\n%%EOF\n");

        return pdf.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * @return 文件头合法、其余全是垃圾的 PDF
     */
    static byte[] corruptedPdf() {
        return "%PDF-1.7\nthis is not a pdf body at all\n".getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * 一份真实的 XLSX（SpreadsheetML）OOXML 包：内容类型、关系、工作簿与工作表齐备。
     *
     * <p>它的 ZIP 文件头是 {@code PK\x03\x04}，因此能通过上传阶段「DOCX 必须是 ZIP 容器」
     * 的校验；但包本身是电子表格，必须在<b>解析阶段</b>被类型验证拒绝（FD-0009-R1）。</p>
     *
     * @param cellText 单元格文本
     * @return XLSX 字节
     */
    static byte[] xlsx(String cellText) {
        String contentTypes = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package."
                + "relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-"
                + "officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd."
                + "openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>";
        String relationships = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/"
                + "relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>";
        String workbook = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>";
        String sheet = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>" + cellText
                + "</t></is></c></row></sheetData></worksheet>";

        return zip(Map.of(
                "[Content_Types].xml", contentTypes,
                "_rels/.rels", relationships,
                "xl/workbook.xml", workbook,
                "xl/worksheets/sheet1.xml", sheet));
    }

    private static byte[] zip(Map<String, String> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> entry : new TreeMap<>(entries).entrySet()) {
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
}
