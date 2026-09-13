package com.flowdesk.domain.ticket;

/**
 * 用户标识值对象，内部使用字符串。
 *
 * <p>构造时用 {@link String#strip()} 标准化：去除首尾空白（含 Unicode 空白）后不能为空，
 * 且长度不超过 {@link #MAX_LENGTH}。</p>
 *
 * @param value 已标准化的用户标识
 */
public record UserId(String value) {

    /** 用户标识最大长度。 */
    public static final int MAX_LENGTH = 64;

    /**
     * @param value 用户标识原始文本
     * @throws TicketDomainException 空白或超长时抛出 {@code INVALID_USER_ID}；异常信息不回显原始输入
     */
    public UserId {
        if (value == null) {
            throw new TicketDomainException(TicketErrorCode.INVALID_USER_ID, "用户标识不能为空");
        }
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new TicketDomainException(TicketErrorCode.INVALID_USER_ID, "用户标识不能为空");
        }
        if (normalized.length() > MAX_LENGTH) {
            throw new TicketDomainException(TicketErrorCode.INVALID_USER_ID,
                    "用户标识长度不能超过 " + MAX_LENGTH + " 个字符");
        }
        value = normalized;
    }

    /**
     * 从字符串创建。
     *
     * @param value 用户标识原始文本
     * @return 用户标识
     */
    public static UserId of(String value) {
        return new UserId(value);
    }

    @Override
    public String toString() {
        return this.value;
    }
}
