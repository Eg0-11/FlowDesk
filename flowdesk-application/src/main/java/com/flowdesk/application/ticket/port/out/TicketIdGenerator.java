package com.flowdesk.application.ticket.port.out;

import com.flowdesk.domain.ticket.TicketId;

/**
 * 工单标识生成输出端口。
 *
 * <p>由适配器决定标识来源（UUID、雪花号、数据库序列等）。应用层不自行生成标识，
 * 因此用例行为完全确定、可直接测试。</p>
 */
public interface TicketIdGenerator {

    /**
     * @return 一个新的、未被使用过的工单标识，永不为 {@code null}
     */
    TicketId nextId();
}
