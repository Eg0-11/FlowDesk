package com.flowdesk.application.ticket.command;

import com.flowdesk.domain.ticket.TicketId;

/**
 * 开始处理命令：{@code ASSIGNED → IN_PROGRESS}。
 *
 * @param ticketId        工单标识
 * @param expectedVersion 调用方读取到的版本
 */
public record StartTicketCommand(TicketId ticketId, long expectedVersion) {
}
