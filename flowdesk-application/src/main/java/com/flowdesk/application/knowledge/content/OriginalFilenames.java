package com.flowdesk.application.knowledge.content;

/**
 * 原始文件名的规范化：把客户端传来的东西收敛成一个<b>纯文件名</b>。
 *
 * <p>浏览器在 multipart 里可能给出完整路径（历史行为：{@code C:\fakepath\report.pdf}），
 * 也可能是恶意构造的 {@code ../../etc/passwd}。这里统一按 {@code /} 与 {@code \} 取最后一段，
 * 与领域层的「文件名不得含路径分隔符」形成两道关卡。</p>
 *
 * <p><b>规范化后的文件名只作为元数据</b>：它不会参与任何存储路径拼接
 * （内容键由文档标识生成，见 {@link com.flowdesk.application.knowledge.port.out.KnowledgeDocumentContentStore}）。</p>
 */
public final class OriginalFilenames {

    /** 文件名最大长度，与领域常量一致。 */
    public static final int MAX_LENGTH = 255;

    private OriginalFilenames() {
    }

    /**
     * 取最后一段路径作为文件名，并去掉首尾空白。
     *
     * @param rawFileName 客户端提供的原始文件名，可为 {@code null}
     * @return 规范化后的文件名；无法得到非空文件名时返回 {@code null}
     */
    public static String basename(String rawFileName) {
        if (rawFileName == null) {
            return null;
        }
        String candidate = rawFileName;
        int slash = Math.max(candidate.lastIndexOf('/'), candidate.lastIndexOf('\\'));
        if (slash >= 0) {
            candidate = candidate.substring(slash + 1);
        }
        String stripped = candidate.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    /**
     * 去掉首尾空白，空则返回 {@code null}。
     *
     * @param raw 原始文本
     * @return 规范化文本或 {@code null}
     */
    public static String stripToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String stripped = raw.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
