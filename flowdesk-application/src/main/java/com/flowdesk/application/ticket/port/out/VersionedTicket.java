package com.flowdesk.application.ticket.port.out;

import com.flowdesk.domain.ticket.Ticket;

/**
 * 带版本的工单快照。
 *
 * <p>版本用于乐观并发控制：调用方读取时拿到版本，写回时带上该版本，
 * 存储适配器据此做 compare-and-set。</p>
 *
 * @param ticket  工单聚合
 * @param version 版本号；新建工单为 0，每次成功更新后严格加 1
 */
public record VersionedTicket(Ticket ticket, long version) {
}
