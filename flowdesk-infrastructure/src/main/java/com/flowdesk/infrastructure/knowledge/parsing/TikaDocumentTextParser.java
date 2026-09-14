package com.flowdesk.infrastructure.knowledge.parsing;

import com.flowdesk.application.knowledge.parse.DocumentParsingException;
import com.flowdesk.application.knowledge.port.out.DocumentTextParser;
import com.flowdesk.domain.knowledge.DocumentFormat;
import com.flowdesk.domain.knowledge.KnowledgeParseFailureCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.microsoft.ooxml.OOXMLParser;
import org.apache.tika.parser.pdf.PDFParser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * 基于 Apache Tika 3.x 的文本提取适配器（FD-0009 / FD-0009-R1）。
 *
 * <h2>格式以服务端记录为准</h2>
 * <p>解析器由<b>数据库里保存的 {@link DocumentFormat}</b> 直接决定（PDF → PDFBox、DOCX → POI），
 * 而不是让 Tika 按文件名或内容自动探测：上传阶段的格式识别才是权威，
 * 解析阶段再探测一次只会得到「同一份文件两条结论」。</p>
 *
 * <p>Markdown 与纯文本<b>不</b>走解析框架：它们在 FD-0008 已经通过 UTF-8 与 NUL 校验，
 * 直接按 UTF-8 解码更简单、更确定，也少一层可能出错的转换。</p>
 *
 * <h2>DOCX 必须验证到「OOXML 包的真实类型」（FD-0009-R1 修复）</h2>
 * <p>只检查 ZIP 文件头是不够的：{@code OOXMLParser} 同时支持 DOCX / XLSX / PPTX / XPS 等包，
 * 它会<b>按包自身的内容类型</b>选择子解析器 —— 一份真实的 XLSX 只要文件头是 {@code PK\x03\x04}
 * 就会被当成 DOCX 收下并提取出单元格文本（类型混淆）。因此 DOCX 路径现在是三步：</p>
 * <ol>
 *   <li><b>ZIP 初筛</b>：文件头不是 {@code PK\x03\x04} → 直接 {@code UNSUPPORTED_DOCUMENT_CONTENT}；</li>
 *   <li><b>有界落盘</b>：把内容以固定缓冲区流式写入受控临时文件（<b>不</b>把输入整体读进堆）；
 *       临时文件在 {@code finally} 中删除，调用方的流始终不被关闭；</li>
 *   <li><b>包类型验证</b>：在<b>正文提取之前</b>检查 OOXML 包声明的主文档部件类型必须是
 *       WordprocessingML、{@code officeDocument} 关系必须指向 {@code word/document.xml}、
 *       且该部件必须存在。XLSX / PPTX / DOCM / XPS / 普通 ZIP 都在这里被拒。</li>
 * </ol>
 * <p>判定依据<b>只有包内容</b>：不使用原始文件名、扩展名或客户端声明的 Content-Type，
 * 也<b>不</b>往 {@link Metadata#CONTENT_TYPE} 里写一个「它是 DOCX」的声明来充当验证。</p>
 *
 * <h2>PDF 不进入 OCR 路径（FD-0009-R1 修复）</h2>
 * <p>{@link PDFParser} 的默认 OCR 策略是 {@code AUTO}：机器上装了 Tesseract 时会自动走 OCR，
 * 导致解析结果、耗时与资源消耗随部署环境变化。这里在构造时显式固定为 {@code NO_OCR}，
 * 构造之后<b>不再修改</b>这份共享配置（解析过程中只读）。</p>
 *
 * <h2>其它安全边界</h2>
 * <ul>
 *   <li>不访问任何网络资源：解析只用本地解析器，Tika/POI 的 XML 读取默认忽略外部实体；</li>
 *   <li>加密文档 → {@code ENCRYPTED_DOCUMENT}，损坏文档 → {@code CORRUPTED_DOCUMENT}，
 *       其它 → {@code PARSER_FAILURE}；映射基于<b>异常类型</b>而不是消息文本；</li>
 *   <li>第三方解析器的异常消息只作为 cause 保留在服务端，绝不进入响应；</li>
 *   <li>提取文本按 code point 限量，<b>所有</b>写入路径（字符、结构换行、跨回调代理对）都经过
 *       同一个计数器，超限立即中断（见 {@link CodePointLimitedTextBuilder}）。</li>
 * </ul>
 *
 * <p>本类<b>不关闭</b>传入的流：生命周期由调用方负责。</p>
 */
public final class TikaDocumentTextParser implements DocumentTextParser {

    private static final byte[] PDF_HEADER = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    private static final byte[] ZIP_HEADER = { 0x50, 0x4B, 0x03, 0x04 };

    private static final int HEADER_LENGTH = 5;

    /** OOXML 包的内容类型部件。 */
    private static final String CONTENT_TYPES_PART = "[Content_Types].xml";

    /** OOXML 包的顶层关系部件。 */
    private static final String PACKAGE_RELATIONSHIPS_PART = "_rels/.rels";

    /** WordprocessingML 主文档部件名（不含前导斜杠）。 */
    private static final String WORD_DOCUMENT_PART = "word/document.xml";

    /** WordprocessingML 主文档的内容类型：只有它才代表一个真正的 DOCX。 */
    private static final String DOCX_MAIN_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml";

    /** {@code officeDocument} 关系的类型 URI。 */
    private static final String OFFICE_DOCUMENT_RELATIONSHIP =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument";

    /**
     * 读取包元数据（内容类型、关系）时的字节上限。
     *
     * <p>这两个部件正常只有几 KB；上限存在的意义是：即使面对「压缩后极小、解压后极大」的
     * 恶意包，验证本身也不会吃掉内存。</p>
     */
    private static final int MAX_PACKAGE_METADATA_BYTES = 1 << 20;

    /** 落盘时的固定缓冲区（8 KiB），因此内存占用与输入大小无关。 */
    private static final int COPY_BUFFER_SIZE = 8192;

    private final int maxExtractedCodePoints;

    private final PDFParser pdfParser;

    private final Parser ooxmlParser = new OOXMLParser();

    /**
     * @param maxExtractedCodePoints 单次解析允许提取的最大 code point 数
     */
    public TikaDocumentTextParser(int maxExtractedCodePoints) {
        if (maxExtractedCodePoints <= 0) {
            throw new IllegalArgumentException("maxExtractedCodePoints 必须大于 0");
        }
        this.maxExtractedCodePoints = maxExtractedCodePoints;

        // 显式关闭 OCR：不依赖机器上是否安装 Tesseract，也不允许环境改变解析结果
        PDFParserConfig pdfConfig = new PDFParserConfig();
        pdfConfig.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
        this.pdfParser = new PDFParser();
        this.pdfParser.setPDFParserConfig(pdfConfig);
    }

    @Override
    public String parse(InputStream content, DocumentFormat format) {
        Objects.requireNonNull(content, "content 不能为 null");
        Objects.requireNonNull(format, "format 不能为 null");

        return switch (format) {
            case PDF -> parsePdf(content);
            case DOCX -> parseDocx(content);
            case MARKDOWN, TEXT -> decodeUtf8(content);
        };
    }

    /**
     * 供测试直接断言最终生效的 PDF 配置（生产 Bean 与单测走同一个构造路径）。
     *
     * @return 当前生效的 PDF 解析配置
     */
    PDFParserConfig pdfParserConfig() {
        return this.pdfParser.getPDFParserConfig();
    }

    /**
     * PDF：文件头初筛后直接交给 PDFBox（PDFParser 自己需要随机访问，会自行缓冲）。
     */
    private String parsePdf(InputStream content) {
        try {
            byte[] header = content.readNBytes(HEADER_LENGTH);
            if (!startsWith(header, PDF_HEADER)) {
                throw unsupportedContent("内容与声明的文档格式不一致");
            }
            try (InputStream full = new SequenceInputStream(new ByteArrayInputStream(header),
                    nonClosing(content))) {
                return parseWithParser(full, this.pdfParser);
            }
        }
        catch (DocumentParsingException ex) {
            throw ex;
        }
        catch (ExtractionLimitExceededException ex) {
            throw limitExceeded(ex);
        }
        catch (EncryptedDocumentException ex) {
            throw new DocumentParsingException(KnowledgeParseFailureCode.ENCRYPTED_DOCUMENT,
                    "文档已加密，无法解析", ex);
        }
        catch (TikaException | SAXException | IOException | RuntimeException ex) {
            throw mapFailure(ex);
        }
    }

    /**
     * DOCX：ZIP 初筛 → 有界落盘 → OOXML 包类型验证 → 正文提取。
     */
    private String parseDocx(InputStream content) {
        try {
            byte[] header = content.readNBytes(HEADER_LENGTH);
            if (!startsWith(header, ZIP_HEADER)) {
                throw unsupportedContent("内容与声明的文档格式不一致");
            }

            Path spooled = spoolToTemporaryFile(header, content);
            try {
                // 类型验证必须发生在正文提取之前：不符合的包不会被任何解析器读到
                verifyWordprocessingPackage(spooled);
                try (InputStream body = Files.newInputStream(spooled, StandardOpenOption.READ)) {
                    return parseWithParser(body, this.ooxmlParser);
                }
            }
            finally {
                deleteQuietly(spooled);
            }
        }
        catch (DocumentParsingException ex) {
            throw ex;
        }
        catch (ExtractionLimitExceededException ex) {
            throw limitExceeded(ex);
        }
        catch (EncryptedDocumentException ex) {
            throw new DocumentParsingException(KnowledgeParseFailureCode.ENCRYPTED_DOCUMENT,
                    "文档已加密，无法解析", ex);
        }
        catch (TikaException | SAXException | IOException | RuntimeException ex) {
            throw mapFailure(ex);
        }
    }

    /**
     * 文本格式：按 UTF-8 严格解码并按 code point 限量。
     *
     * <p>解码器显式配置为 {@code REPORT}：{@code InputStreamReader} 的默认行为是
     * 把非法字节替换成 {@code U+FFFD}，那样「内容不是合法 UTF-8」就会被静默吞掉，
     * 变成一份看起来正常、实际上已经损坏的文本。</p>
     *
     * <p>计数交给 {@link CodePointLimitedTextBuilder}：它与 SAX 路径共用同一套规则，
     * 因此跨 8192 char 解码缓冲区的代理对只计一次，开头的 BOM 不占配额。</p>
     */
    private String decodeUtf8(InputStream content) {
        java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);

        CodePointLimitedTextBuilder builder =
                new CodePointLimitedTextBuilder(this.maxExtractedCodePoints);

        try (java.io.Reader reader = new java.io.InputStreamReader(nonClosing(content), decoder)) {
            char[] buffer = new char[COPY_BUFFER_SIZE];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                builder.append(buffer, 0, read);
            }
            return builder.text();
        }
        catch (ExtractionLimitExceededException ex) {
            throw limitExceeded(ex);
        }
        catch (CharacterCodingException ex) {
            throw new DocumentParsingException(KnowledgeParseFailureCode.UNSUPPORTED_DOCUMENT_CONTENT,
                    "文本内容不是合法 UTF-8", ex);
        }
        catch (IOException ex) {
            throw new DocumentParsingException(KnowledgeParseFailureCode.CORRUPTED_DOCUMENT,
                    "文本内容读取失败", ex);
        }
    }

    /**
     * 调用第三方解析器，并把文本收集交给统一的限量写入器。
     */
    private String parseWithParser(InputStream content, Parser parser) throws IOException, SAXException,
            TikaException {

        ExtractedTextCollector collector = new ExtractedTextCollector(this.maxExtractedCodePoints);
        ParseContext context = new ParseContext();
        // 解析器由服务端选好并显式传入；刻意不往 Metadata 里写 CONTENT_TYPE —— 那只是「声明」，
        // 不能充当类型验证（FD-0009-R1 的类型混淆正说明声明不可信）
        context.set(Parser.class, parser);
        parser.parse(content, collector, new Metadata(), context);
        return collector.text();
    }

    /**
     * 把输入流以固定缓冲区写入一个受控临时文件（不整体读进堆）。
     *
     * @param header  已经读出的文件头，必须一并写入
     * @param content 调用方的流（只读，不关闭）
     * @return 临时文件路径
     * @throws IOException 写入失败
     */
    private static Path spoolToTemporaryFile(byte[] header, InputStream content) throws IOException {
        Path spooled = Files.createTempFile("flowdesk-knowledge-", ".pkg");
        try (OutputStream out = Files.newOutputStream(spooled, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            out.write(header);
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int read;
            while ((read = content.read(buffer)) >= 0) {
                if (read > 0) {
                    out.write(buffer, 0, read);
                }
            }
        }
        catch (IOException ex) {
            deleteQuietly(spooled);
            throw ex;
        }
        return spooled;
    }

    /**
     * 验证这是一个 WordprocessingML（DOCX）OOXML 包。
     *
     * <p>三个条件缺一不可，且全部只看包内容：</p>
     * <ol>
     *   <li>{@code [Content_Types].xml} 对 {@code /word/document.xml} 生效的内容类型
     *       必须是 WordprocessingML 主文档类型（Override 优先于 Default，与 OPC 规则一致）；</li>
     *   <li>{@code _rels/.rels} 里的 {@code officeDocument} 关系必须指向 {@code word/document.xml}
     *       —— 否则就是「用 DOCX 的内容类型伪装、实际让解析器去读别的部件」；</li>
     *   <li>该部件必须真实存在。</li>
     * </ol>
     * <p>ZIP 容器本身打不开 → 损坏（{@code CORRUPTED_DOCUMENT}）；
     * 包能打开但不能证明自己是 DOCX → 不受支持（{@code UNSUPPORTED_DOCUMENT_CONTENT}）。</p>
     *
     * @param spooled 已落盘的包文件
     * @throws DocumentParsingException 不是 DOCX 或包损坏
     */
    private static void verifyWordprocessingPackage(Path spooled) {
        ZipFile zip;
        try {
            zip = new ZipFile(spooled.toFile());
        }
        catch (IOException ex) {
            // 文件头是 PK、但容器本身读不出来：这是损坏而不是「类型不符」
            throw new DocumentParsingException(KnowledgeParseFailureCode.CORRUPTED_DOCUMENT,
                    "OOXML 容器损坏，无法读取", ex);
        }

        try (ZipFile open = zip) {
            String effectiveType = effectiveMainDocumentContentType(open);
            if (!DOCX_MAIN_CONTENT_TYPE.equals(effectiveType)) {
                throw unsupportedContent("OOXML 包的主文档部件不是 WordprocessingML");
            }
            if (!officeDocumentRelationshipPointsToWordDocument(open)) {
                throw unsupportedContent("OOXML 包的 officeDocument 关系未指向 word/document.xml");
            }
            if (open.getEntry(WORD_DOCUMENT_PART) == null) {
                // 包已经明确声明自己是 WordprocessingML、关系也指向主文档部件，但那个部件不存在：
                // 这是「声明为 DOCX 却内部损坏」，不是「类型不符」
                throw new DocumentParsingException(KnowledgeParseFailureCode.CORRUPTED_DOCUMENT,
                        "OOXML 包缺少已声明的主文档部件");
            }
        }
        catch (IOException ex) {
            throw new DocumentParsingException(KnowledgeParseFailureCode.CORRUPTED_DOCUMENT,
                    "OOXML 包元数据读取失败", ex);
        }
    }

    /**
     * 计算 {@code /word/document.xml} 的生效内容类型（Override 优先，其次按扩展名 Default）。
     *
     * @param zip 已打开的包
     * @return 生效的内容类型；无法确定时返回 {@code null}
     */
    private static String effectiveMainDocumentContentType(ZipFile zip) throws IOException {
        Document contentTypes = readPackageXml(zip, CONTENT_TYPES_PART);
        if (contentTypes == null) {
            return null;
        }
        Element root = contentTypes.getDocumentElement();
        if (root == null || !"Types".equals(root.getLocalName() == null ? root.getNodeName()
                : root.getLocalName())) {
            return null;
        }

        String partName = "/" + WORD_DOCUMENT_PART;
        NodeList children = root.getChildNodes();
        String defaultForXml = null;
        for (int index = 0; index < children.getLength(); index++) {
            Node node = children.item(index);
            if (!(node instanceof Element element)) {
                continue;
            }
            String localName = element.getLocalName() == null ? element.getNodeName() : element.getLocalName();
            if ("Override".equals(localName) && partName.equals(element.getAttribute("PartName"))) {
                return element.getAttribute("ContentType");
            }
            if ("Default".equals(localName) && "xml".equalsIgnoreCase(element.getAttribute("Extension"))) {
                defaultForXml = element.getAttribute("ContentType");
            }
        }
        return defaultForXml;
    }

    /**
     * @param zip 已打开的包
     * @return {@code _rels/.rels} 中的 officeDocument 关系是否指向 {@code word/document.xml}
     */
    private static boolean officeDocumentRelationshipPointsToWordDocument(ZipFile zip) throws IOException {
        Document relationships = readPackageXml(zip, PACKAGE_RELATIONSHIPS_PART);
        if (relationships == null) {
            return false;
        }
        NodeList nodes = relationships.getElementsByTagNameNS("*", "Relationship");
        for (int index = 0; index < nodes.getLength(); index++) {
            if (!(nodes.item(index) instanceof Element relationship)) {
                continue;
            }
            if (!OFFICE_DOCUMENT_RELATIONSHIP.equals(relationship.getAttribute("Type"))) {
                continue;
            }
            String target = relationship.getAttribute("Target");
            String normalized = target.startsWith("/") ? target.substring(1) : target;
            if (WORD_DOCUMENT_PART.equalsIgnoreCase(normalized)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 读取包内的一个小 XML 部件（有界读取 + 安全解析）。
     *
     * @param zip     已打开的包
     * @param partName 部件名
     * @return 解析后的文档；部件不存在或无法解析时返回 {@code null}
     */
    private static Document readPackageXml(ZipFile zip, String partName) throws IOException {
        ZipEntry entry = zip.getEntry(partName);
        if (entry == null) {
            return null;
        }
        byte[] xml = readBounded(zip.getInputStream(entry));
        if (xml == null) {
            // 元数据部件异常巨大：无法据此证明这是 DOCX
            throw unsupportedContent("OOXML 包元数据部件过大");
        }
        try {
            return secureDocumentBuilder().parse(new ByteArrayInputStream(xml));
        }
        catch (SAXException | ParserConfigurationException ex) {
            return null;
        }
    }

    /**
     * 有界读取：超过 {@link #MAX_PACKAGE_METADATA_BYTES} 返回 {@code null}（调用方按不受支持处理）。
     */
    private static byte[] readBounded(InputStream input) throws IOException {
        try (InputStream source = input; java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int total = 0;
            int read;
            while ((read = source.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                total += read;
                if (total > MAX_PACKAGE_METADATA_BYTES) {
                    return null;
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    /**
     * 安全 XML 解析器：禁止 DOCTYPE 与任何外部实体。
     */
    private static DocumentBuilder secureDocumentBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        return factory.newDocumentBuilder();
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        }
        catch (IOException ignored) {
            // 临时文件清理失败不影响解析结果，也不向调用方暴露路径
        }
    }

    /**
     * 包一层「关闭无效」的流：契约要求解析器不关闭调用方的流，
     * 而 {@code SequenceInputStream} / {@code InputStreamReader} 的关闭会传递到底层。
     */
    private static InputStream nonClosing(InputStream delegate) {
        return new java.io.FilterInputStream(delegate) {

            @Override
            public void close() {
                // 生命周期由调用方负责：这里刻意什么都不做
            }
        };
    }

    private static DocumentParsingException unsupportedContent(String message) {
        return new DocumentParsingException(KnowledgeParseFailureCode.UNSUPPORTED_DOCUMENT_CONTENT, message);
    }

    /**
     * 把解析器异常映射为稳定失败码。
     *
     * <p>只按<b>类型</b>判断，不解析消息文本 —— 消息会随第三方库版本变化，
     * 甚至可能带上路径等内部信息。</p>
     */
    private static DocumentParsingException mapFailure(Exception failure) {
        if (containsLimitExceeded(failure)) {
            return limitExceeded(failure);
        }
        if (containsType(failure, EncryptedDocumentException.class)
                || containsType(failure, "org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException")
                || containsType(failure, "org.apache.poi.EncryptedDocumentException")) {
            return new DocumentParsingException(KnowledgeParseFailureCode.ENCRYPTED_DOCUMENT,
                    "文档已加密，无法解析", failure);
        }
        if (failure instanceof IOException
                || containsType(failure, "org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException")
                || containsType(failure, "org.apache.poi.openxml4j.exceptions.InvalidFormatException")
                || containsType(failure, "org.apache.poi.POIXMLException")
                || containsType(failure, "org.apache.poi.ooxml.POIXMLException")
                || containsType(failure, "org.apache.poi.util.XMLHelper")
                || containsType(failure, "org.apache.pdfbox.io.RandomAccessRead")
                || failure instanceof SAXException) {
            return new DocumentParsingException(KnowledgeParseFailureCode.CORRUPTED_DOCUMENT,
                    "文档内容损坏，无法解析", failure);
        }
        return new DocumentParsingException(KnowledgeParseFailureCode.PARSER_FAILURE,
                "文档解析失败", failure);
    }

    private static DocumentParsingException limitExceeded(Throwable cause) {
        return new DocumentParsingException(KnowledgeParseFailureCode.EXTRACTED_TEXT_TOO_LARGE,
                "提取文本超过配置上限", cause);
    }

    private static boolean containsLimitExceeded(Throwable failure) {
        return containsType(failure, ExtractionLimitExceededException.class);
    }

    private static boolean containsType(Throwable failure, Class<? extends Throwable> type) {
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth < 10) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
            depth++;
        }
        return false;
    }

    /**
     * 按类名检查异常链：部分解析器异常来自可选依赖，直接引用会让编译期依赖变重。
     */
    private static boolean containsType(Throwable failure, String className) {
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth < 10) {
            if (current.getClass().getName().equals(className)) {
                return true;
            }
            current = current.getCause();
            depth++;
        }
        return false;
    }

    private static boolean startsWith(byte[] header, byte[] expected) {
        if (header.length < expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (header[index] != expected[index]) {
                return false;
            }
        }
        return true;
    }
}
