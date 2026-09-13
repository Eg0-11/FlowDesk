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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.flowdesk.application.ticket.view.TicketView;
import com.flowdesk.domain.ticket.Ticket;
import com.flowdesk.domain.ticket.TicketStatus;
import java.lang.reflect.Method;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 视图映射与不可变性测试。
 */
class TicketViewTest {

    @Test
    void mapsEveryFieldOfANewTicket() {
        Ticket ticket = Ticket.create(TICKET_ID, TITLE, DESCRIPTION, CATEGORY, PRIORITY, ALICE, BASE);

        TicketView view = TicketView.from(ticket, 7L);

        assertThat(view.id()).isEqualTo(TICKET_ID);
        assertThat(view.title()).isEqualTo(TITLE);
        assertThat(view.description()).isEqualTo(DESCRIPTION);
        assertThat(view.category()).isEqualTo(CATEGORY);
        assertThat(view.priority()).isEqualTo(PRIORITY);
        assertThat(view.requesterId()).isEqualTo(ALICE);
        assertThat(view.status()).isEqualTo(TicketStatus.NEW);
        assertThat(view.createdAt()).isEqualTo(BASE);
        assertThat(view.updatedAt()).isEqualTo(BASE);
        assertThat(view.version()).isEqualTo(7L);
        assertThat(view.assigneeId()).isEmpty();
        assertThat(view.resolution()).isEmpty();
        assertThat(view.resolvedAt()).isEmpty();
        assertThat(view.closedAt()).isEmpty();
    }

    @Test
    void mapsEveryFieldOfAClosedTicket() {
        Ticket ticket = Ticket.restore(TICKET_ID, TITLE, DESCRIPTION, CATEGORY, PRIORITY, ALICE, BOB,
                TicketStatus.CLOSED, RESOLUTION, BASE, BASE.plusSeconds(20), BASE.plusSeconds(10),
                BASE.plusSeconds(20));

        TicketView view = TicketView.from(ticket, 5L);

        assertThat(view.status()).isEqualTo(TicketStatus.CLOSED);
        assertThat(view.assigneeId()).contains(BOB);
        assertThat(view.resolution()).contains(RESOLUTION);
        assertThat(view.resolvedAt()).contains(BASE.plusSeconds(10));
        assertThat(view.closedAt()).contains(BASE.plusSeconds(20));
        assertThat(view.updatedAt()).isEqualTo(BASE.plusSeconds(20));
        assertThat(view.version()).isEqualTo(5L);
    }

    @Test
    void rejectsNullOptionalFields() {
        assertThatNullPointerException().isThrownBy(() -> new TicketView(TICKET_ID, TITLE, DESCRIPTION, CATEGORY,
                PRIORITY, ALICE, null, TicketStatus.NEW, Optional.empty(), BASE, BASE, Optional.empty(),
                Optional.empty(), 0L));
        assertThatNullPointerException().isThrownBy(() -> new TicketView(TICKET_ID, TITLE, DESCRIPTION, CATEGORY,
                PRIORITY, ALICE, Optional.empty(), TicketStatus.NEW, null, BASE, BASE, Optional.empty(),
                Optional.empty(), 0L));
        assertThatNullPointerException().isThrownBy(() -> new TicketView(TICKET_ID, TITLE, DESCRIPTION, CATEGORY,
                PRIORITY, ALICE, Optional.empty(), TicketStatus.NEW, Optional.empty(), BASE, BASE, null,
                Optional.empty(), 0L));
        assertThatNullPointerException().isThrownBy(() -> new TicketView(TICKET_ID, TITLE, DESCRIPTION, CATEGORY,
                PRIORITY, ALICE, Optional.empty(), TicketStatus.NEW, Optional.empty(), BASE, BASE, Optional.empty(),
                null, 0L));
    }

    @Test
    void rejectsNullCoreFields() {
        assertThatNullPointerException().isThrownBy(() -> new TicketView(null, TITLE, DESCRIPTION, CATEGORY,
                PRIORITY, ALICE, Optional.empty(), TicketStatus.NEW, Optional.empty(), BASE, BASE,
                Optional.empty(), Optional.empty(), 0L));
        assertThatNullPointerException().isThrownBy(() -> new TicketView(TICKET_ID, TITLE, DESCRIPTION, CATEGORY,
                PRIORITY, ALICE, Optional.empty(), null, Optional.empty(), BASE, BASE, Optional.empty(),
                Optional.empty(), 0L));
    }

    @Test
    void neverExposesTheMutableAggregate() {
        assertThat(TicketView.class.getMethods())
                .as("视图不得向外提供 Ticket 聚合")
                .noneMatch(method -> Ticket.class.isAssignableFrom(method.getReturnType()));

        assertThat(TicketView.class.getMethods())
                .as("不可变视图不得有 setter")
                .noneMatch(method -> method.getName().startsWith("set"));
    }

    @Test
    void isAnImmutableRecord() {
        assertThat(TicketView.class.isRecord()).isTrue();

        for (Method method : TicketView.class.getDeclaredMethods()) {
            assertThat(method.getReturnType()).isNotEqualTo(void.class);
        }
    }
}
