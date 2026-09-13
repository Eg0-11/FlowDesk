package com.flowdesk.domain.knowledge;

/**
 * 原始文件名值对象：只表示<b>文件名本身</b>。
 *
 * <p>拒绝 {@code /}、{@code \}、NUL 与任何 ISO 控制字符，是<b>安全不变量</b>而非格式洁癖：
 * 文件名会被展示、记录、也可能被后续代码当成路径的一部分。允许 {@code ../} 或 {@code C:\...}
 * 出现在这个值里，等于把路径穿越的种子存进了数据库。</p>
 *
 * <p>值本身<b>永远不参与存储路径拼接</b>：内容键由文档标识生成，
 * 见 {@code docs/adr/0005-knowledge-document-upload-storage.md}。</p>
 *
 * @param value 已去掉首尾空白的纯文件名，长度 1～{@value #MAX_LENGTH}
 */
public record OriginalFilename(String value) {

    /** 文件名最大长度。 */
    public static final int MAX_LENGTH = 255;

    /**
     * @param value 文件名
     * @throws KnowledgeDomainException 为空、含路径分隔符/控制字符、含首尾空白或超长时抛出
     *                                  {@code INVALID_ORIGINAL_FILENAME}；异常信息不回显原始文本
     */
    public OriginalFilename {
        if (value == null || value.isEmpty()) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_ORIGINAL_FILENAME,
                    "原始文件名不能为空");
        }
        if (!value.equals(value.strip())) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_ORIGINAL_FILENAME,
                    "原始文件名必须是已去掉首尾空白的形式");
        }
        if (value.length() > MAX_LENGTH) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_ORIGINAL_FILENAME,
                    "原始文件名长度不能超过 " + MAX_LENGTH + " 个字符");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '/' || character == '\\' || character == '\0') {
                throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_ORIGINAL_FILENAME,
                        "原始文件名只能是文件名，不能包含路径分隔符");
            }
            if (Character.isISOControl(character)) {
                throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_ORIGINAL_FILENAME,
                        "原始文件名不能包含控制字符");
            }
        }
    }

    /**
     * @param value 文件名
     * @return 文件名值对象
     */
    public static OriginalFilename of(String value) {
        return new OriginalFilename(value);
    }

    /**
     * 扩展名（不含点，可能为空字符串）。
     *
     * @return 小写扩展名；没有扩展名时返回空字符串
     */
    public String extension() {
        int dot = this.value.lastIndexOf('.');
        if (dot < 0 || dot == this.value.length() - 1) {
            return "";
        }
        return this.value.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public String toString() {
        return this.value;
    }
}
