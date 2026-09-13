package com.flowdesk.application.ticket;

import static com.flowdesk.application.ticket.ApplicationTestSupport.ALICE;
import static com.flowdesk.application.ticket.ApplicationTestSupport.CATEGORY;
import static com.flowdesk.application.ticket.ApplicationTestSupport.PRIORITY;
import static com.flowdesk.application.ticket.ApplicationTestSupport.TICKET_ID;
import static com.flowdesk.application.ticket.ApplicationTestSupport.TITLE;
import static com.flowdesk.application.ticket.ApplicationTestSupport.assertApplicationError;
import static org.assertj.core.api.Assertions.assertThat;

import com.flowdesk.application.ticket.command.AssignTicketCommand;
import com.flowdesk.application.ticket.command.CreateTicketCommand;
import com.flowdesk.application.ticket.port.out.TicketSearchCriteria;
import com.flowdesk.application.ticket.query.SearchTicketsQuery;
import com.flowdesk.application.ticket.query.TicketSortDirection;
import com.flowdesk.application.ticket.query.TicketSortField;
import com.flowdesk.application.ticket.service.TicketApplicationService;
import com.flowdesk.application.ticket.view.TicketPageView;
import com.flowdesk.domain.ticket.TicketPriority;
import com.flowdesk.domain.ticket.TicketStatus;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 列表用例测试：条件传递、视图映射，以及「列表查询完全无副作用」。
 */
class TicketApplicationServiceSearchTest {

    private final InMemoryTicketRepository repository = new InMemoryTicketRepository();

    private final RecordingTicketIdGenerator idGenerator = new RecordingTicketIdGenerator(TICKET_ID);

    private final RecordingTimeProvider timeProvider = new RecordingTimeProvider(ApplicationTestSupport.BASE,
            Duration.ofMinutes(1));

    private final TicketApplicationService service =
            new TicketApplicationService(this.repository, this.idGenerator, this.timeProvider);

    @BeforeEach
    void createTicket() {
        this.service.create(new CreateTicketCommand(TITLE, ApplicationTestSupport.DESCRIPTION, CATEGORY,
                PRIORITY, ALICE));
    }

    // ---------- 条件传递 ----------

    @Test
    void passesNormalizedDefaultsToTheRepository() {
        TicketPageView view = this.service.search(SearchTicketsQuery.defaults());

        assertThat(this.repository.searchCalls()).isEqualTo(1);
        TicketSearchCriteria criteria = this.repository.lastCriteria();
        assertThat(criteria.page()).isZero();
        assertThat(criteria.size()).isEqualTo(20);
        assertThat(criteria.sortField()).isEqualTo(TicketSortField.UPDATED_AT);
        assertThat(criteria.direction()).isEqualTo(TicketSortDirection.DESC);
        assertThat(view.totalElements()).isEqualTo(1);
    }

    @Test
    void passesEveryFilterDownToTheRepository() {
        this.service.search(new SearchTicketsQuery(2, 5, TicketStatus.NEW.name(),
                CATEGORY.name(), TicketPriority.P1.name(), "  alice  ", " bob ", "  登录  ",
                "priority", "asc"));

        TicketSearchCriteria criteria = this.repository.lastCriteria();
        assertThat(criteria.page()).isEqualTo(2);
        assertThat(criteria.size()).isEqualTo(5);
        assertThat(criteria.status()).isEqualTo(TicketStatus.NEW);
        assertThat(criteria.category()).isEqualTo(CATEGORY);
        assertThat(criteria.priority()).isEqualTo(TicketPriority.P1);
        assertThat(criteria.requesterId()).as("必须 strip 后再传给存储").isEqualTo("alice");
        assertThat(criteria.assigneeId()).isEqualTo("bob");
        assertThat(criteria.keyword()).isEqualTo("登录");
        assertThat(criteria.sortField()).isEqualTo(TicketSortField.PRIORITY);
        assertThat(criteria.direction()).isEqualTo(TicketSortDirection.ASC);
    }

    @Test
    void offsetUsesLongArithmetic() {
        this.service.search(SearchTicketsQuery.ofPage(Integer.MAX_VALUE, 100));

        // int 运算会溢出成负数，long 不会
        assertThat(this.repository.lastCriteria().offset()).isEqualTo(214748364700L);
    }

