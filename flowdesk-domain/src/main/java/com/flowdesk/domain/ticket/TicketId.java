package com.flowdesk.domain.ticket;

import java.util.UUID;

/**
 * 工单标识值对象，内部使用 {@link UUID}。
 *
 * @param value 非空的 UUID
 */
public record TicketId(UUID value) {

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
     * 从字符串解析。解析为严格模式：不接受首尾空白，也不接受 UUID 之外的任何形式。
     *
     * @param raw 待解析文本
     * @return 工单标识
     * @throws TicketDomainException 当文本为 {@code null} 或不是合法 UUID 时抛出 {@code INVALID_TICKET_ID}；
     *                               异常信息不回显原始文本
     */
    public static TicketId parse(String raw) {
        if (raw == null) {
            throw new TicketDomainException(TicketErrorCode.INVALID_TICKET_ID, "工单标识不能为空");
        }
        try {
            return new TicketId(UUID.fromString(raw));
        } catch (IllegalArgumentException ex) {
            throw new TicketDomainException(TicketErrorCode.INVALID_TICKET_ID, "工单标识格式不合法");
        }
    }

    @Override
    public String toString() {
        return this.value.toString();
    }
}
