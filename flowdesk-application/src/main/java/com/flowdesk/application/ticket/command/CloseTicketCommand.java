package com.flowdesk.application.ticket.command;

import com.flowdesk.domain.ticket.TicketId;

/**
 * 关闭工单命令：{@code RESOLVED → CLOSED}。
 *
 * @param ticketId        工单标识
 * @param expectedVersion 调用方读取到的版本
 */
public record CloseTicketCommand(TicketId ticketId, long expectedVersion) {
}
