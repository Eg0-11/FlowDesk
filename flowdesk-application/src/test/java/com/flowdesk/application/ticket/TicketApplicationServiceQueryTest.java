package com.flowdesk.application.ticket;

import static com.flowdesk.application.ticket.ApplicationTestSupport.ALICE;
import static com.flowdesk.application.ticket.ApplicationTestSupport.CATEGORY;
import static com.flowdesk.application.ticket.ApplicationTestSupport.DESCRIPTION;
import static com.flowdesk.application.ticket.ApplicationTestSupport.MISSING_TICKET_ID;
import static com.flowdesk.application.ticket.ApplicationTestSupport.PRIORITY;
import static com.flowdesk.application.ticket.ApplicationTestSupport.TICKET_ID;
import static com.flowdesk.application.ticket.ApplicationTestSupport.TITLE;
import static com.flowdesk.application.ticket.ApplicationTestSupport.assertApplicationError;
import static org.assertj.core.api.Assertions.assertThat;

import com.flowdesk.application.ticket.command.AssignTicketCommand;
import com.flowdesk.application.ticket.command.CreateTicketCommand;
import com.flowdesk.application.ticket.query.GetTicketQuery;
import com.flowdesk.application.ticket.service.TicketApplicationService;
import com.flowdesk.application.ticket.view.TicketView;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 查询用例测试：成功、不存在，以及「查询完全无副作用」。
 */
class TicketApplicationServiceQueryTest {

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
    void returnsTheCreatedTicket() {
        TicketView view = this.service.get(new GetTicketQuery(TICKET_ID));

        assertThat(view.id()).isEqualTo(TICKET_ID);
        assertThat(view.status()).isEqualTo(com.flowdesk.domain.ticket.TicketStatus.NEW);
        assertThat(view.version()).isZero();
        assertThat(view.assigneeId()).isEmpty();
    }

    @Test
    void returnsTheCurrentVersionAfterAnUpdate() {
        this.service.assign(new AssignTicketCommand(TICKET_ID, ALICE, 0L));

        TicketView view = this.service.get(new GetTicketQuery(TICKET_ID));

        assertThat(view.version()).isEqualTo(1);
        assertThat(view.assigneeId()).contains(ALICE);
    }

    @Test
    void reportsMissingTicket() {
        assertApplicationError(() -> this.service.get(new GetTicketQuery(MISSING_TICKET_ID)),
                TicketApplicationErrorCode.TICKET_NOT_FOUND);
    }

    @Test
    void rejectsNullQueryAndNullTicketId() {
        assertApplicationError(() -> this.service.get(null), TicketApplicationErrorCode.INVALID_COMMAND);
        assertApplicationError(() -> this.service.get(new GetTicketQuery(null)),
                TicketApplicationErrorCode.INVALID_COMMAND);
    }

    @Test
    void queryHasNoSideEffects() {
        int timeCallsBefore = this.timeProvider.calls();
        int findCallsBefore = this.repository.findCalls();
        int insertCallsBefore = this.repository.insertCalls();
        int updateCallsBefore = this.repository.updateCalls();
        long versionBefore = this.repository.versionOf(TICKET_ID);

        this.service.get(new GetTicketQuery(TICKET_ID));
        this.service.get(new GetTicketQuery(TICKET_ID));

        assertThat(this.timeProvider.calls()).as("查询不得读取时间").isEqualTo(timeCallsBefore);
        assertThat(this.repository.findCalls()).isEqualTo(findCallsBefore + 2);
        assertThat(this.repository.insertCalls()).isEqualTo(insertCallsBefore);
        assertThat(this.repository.updateCalls()).as("查询不得写入").isEqualTo(updateCallsBefore);
        assertThat(this.repository.versionOf(TICKET_ID)).as("查询不得推进版本").isEqualTo(versionBefore);
    }

    @Test
    void failedQueryAlsoHasNoSideEffects() {
        int updateCallsBefore = this.repository.updateCalls();
        long versionBefore = this.repository.versionOf(TICKET_ID);

        assertApplicationError(() -> this.service.get(new GetTicketQuery(MISSING_TICKET_ID)),
                TicketApplicationErrorCode.TICKET_NOT_FOUND);

        assertThat(this.repository.updateCalls()).isEqualTo(updateCallsBefore);
        assertThat(this.repository.versionOf(TICKET_ID)).isEqualTo(versionBefore);
    }
}
