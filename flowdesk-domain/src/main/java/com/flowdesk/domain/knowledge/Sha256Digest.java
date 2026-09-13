package com.flowdesk.domain.knowledge;

import java.util.Locale;

/**
 * 内容摘要（SHA-256）值对象。
 *
 * <p>只接受<b>64 位小写十六进制</b>文本：大小写不敏感地接受输入后统一转成小写，
 * 这样数据库里永远只有一种表示，比较与索引都不会出现「同一摘要两种写法」。</p>
 *
 * @param value 64 位小写十六进制摘要
 */
public record Sha256Digest(String value) {

    /** 十六进制摘要长度。 */
    public static final int HEX_LENGTH = 64;

    /**
     * @param value 摘要文本，允许大写，会统一转成小写
     * @throws KnowledgeDomainException 为 {@code null}、长度不为 64 或含非十六进制字符时抛出
     *                                  {@code INVALID_DIGEST}；异常信息不回显原始文本
     */
    public Sha256Digest {
        if (value == null) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_DIGEST, "内容摘要不能为空");
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.length() != HEX_LENGTH || !isHex(normalized)) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_DIGEST,
                    "内容摘要必须是 " + HEX_LENGTH + " 位十六进制字符");
        }
        value = normalized;
    }

    /**
     * @param value 摘要文本
     * @return 内容摘要
     */
    public static Sha256Digest of(String value) {
        return new Sha256Digest(value);
    }

    private static boolean isHex(String candidate) {
        for (int index = 0; index < candidate.length(); index++) {
            char character = candidate.charAt(index);
            boolean hexDigit = (character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f');
            if (!hexDigit) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return this.value;
    }
}
