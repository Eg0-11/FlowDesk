package com.flowdesk.domain.knowledge;

import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 支持的知识文档格式白名单。
 *
 * <p>本阶段只支持四种：{@code pdf}、{@code docx}、{@code md}、{@code txt}。
 * 格式由<b>扩展名</b>初步识别（大小写不敏感），再由内容做基本校验（文件头 / UTF-8），
 * 两者必须一致；内容校验在应用层的内容校验器里完成，领域层只负责「格式是什么」。</p>
 *
 * @param extension    规范扩展名（小写，不含点）
 * @param canonicalMediaType 该格式的规范媒体类型
 * @param binary       是否为二进制格式（需要文件头校验）
 */
public enum DocumentFormat {

    /** PDF 文档。 */
    PDF("pdf", "application/pdf", true),

    /** Word 文档（OOXML，本质是 ZIP 容器）。 */
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", true),

    /** Markdown 文本。 */
    MARKDOWN("md", "text/markdown", false),

    /** 纯文本。 */
    TEXT("txt", "text/plain", false);

    /** 无扩展名或扩展名不受支持时使用的错误说明。 */
    public static final String SUPPORTED_EXTENSIONS = Stream.of(values())
            .map(DocumentFormat::extension)
            .collect(Collectors.joining("、"));

    private final String extension;

    private final String canonicalMediaType;

    private final boolean binary;

    DocumentFormat(String extension, String canonicalMediaType, boolean binary) {
        this.extension = extension;
        this.canonicalMediaType = canonicalMediaType;
        this.binary = binary;
    }

    /**
     * @return 规范扩展名（小写、不含点）
     */
    public String extension() {
        return this.extension;
    }

    /**
     * @return 规范媒体类型
     */
    public String canonicalMediaType() {
        return this.canonicalMediaType;
    }

    /**
     * @return 是否为二进制格式
     */
    public boolean binary() {
        return this.binary;
    }

    /**
     * 按扩展名识别格式。
     *
     * @param extension 扩展名，可带点、可为大写
     * @return 匹配的格式；无法识别时返回 {@link Optional#empty()}
     */
    public static Optional<DocumentFormat> fromExtension(String extension) {
        if (extension == null) {
            return Optional.empty();
        }
        String normalized = extension.strip().toLowerCase(Locale.ROOT);
        if (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        for (DocumentFormat format : values()) {
            if (format.extension.equals(normalized)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }

    /**
     * 按文件名识别格式。
     *
     * <p>只取最后一个点之后的扩展名；没有点、以点结尾或扩展名不受支持时返回
     * {@link Optional#empty()}。文件名里的路径分隔符与本方法无关：
     * 调用方必须<b>先</b>把原始文件名收敛成纯文件名。</p>
     *
     * @param fileName 文件名
     * @return 匹配的格式；无法识别时返回 {@link Optional#empty()}
     */
    public static Optional<DocumentFormat> fromFileName(String fileName) {
        if (fileName == null) {
            return Optional.empty();
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return Optional.empty();
        }
        return fromExtension(fileName.substring(dot + 1));
    }
}
