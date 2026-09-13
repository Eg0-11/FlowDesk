package com.flowdesk.application.knowledge.content;

import com.flowdesk.domain.knowledge.DocumentFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 客户端声明的 {@code Content-Type} 与识别出的格式之间的<b>一致性规则</b>。
 *
 * <p>为什么需要它：扩展名与内容本身都能伪造，声明也不能盲信；反过来，
 * 一个明确声明了 {@code image/png} 却叫 {@code report.pdf} 的上传几乎一定是错的，
 * 早一点拒绝比入库后再发现好。</p>
 *
 * <h2>规则</h2>
 * <ul>
 *   <li>声明为空或 {@code application/octet-stream} → <b>不做判断</b>，完全交给扩展名 + 内容校验
 *       （浏览器对未知类型就是这么发的，拒绝它们没有意义）；</li>
 *   <li>其余声明必须落在该格式的兼容集合里，否则拒绝；</li>
 *   <li>参数被忽略（{@code text/plain; charset=utf-8} 视为 {@code text/plain}），比较大小写不敏感。</li>
 * </ul>
 */
public final class DocumentMediaTypes {

    private static final String GENERIC = "application/octet-stream";

    /** 各格式可接受的声明类型。 */
    private static final Map<DocumentFormat, Set<String>> COMPATIBLE = Map.of(
            DocumentFormat.PDF, Set.of("application/pdf"),
            DocumentFormat.DOCX, Set.of(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/zip", "application/x-zip-compressed"),
            DocumentFormat.MARKDOWN, Set.of("text/markdown", "text/plain", "text/x-markdown"),
            DocumentFormat.TEXT, Set.of("text/plain"));

    private DocumentMediaTypes() {
    }

    /**
     * 判断声明类型是否与格式兼容。
     *
     * @param format              识别出的格式
     * @param declaredContentType 客户端声明的类型，可为 {@code null} 或空
     * @return 兼容（或未声明）返回 {@code true}
     */
    public static boolean isCompatible(DocumentFormat format, String declaredContentType) {
        String normalized = normalize(declaredContentType);
        if (normalized == null || GENERIC.equals(normalized)) {
            return true;
        }
        return COMPATIBLE.getOrDefault(format, Set.of()).contains(normalized);
    }

    /**
     * 规范化声明类型：去参数、去首尾空白、转小写。
     *
     * @param declaredContentType 原始声明类型
     * @return 规范化结果；未声明时返回 {@code null}
     */
    public static String normalize(String declaredContentType) {
        if (declaredContentType == null) {
            return null;
        }
        String value = declaredContentType.strip();
        if (value.isEmpty()) {
            return null;
        }
        int semicolon = value.indexOf(';');
        if (semicolon >= 0) {
            value = value.substring(0, semicolon).strip();
        }
        return value.isEmpty() ? null : value.toLowerCase(Locale.ROOT);
    }

    /**
     * 该格式的规范媒体类型；与领域枚举保持单一来源。
     *
     * @param format 格式
     * @return 规范媒体类型
     */
    public static String canonical(DocumentFormat format) {
        return format.canonicalMediaType();
    }
}
