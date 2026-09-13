package com.flowdesk.domain.ticket;

/**
 * 工单状态。
 *
 * <p>状态只允许通过 {@link Ticket} 的领域方法流转，不存在可任意赋值的入口。
 * 合法流转见 {@link Ticket} 的类注释。</p>
 */
public enum TicketStatus {

    /** 已创建、尚未分配处理人。 */
    NEW,

    /** 已分配处理人。 */
    ASSIGNED,

    /** 处理中。 */
    IN_PROGRESS,

    /** 已给出处理结论，等待关闭。 */
    RESOLVED,

    /** 已关闭。 */
    CLOSED
}
