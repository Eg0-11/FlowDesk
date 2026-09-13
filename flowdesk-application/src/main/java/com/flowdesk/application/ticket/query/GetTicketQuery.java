package com.flowdesk.application.ticket.query;

import com.flowdesk.domain.ticket.TicketId;

/**
 * 查询单个工单。
 *
 * @param ticketId 工单标识
 */
public record GetTicketQuery(TicketId ticketId) {
}
