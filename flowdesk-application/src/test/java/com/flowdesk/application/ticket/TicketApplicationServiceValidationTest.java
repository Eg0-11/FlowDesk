package com.flowdesk.application.ticket;

import static com.flowdesk.application.ticket.ApplicationTestSupport.ALICE;
import static com.flowdesk.application.ticket.ApplicationTestSupport.BOB;
import static com.flowdesk.application.ticket.ApplicationTestSupport.CATEGORY;
import static com.flowdesk.application.ticket.ApplicationTestSupport.DESCRIPTION;
import static com.flowdesk.application.ticket.ApplicationTestSupport.MISSING_TICKET_ID;
import static com.flowdesk.application.ticket.ApplicationTestSupport.PRIORITY;
import static com.flowdesk.application.ticket.ApplicationTestSupport.RESOLUTION;
import static com.flowdesk.application.ticket.ApplicationTestSupport.TICKET_ID;
import static com.flowdesk.application.ticket.ApplicationTestSupport.TITLE;
import static com.flowdesk.application.ticket.ApplicationTestSupport.assertApplicationError;
import static org.assertj.core.api.Assertions.assertThat;

import com.flowdesk.application.ticket.command.AssignTicketCommand;
import com.flowdesk.application.ticket.command.CloseTicketCommand;
import com.flowdesk.application.ticket.command.CreateTicketCommand;
import com.flowdesk.application.ticket.command.ReassignTicketCommand;
import com.flowdesk.application.ticket.command.ResolveTicketCommand;
import com.flowdesk.application.ticket.command.StartTicketCommand;
import com.flowdesk.application.ticket.query.GetTicketQuery;
import com.flowdesk.application.ticket.service.TicketApplicationService;
import java.time.Duration;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 命令校验与工单不存在场景测试。
 */
class TicketApplicationServiceValidationTest {

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
    void rejectsNullCommands() {
        assertInvalidCommand(() -> this.service.create(null));
        assertInvalidCommand(() -> this.service.assign(null));
        assertInvalidCommand(() -> this.service.reassign(null));
        assertInvalidCommand(() -> this.service.start(null));
        assertInvalidCommand(() -> this.service.resolve(null));
        assertInvalidCommand(() -> this.service.close(null));
        assertInvalidCommand(() -> this.service.get(null));
    }

    @Test
    void rejectsNullTicketId() {
        assertInvalidCommand(() -> this.service.assign(new AssignTicketCommand(null, BOB, 0L)));
        assertInvalidCommand(() -> this.service.reassign(new ReassignTicketCommand(null, BOB, 0L)));
        assertInvalidCommand(() -> this.service.start(new StartTicketCommand(null, 0L)));
        assertInvalidCommand(() -> this.service.resolve(new ResolveTicketCommand(null, RESOLUTION, 0L)));
        assertInvalidCommand(() -> this.service.close(new CloseTicketCommand(null, 0L)));
        assertInvalidCommand(() -> this.service.get(new GetTicketQuery(null)));
    }

    @Test
    void rejectsNegativeExpectedVersion() {
        assertInvalidCommand(() -> this.service.assign(new AssignTicketCommand(TICKET_ID, BOB, -1L)));
        assertInvalidCommand(() -> this.service.reassign(new ReassignTicketCommand(TICKET_ID, BOB, -1L)));
        assertInvalidCommand(() -> this.service.start(new StartTicketCommand(TICKET_ID, -1L)));
        assertInvalidCommand(() -> this.service.resolve(new ResolveTicketCommand(TICKET_ID, RESOLUTION, -1L)));
        assertInvalidCommand(() -> this.service.close(new CloseTicketCommand(TICKET_ID, -1L)));
    }

    @Test
    void validatesBeforeTouchingTheRepositoryOrTheClock() {
        int findCallsBefore = this.repository.findCalls();
        int timeCallsBefore = this.timeProvider.calls();

        assertInvalidCommand(() -> this.service.assign(new AssignTicketCommand(null, BOB, -1L)));

        assertThat(this.repository.findCalls())
                .as("命令校验必须发生在读取存储之前")
                .isEqualTo(findCallsBefore);
        assertThat(this.timeProvider.calls()).isEqualTo(timeCallsBefore);
    }

    @Test
    void reportsMissingTicketForEveryMutation() {
        assertApplicationError(() -> this.service.assign(new AssignTicketCommand(MISSING_TICKET_ID, BOB, 0L)),
                TicketApplicationErrorCode.TICKET_NOT_FOUND);
        assertApplicationError(() -> this.service.reassign(new ReassignTicketCommand(MISSING_TICKET_ID, BOB, 0L)),
                TicketApplicationErrorCode.TICKET_NOT_FOUND);
        assertApplicationError(() -> this.service.start(new StartTicketCommand(MISSING_TICKET_ID, 0L)),
                TicketApplicationErrorCode.TICKET_NOT_FOUND);
        assertApplicationError(
                () -> this.service.resolve(new ResolveTicketCommand(MISSING_TICKET_ID, RESOLUTION, 0L)),
                TicketApplicationErrorCode.TICKET_NOT_FOUND);
        assertApplicationError(() -> this.service.close(new CloseTicketCommand(MISSING_TICKET_ID, 0L)),
                TicketApplicationErrorCode.TICKET_NOT_FOUND);
    }

    @Test
    void missingTicketDoesNotWriteOrConsumeTime() {
        int timeCallsBefore = this.timeProvider.calls();
        int updateCallsBefore = this.repository.updateCalls();

        assertApplicationError(() -> this.service.start(new StartTicketCommand(MISSING_TICKET_ID, 0L)),
                TicketApplicationErrorCode.TICKET_NOT_FOUND);

        assertThat(this.timeProvider.calls()).isEqualTo(timeCallsBefore);
        assertThat(this.repository.updateCalls()).isEqualTo(updateCallsBefore);
    }

    private static void assertInvalidCommand(ThrowingCallable callable) {
        assertApplicationError(callable, TicketApplicationErrorCode.INVALID_COMMAND);
    }
}
