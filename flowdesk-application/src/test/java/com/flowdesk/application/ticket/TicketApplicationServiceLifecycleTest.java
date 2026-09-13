package com.flowdesk.application.ticket;

import static com.flowdesk.application.ticket.ApplicationTestSupport.ALICE;
import static com.flowdesk.application.ticket.ApplicationTestSupport.BASE;
import static com.flowdesk.application.ticket.ApplicationTestSupport.BOB;
import static com.flowdesk.application.ticket.ApplicationTestSupport.CATEGORY;
import static com.flowdesk.application.ticket.ApplicationTestSupport.DESCRIPTION;
import static com.flowdesk.application.ticket.ApplicationTestSupport.PRIORITY;
import static com.flowdesk.application.ticket.ApplicationTestSupport.RESOLUTION;
import static com.flowdesk.application.ticket.ApplicationTestSupport.TICKET_ID;
import static com.flowdesk.application.ticket.ApplicationTestSupport.TITLE;
import static com.flowdesk.application.ticket.ApplicationTestSupport.assertDomainError;
import static org.assertj.core.api.Assertions.assertThat;

import com.flowdesk.application.ticket.command.AssignTicketCommand;
import com.flowdesk.application.ticket.command.CloseTicketCommand;
import com.flowdesk.application.ticket.command.CreateTicketCommand;
import com.flowdesk.application.ticket.command.ReassignTicketCommand;
import com.flowdesk.application.ticket.command.ResolveTicketCommand;
import com.flowdesk.application.ticket.command.StartTicketCommand;
import com.flowdesk.application.ticket.service.TicketApplicationService;
import com.flowdesk.application.ticket.view.TicketView;
import com.flowdesk.domain.ticket.TicketErrorCode;
import com.flowdesk.domain.ticket.TicketStatus;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 六条写入用例的成功路径、版本递增、时间调用次数与领域失败不写存储。
 */
class TicketApplicationServiceLifecycleTest {

    private final InMemoryTicketRepository repository = new InMemoryTicketRepository();

    private final RecordingTicketIdGenerator idGenerator = new RecordingTicketIdGenerator(TICKET_ID);

    private final RecordingTimeProvider timeProvider = new RecordingTimeProvider(BASE, Duration.ofMinutes(1));

    private final TicketApplicationService service =
            new TicketApplicationService(this.repository, this.idGenerator, this.timeProvider);

    private TicketView ticket;

    @BeforeEach
    void createTicket() {
        this.ticket = this.service.create(new CreateTicketCommand(TITLE, DESCRIPTION, CATEGORY, PRIORITY, ALICE));
    }

