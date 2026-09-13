package com.flowdesk.domain.knowledge;

/**
 * 文档标题值对象。
 *
 * <p>必须<b>已经</b> strip：本类刻意不做静默 strip —— 规范化是输入适配器与应用层的职责，
 * 静默修正会让「忘了规范化」的实现一直看不出来，而且同一份输入在不同入口可能得到不同结果。</p>
 *
 * @param value 已去掉首尾空白的标题，长度 1～{@value #MAX_LENGTH}
 */
public record DocumentTitle(String value) {

    /** 标题最大长度。 */
    public static final int MAX_LENGTH = 200;

    /**
     * @param value 标题
     * @throws KnowledgeDomainException 为空、含首尾空白或超长时抛出 {@code INVALID_TITLE}；
     *                                  异常信息不回显原始文本
     */
    public DocumentTitle {
        if (value == null || value.isEmpty()) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_TITLE, "标题不能为空");
        }
        if (!value.equals(value.strip())) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_TITLE,
                    "标题必须是已去掉首尾空白的形式");
        }
        if (value.length() > MAX_LENGTH) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_TITLE,
                    "标题长度不能超过 " + MAX_LENGTH + " 个字符");
        }
    }

    /**
     * @param value 标题
     * @return 标题值对象
     */
    public static DocumentTitle of(String value) {
        return new DocumentTitle(value);
    }

    @Override
    public String toString() {
        return this.value;
    }
}
