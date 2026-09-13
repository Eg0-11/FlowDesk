package com.flowdesk.application.ticket;

import static com.flowdesk.application.ticket.ApplicationTestSupport.ALICE;
import static com.flowdesk.application.ticket.ApplicationTestSupport.BOB;
import static com.flowdesk.application.ticket.ApplicationTestSupport.CATEGORY;
import static com.flowdesk.application.ticket.ApplicationTestSupport.DESCRIPTION;
import static com.flowdesk.application.ticket.ApplicationTestSupport.PRIORITY;
import static com.flowdesk.application.ticket.ApplicationTestSupport.TICKET_ID;
import static com.flowdesk.application.ticket.ApplicationTestSupport.TITLE;
import static com.flowdesk.application.ticket.ApplicationTestSupport.assertApplicationError;
import static org.assertj.core.api.Assertions.assertThat;

import com.flowdesk.application.ticket.command.AssignTicketCommand;
import com.flowdesk.application.ticket.command.CreateTicketCommand;
import com.flowdesk.application.ticket.command.StartTicketCommand;
import com.flowdesk.application.ticket.service.TicketApplicationService;
import com.flowdesk.domain.ticket.TicketStatus;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 乐观并发契约测试：调用方版本过期、以及读取后写入前的 CAS 竞争。
 */
class TicketApplicationServiceConflictTest {

    private final InMemoryTicketRepository repository = new InMemoryTicketRepository();

    private final RecordingTicketIdGenerator idGenerator = new RecordingTicketIdGenerator(TICKET_ID);

    private final RecordingTimeProvider timeProvider = new RecordingTimeProvider(ApplicationTestSupport.BASE,
            Duration.ofMinutes(1));

    private final TicketApplicationService service =
            new TicketApplicationService(this.repository, this.idGenerator, this.timeProvider);

    @BeforeEach
    void createTicket() {
        this.service.create(new CreateTicketCommand(TITLE, DESCRIPTION, CATEGORY, PRIORITY, ALICE));
    }

    @Test
    void rejectsAStaleVersion() {
        this.service.assign(new AssignTicketCommand(TICKET_ID, BOB, 0L));

        assertApplicationError(() -> this.service.start(new StartTicketCommand(TICKET_ID, 0L)),
                TicketApplicationErrorCode.TICKET_VERSION_CONFLICT);
    }

    @Test
    void rejectsAFutureVersion() {
        assertApplicationError(() -> this.service.assign(new AssignTicketCommand(TICKET_ID, BOB, 5L)),
                TicketApplicationErrorCode.TICKET_VERSION_CONFLICT);
    }

    @Test
    void versionConflictHappensBeforeTheDomainTransitionAndTheClock() {
        int timeCallsBefore = this.timeProvider.calls();
        int updateCallsBefore = this.repository.updateCalls();

        assertApplicationError(() -> this.service.assign(new AssignTicketCommand(TICKET_ID, BOB, 3L)),
                TicketApplicationErrorCode.TICKET_VERSION_CONFLICT);

        assertThat(this.timeProvider.calls()).as("版本冲突不得读取时间").isEqualTo(timeCallsBefore);
        assertThat(this.repository.updateCalls()).as("版本冲突不得写入").isEqualTo(updateCallsBefore);
        assertThat(this.repository.findById(TICKET_ID).orElseThrow().ticket().status())
                .as("版本冲突不得触发领域转换")
                .isEqualTo(TicketStatus.NEW);
        assertThat(this.repository.versionOf(TICKET_ID)).isZero();
    }

    @Test
    void propagatesCasFailureCausedByAConcurrentWriter() {
        this.repository.armConcurrentWriteBeforeNextUpdate(TICKET_ID);

        assertApplicationError(() -> this.service.assign(new AssignTicketCommand(TICKET_ID, BOB, 0L)),
                TicketApplicationErrorCode.TICKET_VERSION_CONFLICT);

        assertThat(this.repository.versionOf(TICKET_ID))
                .as("并发写入生效后版本为 1，本次写入被 CAS 拒绝")
                .isEqualTo(1);
        assertThat(this.repository.findById(TICKET_ID).orElseThrow().ticket().status())
                .as("被拒绝的写入不得落库")
                .isEqualTo(TicketStatus.NEW);
    }

    @Test
    void allowsARetryAfterAConflict() {
        this.repository.armConcurrentWriteBeforeNextUpdate(TICKET_ID);
        assertApplicationError(() -> this.service.assign(new AssignTicketCommand(TICKET_ID, BOB, 0L)),
                TicketApplicationErrorCode.TICKET_VERSION_CONFLICT);

        // 重新读取当前版本后重试即可成功
        long currentVersion = this.repository.versionOf(TICKET_ID);
        var view = this.service.assign(new AssignTicketCommand(TICKET_ID, BOB, currentVersion));

        assertThat(view.version()).isEqualTo(currentVersion + 1);
        assertThat(view.assigneeId()).contains(BOB);
    }
}