    @Test
    void runsTheWholeLifecycleAndBumpsVersionOnEveryStep() {
        assertThat(this.ticket.version()).isZero();
        assertThat(this.ticket.status()).isEqualTo(TicketStatus.NEW);

        TicketView assigned = this.service.assign(new AssignTicketCommand(TICKET_ID, BOB, 0L));
        assertThat(assigned.status()).isEqualTo(TicketStatus.ASSIGNED);
        assertThat(assigned.assigneeId()).contains(BOB);
        assertThat(assigned.version()).isEqualTo(1);
        assertThat(assigned.updatedAt()).isEqualTo(BASE.plusSeconds(60));

        TicketView reassigned = this.service.reassign(new ReassignTicketCommand(TICKET_ID, ALICE, 1L));
        assertThat(reassigned.status()).isEqualTo(TicketStatus.ASSIGNED);
        assertThat(reassigned.assigneeId()).contains(ALICE);
        assertThat(reassigned.version()).isEqualTo(2);

        TicketView started = this.service.start(new StartTicketCommand(TICKET_ID, 2L));
        assertThat(started.status()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(started.version()).isEqualTo(3);

        TicketView resolved = this.service.resolve(new ResolveTicketCommand(TICKET_ID, "  " + RESOLUTION + "  ", 3L));
        assertThat(resolved.status()).isEqualTo(TicketStatus.RESOLVED);
        assertThat(resolved.resolution()).contains(RESOLUTION);
        assertThat(resolved.resolvedAt()).contains(BASE.plusSeconds(240));
        assertThat(resolved.version()).isEqualTo(4);

        TicketView closed = this.service.close(new CloseTicketCommand(TICKET_ID, 4L));
        assertThat(closed.status()).isEqualTo(TicketStatus.CLOSED);
        assertThat(closed.closedAt()).contains(BASE.plusSeconds(300));
        assertThat(closed.version()).isEqualTo(5);

        assertThat(this.repository.versionOf(TICKET_ID)).isEqualTo(5);
    }

    @Test
    void allowsReassignWhileInProgress() {
        this.service.assign(new AssignTicketCommand(TICKET_ID, BOB, 0L));
        this.service.start(new StartTicketCommand(TICKET_ID, 1L));

        TicketView reassigned = this.service.reassign(new ReassignTicketCommand(TICKET_ID, ALICE, 2L));

        assertThat(reassigned.status()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(reassigned.assigneeId()).contains(ALICE);
        assertThat(reassigned.resolution()).isEmpty();
        assertThat(reassigned.version()).isEqualTo(3);
    }

    @Test
    void readsTheTimeExactlyOncePerSuccessfulCommand() {
        assertThat(this.timeProvider.calls()).isEqualTo(1);

        this.service.assign(new AssignTicketCommand(TICKET_ID, BOB, 0L));
        assertThat(this.timeProvider.calls()).isEqualTo(2);

        this.service.start(new StartTicketCommand(TICKET_ID, 1L));
        assertThat(this.timeProvider.calls()).isEqualTo(3);

        this.service.resolve(new ResolveTicketCommand(TICKET_ID, RESOLUTION, 2L));
        assertThat(this.timeProvider.calls()).isEqualTo(4);

        this.service.close(new CloseTicketCommand(TICKET_ID, 3L));
        assertThat(this.timeProvider.calls()).isEqualTo(5);
    }

    @Test
    void rejectsIllegalTransitionWithoutTouchingTheRepository() {
        int updatesBefore = this.repository.updateCalls();

        // NEW 状态下不允许提交结论
        assertDomainError(() -> this.service.resolve(new ResolveTicketCommand(TICKET_ID, RESOLUTION, 0L)),
                TicketErrorCode.ILLEGAL_STATUS_TRANSITION);

        assertThat(this.repository.updateCalls()).isEqualTo(updatesBefore);
        assertThat(this.repository.versionOf(TICKET_ID)).isZero();
    }

    @Test
    void rejectsReassignToTheSameAssigneeWithoutTouchingTheRepository() {
        this.service.assign(new AssignTicketCommand(TICKET_ID, BOB, 0L));
        int updatesBefore = this.repository.updateCalls();

        assertDomainError(() -> this.service.reassign(new ReassignTicketCommand(TICKET_ID, BOB, 1L)),
                TicketErrorCode.SAME_ASSIGNEE);

        assertThat(this.repository.updateCalls()).isEqualTo(updatesBefore);
        assertThat(this.repository.versionOf(TICKET_ID)).isEqualTo(1);
    }

    @Test
    void rejectsBlankResolutionWithoutTouchingTheRepository() {
        this.service.assign(new AssignTicketCommand(TICKET_ID, BOB, 0L));
        this.service.start(new StartTicketCommand(TICKET_ID, 1L));
        int updatesBefore = this.repository.updateCalls();

        assertDomainError(() -> this.service.resolve(new ResolveTicketCommand(TICKET_ID, "   ", 2L)),
                TicketErrorCode.INVALID_RESOLUTION);

        assertThat(this.repository.updateCalls()).isEqualTo(updatesBefore);
        assertThat(this.repository.versionOf(TICKET_ID)).isEqualTo(2);
    }

    @Test
    void rejectsNullAssigneeWithoutTouchingTheRepository() {
        int updatesBefore = this.repository.updateCalls();

        assertDomainError(() -> this.service.assign(new AssignTicketCommand(TICKET_ID, null, 0L)),
                TicketErrorCode.INVALID_USER_ID);

        assertThat(this.repository.updateCalls()).isEqualTo(updatesBefore);
    }

    @Test
    void doesNotEchoRejectedResolutionAmongExceptions() {
        this.service.assign(new AssignTicketCommand(TICKET_ID, BOB, 0L));
        this.service.start(new StartTicketCommand(TICKET_ID, 1L));
        String sentinel = "SENTINELRESOLUTION" + "r".repeat(2000);

        RuntimeException ex = ApplicationTestSupport.capture(
                () -> this.service.resolve(new ResolveTicketCommand(TICKET_ID, sentinel, 2L)));

        assertThat(ex.getMessage()).doesNotContain("SENTINELRESOLUTION");
    }
}
