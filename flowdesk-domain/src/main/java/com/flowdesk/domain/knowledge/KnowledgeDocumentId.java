package com.flowdesk.domain.knowledge;

import java.util.Objects;
import java.util.UUID;

/**
 * 知识文档标识值对象。
 *
 * <p>与工单标识同样采用<b>严格解析</b>：只接受规范的 36 位连字符 UUID（大小写不敏感），
 * 拒绝缩写形式（如 {@code 1-1-1-1-1}）、缺少连字符与首尾空白。</p>
 *
 * @param value 非空的 UUID
 */
public record KnowledgeDocumentId(UUID value) {

    /** 规范 UUID 文本长度：32 个十六进制字符 + 4 个连字符。 */
    private static final int CANONICAL_LENGTH = 36;

    /** 规范的连字符位置。 */
    private static final int[] DASH_POSITIONS = { 8, 13, 18, 23 };

    /**
     * @param value 非空 UUID
     * @throws KnowledgeDomainException {@code value} 为 {@code null} 时抛出 {@code INVALID_DOCUMENT_ID}
     */
    public KnowledgeDocumentId {
        if (value == null) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_DOCUMENT_ID, "文档标识不能为空");
        }
    }

    /**
     * @param value UUID
     * @return 文档标识
     */
    public static KnowledgeDocumentId of(UUID value) {
        return new KnowledgeDocumentId(value);
    }

    /**
     * 严格解析文本形式的标识。
     *
     * @param raw 待解析文本
     * @return 文档标识
     * @throws KnowledgeDomainException 文本为 {@code null} 或不是规范 UUID 形式时抛出
     *                                  {@code INVALID_DOCUMENT_ID}；异常信息不回显原始文本
     */
    public static KnowledgeDocumentId parse(String raw) {
        if (raw == null) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_DOCUMENT_ID, "文档标识不能为空");
        }
        if (!isCanonicalUuid(raw)) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_DOCUMENT_ID, "文档标识格式不合法");
        }
        return new KnowledgeDocumentId(UUID.fromString(raw));
    }

    /**
     * 判断文本是否为规范的 36 位连字符 UUID（忽略大小写）。
     */
    private static boolean isCanonicalUuid(String candidate) {
        if (candidate.length() != CANONICAL_LENGTH) {
            return false;
        }
        int dashIndex = 0;
        for (int index = 0; index < CANONICAL_LENGTH; index++) {
            char character = candidate.charAt(index);
            if (dashIndex < DASH_POSITIONS.length && index == DASH_POSITIONS[dashIndex]) {
                if (character != '-') {
                    return false;
                }
                dashIndex++;
                continue;
            }
            boolean hexDigit = (character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f')
                    || (character >= 'A' && character <= 'F');
            if (!hexDigit) {
                return false;
            }
        }
        return dashIndex == DASH_POSITIONS.length;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof KnowledgeDocumentId id && Objects.equals(this.value, id.value);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(this.value);
    }

    @Override
    public String toString() {
        return this.value.toString();
    }
}
