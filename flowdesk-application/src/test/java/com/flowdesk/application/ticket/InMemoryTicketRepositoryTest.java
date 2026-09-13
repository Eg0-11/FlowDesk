package com.flowdesk.application.ticket;

import static com.flowdesk.application.ticket.ApplicationTestSupport.ALICE;
import static com.flowdesk.application.ticket.ApplicationTestSupport.BASE;
import static com.flowdesk.application.ticket.ApplicationTestSupport.BOB;
import static com.flowdesk.application.ticket.ApplicationTestSupport.CATEGORY;
import static com.flowdesk.application.ticket.ApplicationTestSupport.DESCRIPTION;
import static com.flowdesk.application.ticket.ApplicationTestSupport.MISSING_TICKET_ID;
import static com.flowdesk.application.ticket.ApplicationTestSupport.PRIORITY;
import static com.flowdesk.application.ticket.ApplicationTestSupport.TICKET_ID;
import static com.flowdesk.application.ticket.ApplicationTestSupport.TITLE;
import static com.flowdesk.application.ticket.ApplicationTestSupport.assertApplicationError;
import static org.assertj.core.api.Assertions.assertThat;

import com.flowdesk.application.ticket.port.out.VersionedTicket;
import com.flowdesk.domain.ticket.Ticket;
import com.flowdesk.domain.ticket.TicketStatus;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 存储端口契约测试 —— 用测试替身固化 {@code TicketRepository} 的版本、并发与隔离语义。
 *
 * <p>同时也是对替身本身的检验：它必须复制/恢复聚合，不能靠共享可变对象让用例测试虚假通过。</p>
 */
class InMemoryTicketRepositoryTest {

    private final InMemoryTicketRepository repository = new InMemoryTicketRepository();

    @BeforeEach
    void insertTicket() {
        this.repository.insert(newTicket());
    }

    @Test
    void newTicketStartsAtVersionZero() {
        VersionedTicket inserted = this.repository.insert(newTicketWithOtherId());

        assertThat(inserted.version()).isZero();
        assertThat(this.repository.versionOf(TICKET_ID)).isZero();
    }

    @Test
    void updateReturnsTheStrictlyIncrementedVersion() {
        Ticket ticket = this.repository.findById(TICKET_ID).orElseThrow().ticket();
        ticket.assign(BOB, BASE.plusSeconds(60));

        VersionedTicket updated = this.repository.update(ticket, 0L);

        assertThat(updated.version()).isEqualTo(1);
        assertThat(this.repository.versionOf(TICKET_ID)).isEqualTo(1);
    }

    @Test
    void insertRejectsADuplicateId() {
        assertApplicationError(() -> this.repository.insert(newTicket()),
                TicketApplicationErrorCode.TICKET_ALREADY_EXISTS);
    }

    @Test
    void updateReportsMissingRecordDistinctlyFromVersionMismatch() {
        Ticket ticket = this.repository.findById(TICKET_ID).orElseThrow().ticket();

        assertApplicationError(() -> this.repository.update(ticket, 5L),
                TicketApplicationErrorCode.TICKET_VERSION_CONFLICT);
    }

    @Test
    void updateRejectsAnUnknownTicket() {
        Ticket unknown = Ticket.create(MISSING_TICKET_ID, TITLE, DESCRIPTION, CATEGORY, PRIORITY, ALICE, BASE);

        assertApplicationError(() -> this.repository.update(unknown, 0L),
                TicketApplicationErrorCode.TICKET_NOT_FOUND);
    }

    @Test
    void findByIdReturnsEmptyOptionalInsteadOfNull() {
        Optional<VersionedTicket> found = this.repository.findById(MISSING_TICKET_ID);

        assertThat(found).isNotNull();
        assertThat(found).isEmpty();
    }

    @Test
    void findByIdReturnsAFreshAggregateEveryTime() {
        Ticket first = this.repository.findById(TICKET_ID).orElseThrow().ticket();
        Ticket second = this.repository.findById(TICKET_ID).orElseThrow().ticket();

        assertThat(first).isNotSameAs(second);
        assertThat(first).isEqualTo(second);
    }

    @Test
    void mutatingAReturnedAggregateDoesNotAffectStorage() {
        Ticket returned = this.repository.findById(TICKET_ID).orElseThrow().ticket();

        returned.assign(BOB, BASE.plusSeconds(60));
        returned.start(BASE.plusSeconds(120));

        Ticket reread = this.repository.findById(TICKET_ID).orElseThrow().ticket();
        assertThat(reread.status()).isEqualTo(TicketStatus.NEW);
        assertThat(reread.assigneeId()).isEmpty();
    }

    @Test
    void mutatingAnUpdatedAggregateDoesNotAffectTheStoredSnapshot() {
        Ticket ticket = this.repository.findById(TICKET_ID).orElseThrow().ticket();
        ticket.assign(BOB, BASE.plusSeconds(60));
        this.repository.update(ticket, 0L);

        // 调用方继续修改自己手里的同一个实例
        ticket.start(BASE.plusSeconds(120));
        ticket.resolve("已处理", BASE.plusSeconds(180));

        Ticket reread = this.repository.findById(TICKET_ID).orElseThrow().ticket();
        assertThat(reread.status()).as("存储必须停在 update 时刻的快照").isEqualTo(TicketStatus.ASSIGNED);
        assertThat(reread.resolution()).isEmpty();
    }

    @Test
    void concurrentWriteMakesTheNextUpdateFailTheCompareAndSet() {
        this.repository.armConcurrentWriteBeforeNextUpdate(TICKET_ID);
        Ticket ticket = this.repository.findById(TICKET_ID).orElseThrow().ticket();
        ticket.assign(BOB, BASE.plusSeconds(60));

        assertApplicationError(() -> this.repository.update(ticket, 0L),
                TicketApplicationErrorCode.TICKET_VERSION_CONFLICT);

        assertThat(this.repository.versionOf(TICKET_ID)).isEqualTo(1);
        assertThat(this.repository.findById(TICKET_ID).orElseThrow().ticket().status())
                .isEqualTo(TicketStatus.NEW);
    }

    private static Ticket newTicket() {
        return Ticket.create(TICKET_ID, TITLE, DESCRIPTION, CATEGORY, PRIORITY, ALICE, BASE);
    }

    private static Ticket newTicketWithOtherId() {
        return Ticket.create(MISSING_TICKET_ID, TITLE, DESCRIPTION, CATEGORY, PRIORITY, ALICE, BASE);
    }
}