    // ---------- 视图映射 ----------

    @Test
    void mapsItemsToViewsWithTheCurrentVersion() {
        this.service.assign(new AssignTicketCommand(TICKET_ID, ApplicationTestSupport.BOB, 0L));

        TicketPageView view = this.service.search(SearchTicketsQuery.defaults());

        assertThat(view.items()).hasSize(1);
        assertThat(view.items().get(0).id()).isEqualTo(TICKET_ID);
        assertThat(view.items().get(0).version()).as("必须带真实版本").isEqualTo(1L);
        assertThat(view.items().get(0).status()).isEqualTo(TicketStatus.ASSIGNED);
        assertThat(view.items().get(0).assigneeId()).contains(ApplicationTestSupport.BOB);
    }

    @Test
    void returnsEmptyPageForAnEmptyRepository() {
        InMemoryTicketRepository empty = new InMemoryTicketRepository();
        TicketApplicationService emptyService = new TicketApplicationService(empty, this.idGenerator,
                this.timeProvider);

        TicketPageView view = emptyService.search(SearchTicketsQuery.defaults());

        assertThat(view.items()).isNotNull().isEmpty();
        assertThat(view.totalElements()).isZero();
        assertThat(view.totalPages()).isZero();
        assertThat(view.hasNext()).isFalse();
        assertThat(view.hasPrevious()).isFalse();
    }

    // ---------- 校验与副作用 ----------

    @Test
    void rejectsInvalidQueriesBeforeTouchingTheRepository() {
        assertApplicationError(() -> this.service.search(null),
                TicketApplicationErrorCode.INVALID_COMMAND);
        assertApplicationError(() -> this.service.search(SearchTicketsQuery.ofPage(-1, 20)),
                TicketApplicationErrorCode.INVALID_COMMAND);
        assertApplicationError(() -> this.service.search(SearchTicketsQuery.ofPage(0, 0)),
                TicketApplicationErrorCode.INVALID_COMMAND);
        assertApplicationError(() -> this.service.search(new SearchTicketsQuery(null, null, "NOPE", null, null,
                null, null, null, null, null)), TicketApplicationErrorCode.INVALID_COMMAND);

        assertThat(this.repository.searchCalls()).as("校验失败不得触碰存储").isZero();
    }

    @Test
    void searchGeneratesNoIdReadsNoTimeAndWritesNothing() {
        int idCallsBefore = this.idGenerator.calls();
        int timeCallsBefore = this.timeProvider.calls();
        int findCallsBefore = this.repository.findCalls();
        int insertCallsBefore = this.repository.insertCalls();
        int updateCallsBefore = this.repository.updateCalls();
        long versionBefore = this.repository.versionOf(TICKET_ID);

        this.service.search(SearchTicketsQuery.defaults());
        this.service.search(SearchTicketsQuery.ofPage(1, 50));

        assertThat(this.idGenerator.calls()).as("查询不得生成标识").isEqualTo(idCallsBefore);
        assertThat(this.timeProvider.calls()).as("查询不得读取时间").isEqualTo(timeCallsBefore);
        assertThat(this.repository.findCalls()).as("列表查询不得走单条读取").isEqualTo(findCallsBefore);
        assertThat(this.repository.insertCalls()).isEqualTo(insertCallsBefore);
        assertThat(this.repository.updateCalls()).as("查询不得写入").isEqualTo(updateCallsBefore);
        assertThat(this.repository.versionOf(TICKET_ID)).as("查询不得推进版本").isEqualTo(versionBefore);
    }

    @Test
    void failedSearchAlsoHasNoSideEffects() {
        int searchCallsBefore = this.repository.searchCalls();
        int updateCallsBefore = this.repository.updateCalls();

        assertApplicationError(() -> this.service.search(SearchTicketsQuery.ofPage(0, 101)),
                TicketApplicationErrorCode.INVALID_COMMAND);

        assertThat(this.repository.searchCalls()).isEqualTo(searchCallsBefore);
        assertThat(this.repository.updateCalls()).isEqualTo(updateCallsBefore);
    }
}
