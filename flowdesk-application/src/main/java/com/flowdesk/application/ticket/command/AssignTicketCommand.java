package com.flowdesk.application.ticket.command;

import com.flowdesk.domain.ticket.TicketId;
import com.flowdesk.domain.ticket.UserId;

/**
 * 分配处理人命令：{@code NEW → ASSIGNED}。
 *
 * @param ticketId        工单标识
 * @param assigneeId      处理人
 * @param expectedVersion 调用方读取到的版本
 */
public record AssignTicketCommand(TicketId ticketId, UserId assigneeId, long expectedVersion) {
}
