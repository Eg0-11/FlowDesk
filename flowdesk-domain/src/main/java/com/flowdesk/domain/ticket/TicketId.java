package com.flowdesk.domain.ticket;

import java.util.UUID;

/**
 * 工单标识值对象，内部使用 {@link UUID}。
 *
 * @param value 非空的 UUID
 */
public record TicketId(UUID value) {

    /** 规范 UUID 文本的长度：32 个十六进制字符 + 4 个连字符。 */
    private static final int CANONICAL_LENGTH = 36;

    /** 规范的连字符位置。 */
    private static final int[] DASH_POSITIONS = { 8, 13, 18, 23 };

    /**
     * @param value 非空的 UUID
     * @throws TicketDomainException 当 {@code value} 为 {@code null} 时抛出 {@code INVALID_TICKET_ID}
     */
    public TicketId {
        if (value == null) {
            throw new TicketDomainException(TicketErrorCode.INVALID_TICKET_ID, "工单标识不能为空");
        }
    }

    /**
     * 从 UUID 创建。
     *
     * @param value 非空 UUID
     * @return 工单标识
     */
    public static TicketId of(UUID value) {
        return new TicketId(value);
    }

    /**
     * 从字符串解析。解析为<b>严格模式</b>：只接受规范的 36 位连字符形式，
     * 大小写均可，但拒绝首尾空白、缩写形式（如 {@code 1-1-1-1-1}）、缺少连字符、
     * 多余字符以及任何非十六进制字符。
     *
     * <p>之所以不直接依赖 {@link UUID#fromString(String)}：该方法接受缩写分组，
     * 会把 {@code "1-1-1-1-1"} 解析成一个「看起来正常」的 UUID，从而让明显畸形的输入悄悄通过。</p>
     *
     * @param raw 待解析文本
     * @return 工单标识
     * @throws TicketDomainException 当文本为 {@code null} 或不满足规范形式时抛出 {@code INVALID_TICKET_ID}；
     *                               异常信息不回显原始文本
     */
    public static TicketId parse(String raw) {
        if (raw == null) {
            throw new TicketDomainException(TicketErrorCode.INVALID_TICKET_ID, "工单标识不能为空");
        }
        if (!isCanonicalUuid(raw)) {
            throw new TicketDomainException(TicketErrorCode.INVALID_TICKET_ID, "工单标识格式不合法");
        }
        return new TicketId(UUID.fromString(raw));
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
    public String toString() {
        return this.value.toString();
    }
}
