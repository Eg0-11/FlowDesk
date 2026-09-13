package com.flowdesk.application.ticket;

import static com.flowdesk.application.ticket.ApplicationTestSupport.ALICE;
import static com.flowdesk.application.ticket.ApplicationTestSupport.CATEGORY;
import static com.flowdesk.application.ticket.ApplicationTestSupport.DESCRIPTION;
import static com.flowdesk.application.ticket.ApplicationTestSupport.MISSING_TICKET_ID;
import static com.flowdesk.application.ticket.ApplicationTestSupport.PRIORITY;
import static com.flowdesk.application.ticket.ApplicationTestSupport.TITLE;
import static com.flowdesk.application.ticket.ApplicationTestSupport.assertApplicationError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.flowdesk.application.ticket.command.CreateTicketCommand;
import com.flowdesk.application.ticket.query.GetTicketQuery;
import com.flowdesk.application.ticket.service.TicketApplicationService;
import com.flowdesk.domain.ticket.TicketDomainException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 应用层错误契约测试。
 */
class TicketApplicationExceptionTest {

    private final InMemoryTicketRepository repository = new InMemoryTicketRepository();

    private final RecordingTicketIdGenerator idGenerator = new RecordingTicketIdGenerator();

    private final RecordingTimeProvider timeProvider = new RecordingTimeProvider(ApplicationTestSupport.BASE,
            Duration.ofMinutes(1));

    private final TicketApplicationService service =
            new TicketApplicationService(this.repository, this.idGenerator, this.timeProvider);

    @Test
    void carriesTheErrorCode() {
        TicketApplicationException ex = new TicketApplicationException(
                TicketApplicationErrorCode.TICKET_NOT_FOUND, "测试");

        assertThat(ex.errorCode()).isEqualTo(TicketApplicationErrorCode.TICKET_NOT_FOUND);
    }

    @Test
    void rejectsANullErrorCode() {
        assertThatNullPointerException().isThrownBy(() -> new TicketApplicationException(null, "测试"));
    }

    @Test
    void doesNotWrapDomainExceptions() {
        // 领域字段非法：必须以 TicketDomainException 原样传出，而不是被包装成应用层异常
        assertApplicationDoesNotWrapDomainException();
    }

    @Test
    void doesNotEchoTicketIdInNotFoundMessages() {
        TicketApplicationException ex = (TicketApplicationException) ApplicationTestSupport.capture(
                () -> this.service.get(new GetTicketQuery(MISSING_TICKET_ID)));

        assertThat(ex.getMessage()).doesNotContain(MISSING_TICKET_ID.toString());
    }

    @Test
    void serviceRejectsNullCollaboratorsAtConstruction() {
        assertThatNullPointerException().isThrownBy(
                () -> new TicketApplicationService(null, this.idGenerator, this.timeProvider));
        assertThatNullPointerException().isThrownBy(
                () -> new TicketApplicationService(this.repository, null, this.timeProvider));
        assertThatNullPointerException().isThrownBy(
                () -> new TicketApplicationService(this.repository, this.idGenerator, null));
    }

    private void assertApplicationDoesNotWrapDomainException() {
        CreateTicketCommand blankTitle = new CreateTicketCommand("   ", DESCRIPTION, CATEGORY, PRIORITY, ALICE);

        RuntimeException thrown = ApplicationTestSupport.capture(() -> this.service.create(blankTitle));

        assertThat(thrown).isInstanceOf(TicketDomainException.class);
        assertThat(thrown).isNotInstanceOf(TicketApplicationException.class);
    }

    @Test
    void applicationErrorCodesRemainStable() {
        assertThat(TicketApplicationErrorCode.values()).containsExactly(
                TicketApplicationErrorCode.INVALID_COMMAND,
                TicketApplicationErrorCode.TICKET_NOT_FOUND,
                TicketApplicationErrorCode.TICKET_ALREADY_EXISTS,
                TicketApplicationErrorCode.TICKET_VERSION_CONFLICT);
    }

    @Test
    void invalidCommandIsReportedBeforeAnythingElse() {
        assertApplicationError(() -> this.service.start(null), TicketApplicationErrorCode.INVALID_COMMAND);
    }
}
