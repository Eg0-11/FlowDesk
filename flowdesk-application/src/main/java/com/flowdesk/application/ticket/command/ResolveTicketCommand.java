package com.flowdesk.application.ticket.command;

import com.flowdesk.domain.ticket.TicketId;

/**
 * 提交处理结论命令：{@code IN_PROGRESS → RESOLVED}。
 *
 * @param ticketId        工单标识
 * @param resolution      处理结论
 * @param expectedVersion 调用方读取到的版本
 */
public record ResolveTicketCommand(TicketId ticketId, String resolution, long expectedVersion) {
}
