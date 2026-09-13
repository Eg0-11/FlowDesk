package com.flowdesk.application.ticket;

import com.flowdesk.application.ticket.port.out.TicketRepository;
import com.flowdesk.application.ticket.port.out.TicketSearchCriteria;
import com.flowdesk.application.ticket.port.out.TicketSearchResult;
import com.flowdesk.application.ticket.port.out.VersionedTicket;
import com.flowdesk.domain.ticket.Ticket;
import com.flowdesk.domain.ticket.TicketCategory;
import com.flowdesk.domain.ticket.TicketId;
import com.flowdesk.domain.ticket.TicketPriority;
import com.flowdesk.domain.ticket.TicketStatus;
import com.flowdesk.domain.ticket.UserId;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 内存工单存储（测试替身）。
 *
 * <p>刻意做到与真实适配器同等的隔离语义：</p>
 * <ul>
 *   <li>写入时把聚合<b>快照</b>成不可变字段，不保留调用方实例的引用；</li>
 *   <li>读取时用 {@link Ticket#restore} <b>重建</b>一个全新聚合，不把存储内部对象交出去；</li>
 *   <li>因此无论调用方如何修改自己手里的聚合，都不会影响存储，反之亦然 ——
 *       测试不会因为共享可变对象而虚假通过。</li>
 * </ul>
 */
final class InMemoryTicketRepository implements TicketRepository {

    private final Map<TicketId, Snapshot> storage = new LinkedHashMap<>();

    private final Set<TicketId> armedConcurrentWrites = new HashSet<>();

    private int findCalls;

    private int insertCalls;

    private int updateCalls;

    private int searchCalls;

    private TicketSearchCriteria lastCriteria;

    @Override
    public Optional<VersionedTicket> findById(TicketId ticketId) {
        this.findCalls++;
        Snapshot snapshot = this.storage.get(ticketId);
        return snapshot == null ? Optional.empty() : Optional.of(snapshot.toVersionedTicket());
    }

    /**
     * 记录查询条件并返回存储中的全部工单。
     *
     * <p><b>刻意不做筛选、排序与分页</b>：本替身用于验证「用例把条件原样传给了存储」
     * 以及「查询没有副作用」，而不是复刻 SQL 语义 ——
     * 筛选与排序的真实语义由 JDBC 集成测试在真实数据库上验证，避免在这里造第二份实现。</p>
     */
    @Override
    public TicketSearchResult search(TicketSearchCriteria criteria) {
        this.searchCalls++;
        this.lastCriteria = criteria;
        List<VersionedTicket> all = this.storage.values().stream()
                .map(Snapshot::toVersionedTicket)
                .toList();
        return new TicketSearchResult(all, all.size());
    }

    @Override
    public VersionedTicket insert(Ticket ticket) {
        this.insertCalls++;
        if (this.storage.containsKey(ticket.id())) {
            throw new TicketApplicationException(TicketApplicationErrorCode.TICKET_ALREADY_EXISTS, "工单已存在");
        }
        Snapshot snapshot = Snapshot.of(ticket, 0L);
        this.storage.put(ticket.id(), snapshot);
        return snapshot.toVersionedTicket();
    }

    @Override
    public VersionedTicket update(Ticket ticket, long expectedVersion) {
        this.updateCalls++;
        if (this.armedConcurrentWrites.remove(ticket.id())) {
            simulateConcurrentWrite(ticket.id());
        }
        Snapshot current = this.storage.get(ticket.id());
        if (current == null) {
            throw new TicketApplicationException(TicketApplicationErrorCode.TICKET_NOT_FOUND, "工单不存在");
        }
        if (current.version() != expectedVersion) {
            throw new TicketApplicationException(TicketApplicationErrorCode.TICKET_VERSION_CONFLICT,
                    "工单版本不匹配");
        }
        Snapshot updated = Snapshot.of(ticket, expectedVersion + 1);
        this.storage.put(ticket.id(), updated);
        return updated.toVersionedTicket();
    }

    int findCalls() {
        return this.findCalls;
    }

    int insertCalls() {
        return this.insertCalls;
    }

    int updateCalls() {
        return this.updateCalls;
    }

    int searchCalls() {
        return this.searchCalls;
    }

    TicketSearchCriteria lastCriteria() {
        return this.lastCriteria;
    }

    boolean contains(TicketId ticketId) {
        return this.storage.containsKey(ticketId);
    }

    long versionOf(TicketId ticketId) {
        Snapshot snapshot = this.storage.get(ticketId);
        if (snapshot == null) {
            throw new IllegalStateException("存储中没有该工单");
        }
        return snapshot.version();
    }

    /**
     * 模拟「用例读取之后、写回之前」发生的并发提交：把存储中的版本直接加 1。
     * 用于验证 CAS 失败被正确传播。
     */
    void simulateConcurrentWrite(TicketId ticketId) {
        Snapshot current = this.storage.get(ticketId);
        if (current == null) {
            throw new IllegalStateException("存储中没有该工单");
        }
        this.storage.put(ticketId, current.withVersion(current.version() + 1));
    }

    /**
     * 让下一次针对该工单的 {@code update} 先遭遇一次并发提交，用于验证
     * 「读取成功、写入时 CAS 失败」这条真实竞争路径。
     */
    void armConcurrentWriteBeforeNextUpdate(TicketId ticketId) {
        this.armedConcurrentWrites.add(ticketId);
    }

    /**
     * 不可变的存储快照：只保存值，不保存聚合引用。
     */
    private record Snapshot(TicketId id,
                            String title,
                            String description,
                            TicketCategory category,
                            TicketPriority priority,
                            UserId requesterId,
                            UserId assigneeId,
                            TicketStatus status,
                            String resolution,
                            Instant createdAt,
                            Instant updatedAt,
                            Instant resolvedAt,
                            Instant closedAt,
                            long version) {

        static Snapshot of(Ticket ticket, long version) {
            return new Snapshot(ticket.id(), ticket.title(), ticket.description(), ticket.category(),
                    ticket.priority(), ticket.requesterId(), ticket.assigneeId().orElse(null), ticket.status(),
                    ticket.resolution().orElse(null), ticket.createdAt(), ticket.updatedAt(),
                    ticket.resolvedAt().orElse(null), ticket.closedAt().orElse(null), version);
        }

        Snapshot withVersion(long newVersion) {
            return new Snapshot(this.id, this.title, this.description, this.category, this.priority,
                    this.requesterId, this.assigneeId, this.status, this.resolution, this.createdAt,
                    this.updatedAt, this.resolvedAt, this.closedAt, newVersion);
        }

        VersionedTicket toVersionedTicket() {
            Ticket ticket = Ticket.restore(this.id, this.title, this.description, this.category, this.priority,
                    this.requesterId, this.assigneeId, this.status, this.resolution, this.createdAt,
                    this.updatedAt, this.resolvedAt, this.closedAt);
            return new VersionedTicket(ticket, this.version);
        }
    }
}
