package com.flowdesk.application.ticket;

import com.flowdesk.application.ticket.port.out.TicketIdGenerator;
import com.flowdesk.domain.ticket.TicketId;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.UUID;

/**
 * 记录调用次数的标识生成器（测试替身）。
 *
 * <p>优先返回预先排队的标识；队列用尽后返回确定性序列标识，不会返回 {@code null}。</p>
 */
final class RecordingTicketIdGenerator implements TicketIdGenerator {

    private final Deque<TicketId> queued = new ArrayDeque<>();

    private long fallbackSequence;

    private int calls;

    RecordingTicketIdGenerator(TicketId... ticketIds) {
        this.queued.addAll(Arrays.asList(ticketIds));
    }

    @Override
    public TicketId nextId() {
        this.calls++;
        TicketId queuedId = this.queued.poll();
        return queuedId != null ? queuedId : TicketId.of(new UUID(0L, ++this.fallbackSequence));
    }

    int calls() {
        return this.calls;
    }

    boolean hasQueuedIds() {
        return !this.queued.isEmpty();
    }
}
