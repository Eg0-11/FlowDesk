package com.flowdesk.infrastructure.ticket.support;

import com.flowdesk.application.ticket.port.out.TicketIdGenerator;
import com.flowdesk.domain.ticket.TicketId;
import java.util.UUID;

/**
 * 基于随机 UUID 的工单标识生成器。
 *
 * <p>随机数只允许出现在基础设施层：应用层与领域层都通过端口获取标识，
 * 因此它们的用例行为完全确定、可重复测试。</p>
 */
public final class UuidTicketIdGenerator implements TicketIdGenerator {

    @Override
    public TicketId nextId() {
        return TicketId.of(UUID.randomUUID());
    }
}
