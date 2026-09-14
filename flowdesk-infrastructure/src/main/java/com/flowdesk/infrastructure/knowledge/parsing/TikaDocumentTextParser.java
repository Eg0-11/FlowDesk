package com.flowdesk.infrastructure.knowledge.parsing;

import com.flowdesk.application.knowledge.parse.DocumentParsingException;
import com.flowdesk.application.knowledge.port.out.DocumentTextParser;
import com.flowdesk.domain.knowledge.DocumentFormat;
import com.flowdesk.domain.knowledge.KnowledgeParseFailureCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.microsoft.ooxml.OOXMLParser;
import org.apache.tika.parser.pdf.PDFParser;
import org.xml.sax.SAXException;

/**
 * 基于 Apache Tika 3.x 的文本提取适配器（FD-0009）。
 *
 * <h2>格式以服务端记录为准</h2>
 * <p>解析器由<b>数据库里保存的 {@link DocumentFormat}</b> 直接决定（PDF → PDFBox、DOCX → POI），
 * 而不是让 Tika 按文件名或内容自动探测：上传阶段的格式识别才是权威，
 * 解析阶段再探测一次只会得到「同一份文件两条结论」。</p>
 *
 * <p>Markdown 与纯文本<b>不</b>走解析框架：它们在 FD-0008 已经通过 UTF-8 与 NUL 校验，
 * 直接按 UTF-8 解码更简单、更确定，也少一层可能出错的转换。</p>
 *
 * <h2>安全边界</h2>
 * <ul>
 *   <li>先做文件头校验（PDF 必须 {@code %PDF-}、DOCX 必须是 ZIP）—— 伪造格式映射为
 *       {@code UNSUPPORTED_DOCUMENT_CONTENT}，而不是含糊的「解析失败」；</li>
 *   <li>不访问任何网络资源：解析只用本地解析器，Tika 的 XML 读取默认忽略外部实体；</li>
 *   <li>加密文档 → {@code ENCRYPTED_DOCUMENT}，损坏文档 → {@code CORRUPTED_DOCUMENT}，
 *       其它 → {@code PARSER_FAILURE}；映射基于<b>异常类型</b>而不是消息文本；</li>
 *   <li>第三方解析器的异常消息只作为 cause 保留在服务端，绝不进入响应；</li>
 *   <li>提取文本按 code point 限量，超限立即中断（见 {@link ExtractedTextCollector}）。</li>
 * </ul>
 *
 * <p>本类<b>不关闭</b>传入的流：生命周期由调用方负责。</p>
 */
public final class TikaDocumentTextParser implements DocumentTextParser {

    private static final byte[] PDF_HEADER = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    private static final byte[] ZIP_HEADER = { 0x50, 0x4B, 0x03, 0x04 };

    private static final int HEADER_LENGTH = 5;

    private final int maxExtractedCodePoints;

    private final Parser pdfParser = new PDFParser();

    private final Parser ooxmlParser = new OOXMLParser();

    /**
     * @param maxExtractedCodePoints 单次解析允许提取的最大 code point 数
     */
    public TikaDocumentTextParser(int maxExtractedCodePoints) {
        if (maxExtractedCodePoints <= 0) {
            throw new IllegalArgumentException("maxExtractedCodePoints 必须大于 0");
        }
        this.maxExtractedCodePoints = maxExtractedCodePoints;
    }

    @Override
    public String parse(InputStream content, DocumentFormat format) {
        Objects.requireNonNull(content, "content 不能为 null");
        Objects.requireNonNull(format, "format 不能为 null");

        return switch (format) {
            case PDF -> parseBinary(content, PDF_HEADER, this.pdfParser);
            case DOCX -> parseBinary(content, ZIP_HEADER, this.ooxmlParser);
            case MARKDOWN, TEXT -> decodeUtf8(content);
        };
    }

    /**
     * 二进制格式：先校验文件头，再交给对应解析器。
     */
    private String parseBinary(InputStream content, byte[] expectedHeader, Parser parser) {
        try {
            byte[] header = content.readNBytes(HEADER_LENGTH);
            if (!startsWith(header, expectedHeader)) {
                throw new DocumentParsingException(KnowledgeParseFailureCode.UNSUPPORTED_DOCUMENT_CONTENT,
                        "内容与声明的文档格式不一致");
            }
            ExtractedTextCollector collector = new ExtractedTextCollector(this.maxExtractedCodePoints);
            Metadata metadata = new Metadata();
            // 显式声明内容类型：解析器由服务端选好，这里只是让 Tika 内部不再做探测
            metadata.set(Metadata.CONTENT_TYPE, expectedHeader == PDF_HEADER ? "application/pdf"
                    : "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            ParseContext context = new ParseContext();
            context.set(Parser.class, parser);

            try (InputStream full = new SequenceInputStream(new ByteArrayInputStream(header),
                    nonClosing(content))) {
                parser.parse(full, collector, metadata, context);
            }
            return collector.text();
        }
        catch (DocumentParsingException ex) {
            throw ex;
        }
        catch (ExtractedTextCollector.ExtractionLimitExceededException ex) {
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
     */
    private String decodeUtf8(InputStream content) {
        java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);

        try (java.io.Reader reader = new java.io.InputStreamReader(nonClosing(content), decoder)) {
            StringBuilder text = new StringBuilder();
            char[] buffer = new char[8192];
            int codePoints = 0;
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                for (int index = 0; index < read; index++) {
                    char current = buffer[index];
                    boolean pair = Character.isHighSurrogate(current) && index + 1 < read
                            && Character.isLowSurrogate(buffer[index + 1]);
                    codePoints++;
                    if (codePoints > this.maxExtractedCodePoints) {
                        throw limitExceeded(null);
                    }
                    text.append(current);
                    if (pair) {
                        text.append(buffer[index + 1]);
                        index++;
                    }
                }
            }
            return stripByteOrderMark(text.toString());
        }
        catch (DocumentParsingException ex) {
            throw ex;
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
     * 去掉文本开头的 BOM（{@code U+FEFF}）。
     *
     * <p>带 BOM 的 UTF-8 文本很常见；保留它会让第一个切片以一个不可见字符开头，
     * 也会影响摘要与后续向量化的稳定性。</p>
     */
    private static String stripByteOrderMark(String text) {
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
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
        return containsType(failure, ExtractedTextCollector.ExtractionLimitExceededException.class);
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
