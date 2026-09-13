package com.flowdesk.application.knowledge.content;

import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.application.knowledge.port.out.ContentSource;
import com.flowdesk.domain.knowledge.DocumentFormat;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 把「限流 + 格式内容校验」套在内容源外面的装饰器。
 *
 * <p>为什么用装饰器而不是让内容存储适配器做校验：格式策略属于<b>应用层规则</b>
 * （哪些格式支持、文件头是什么、文本要不要 UTF-8），而内容存储只该管字节。
 * 装饰后存储适配器拿到的仍是普通的 {@code ContentSource}，任何适配器
 * （本地文件系统、对象存储）都自动获得同样的校验，不需要各自实现一遍。</p>
 *
 * <p>校验是<b>流式</b>的：文件头只偷看固定长度，UTF-8 用增量解码器逐块判断，
 * 全程不把内容载入内存。</p>
 */
public final class GuardedContentSource implements ContentSource {

    /** PDF 文件头。 */
    private static final byte[] PDF_HEADER = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    /** ZIP 本地文件头（DOCX 是 ZIP 容器）。 */
    private static final byte[] ZIP_HEADER = { 0x50, 0x4B, 0x03, 0x04 };

    private final ContentSource delegate;

    private final DocumentFormat format;

    private final long maxBytes;

    private GuardedContentSource(ContentSource delegate, DocumentFormat format, long maxBytes) {
        this.delegate = delegate;
        this.format = format;
        this.maxBytes = maxBytes;
    }

    /**
     * 包装内容源。
     *
     * @param delegate 原始内容源
     * @param format   识别出的格式
     * @param maxBytes 允许的最大字节数（按实际读取量判断）
     * @return 带限流与内容校验的内容源
     */
    public static ContentSource guard(ContentSource delegate, DocumentFormat format, long maxBytes) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate 不能为 null");
        }
        if (format == null) {
            throw new IllegalArgumentException("format 不能为 null");
        }
        return new GuardedContentSource(delegate, format, maxBytes);
    }

    @Override
    public String declaredFileName() {
        return this.delegate.declaredFileName();
    }

    @Override
    public String declaredContentType() {
        return this.delegate.declaredContentType();
    }

    @Override
    public long declaredSize() {
        return this.delegate.declaredSize();
    }

    @Override
    public InputStream openStream() throws IOException {
        InputStream raw = this.delegate.openStream();
        InputStream limited = new SizeLimitedInputStream(raw, this.maxBytes, limit -> {
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.DOCUMENT_TOO_LARGE,
                    "上传内容超过允许的最大大小");
        });
        return switch (this.format) {
            case PDF -> new SignatureValidatingInputStream(limited, PDF_HEADER, GuardedContentSource::rejectSignature);
            case DOCX -> new SignatureValidatingInputStream(limited, ZIP_HEADER,
                    GuardedContentSource::rejectSignature);
            case MARKDOWN, TEXT -> new Utf8ValidatingInputStream(limited, reason -> {
                throw new KnowledgeApplicationException(
                        KnowledgeApplicationErrorCode.UNSUPPORTED_DOCUMENT_FORMAT, reason);
            });
        };
    }

    @Override
    public boolean opened() {
        return this.delegate.opened();
    }

    @Override
    public void close() {
        this.delegate.close();
    }

    private static void rejectSignature() {
        throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.UNSUPPORTED_DOCUMENT_FORMAT,
                "文件内容与声明的文档格式不一致");
    }
}
