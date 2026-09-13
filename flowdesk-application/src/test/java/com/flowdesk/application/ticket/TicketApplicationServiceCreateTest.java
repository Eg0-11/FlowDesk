package com.flowdesk.application.ticket;

import static com.flowdesk.application.ticket.ApplicationTestSupport.ALICE;
import static com.flowdesk.application.ticket.ApplicationTestSupport.BASE;
import static com.flowdesk.application.ticket.ApplicationTestSupport.CATEGORY;
import static com.flowdesk.application.ticket.ApplicationTestSupport.DESCRIPTION;
import static com.flowdesk.application.ticket.ApplicationTestSupport.PRIORITY;
import static com.flowdesk.application.ticket.ApplicationTestSupport.TICKET_ID;
import static com.flowdesk.application.ticket.ApplicationTestSupport.TITLE;
import static com.flowdesk.application.ticket.ApplicationTestSupport.assertApplicationError;
import static com.flowdesk.application.ticket.ApplicationTestSupport.assertDomainError;
import static org.assertj.core.api.Assertions.assertThat;

import com.flowdesk.application.ticket.command.CreateTicketCommand;
import com.flowdesk.application.ticket.service.TicketApplicationService;
import com.flowdesk.application.ticket.view.TicketView;
import com.flowdesk.domain.ticket.TicketErrorCode;
import com.flowdesk.domain.ticket.TicketStatus;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 创建用例测试。
 */
class TicketApplicationServiceCreateTest {

    private final InMemoryTicketRepository repository = new InMemoryTicketRepository();

    /**
     * 预置两个相同的标识：用于验证「同一标识重复插入被原子拒绝」。
     */
    private final RecordingTicketIdGenerator idGenerator =
            new RecordingTicketIdGenerator(TICKET_ID, TICKET_ID);

    private final RecordingTimeProvider timeProvider = new RecordingTimeProvider(BASE, Duration.ofMinutes(1));

    private final TicketApplicationService service =
            new TicketApplicationService(this.repository, this.idGenerator, this.timeProvider);

    @Test
    void createsATicketWithGeneratedIdAndTime() {
        TicketView view = this.service.create(validCommand());

        assertThat(view.id()).isEqualTo(TICKET_ID);
        assertThat(view.status()).isEqualTo(TicketStatus.NEW);
        assertThat(view.version()).isZero();
        assertThat(view.createdAt()).isEqualTo(BASE);
        assertThat(view.updatedAt()).isEqualTo(BASE);
        assertThat(view.title()).isEqualTo(TITLE);
        assertThat(view.description()).isEqualTo(DESCRIPTION);
        assertThat(view.category()).isEqualTo(CATEGORY);
        assertThat(view.priority()).isEqualTo(PRIORITY);
        assertThat(view.requesterId()).isEqualTo(ALICE);
        assertThat(view.assigneeId()).isEmpty();
        assertThat(view.resolution()).isEmpty();
        assertThat(view.resolvedAt()).isEmpty();
        assertThat(view.closedAt()).isEmpty();
    }

    @Test
    void persistsExactlyOneTicket() {
        this.service.create(validCommand());

        assertThat(this.repository.contains(TICKET_ID)).isTrue();
        assertThat(this.repository.versionOf(TICKET_ID)).isZero();
        assertThat(this.repository.insertCalls()).isEqualTo(1);
        assertThat(this.repository.updateCalls()).isZero();
    }

    @Test
    void consumesOneIdAndOneTimestamp() {
        this.service.create(validCommand());

        assertThat(this.idGenerator.calls()).isEqualTo(1);
        assertThat(this.timeProvider.calls()).isEqualTo(1);
    }

    @Test
    void rejectsDuplicateTicketId() {
        this.service.create(validCommand());

        assertApplicationError(() -> this.service.create(validCommand()),
                TicketApplicationErrorCode.TICKET_ALREADY_EXISTS);
    }

    @Test
    void surfacesDomainErrorsAndDoesNotInsert() {
        CreateTicketCommand blankTitle = new CreateTicketCommand("   ", DESCRIPTION, CATEGORY, PRIORITY, ALICE);

        assertDomainError(() -> this.service.create(blankTitle), TicketErrorCode.INVALID_TITLE);
        assertThat(this.repository.insertCalls()).isZero();
        assertThat(this.repository.contains(TICKET_ID)).isFalse();
    }

    @Test
    void doesNotEchoTheRejectedTitleInTheException() {
        String sentinel = "SENTINELTITLE" + "t".repeat(200);
        CreateTicketCommand command = new CreateTicketCommand(sentinel, DESCRIPTION, CATEGORY, PRIORITY, ALICE);

        RuntimeException ex = ApplicationTestSupport.capture(() -> this.service.create(command));

        assertThat(ex).isInstanceOf(com.flowdesk.domain.ticket.TicketDomainException.class);
        assertThat(ex.getMessage()).doesNotContain("SENTINELTITLE");
    }

    private static CreateTicketCommand validCommand() {
        return new CreateTicketCommand(TITLE, DESCRIPTION, CATEGORY, PRIORITY, ALICE);
    }
}
