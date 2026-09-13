package com.flowdesk.application.ticket.command;

import com.flowdesk.domain.ticket.TicketId;
import com.flowdesk.domain.ticket.UserId;

/**
 * 重新分配处理人命令：{@code ASSIGNED → ASSIGNED}、{@code IN_PROGRESS → IN_PROGRESS}。
 *
 * @param ticketId        工单标识
 * @param newAssigneeId   新处理人
 * @param expectedVersion 调用方读取到的版本
 */
public record ReassignTicketCommand(TicketId ticketId, UserId newAssigneeId, long expectedVersion) {
}
