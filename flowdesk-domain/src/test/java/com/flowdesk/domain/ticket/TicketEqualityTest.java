package com.flowdesk.domain.ticket;

import static com.flowdesk.domain.ticket.TicketTestSupport.ALICE;
import static com.flowdesk.domain.ticket.TicketTestSupport.BASE;
import static com.flowdesk.domain.ticket.TicketTestSupport.CATEGORY;
import static com.flowdesk.domain.ticket.TicketTestSupport.DESCRIPTION;
import static com.flowdesk.domain.ticket.TicketTestSupport.OTHER_TICKET_ID;
import static com.flowdesk.domain.ticket.TicketTestSupport.PRIORITY;
import static com.flowdesk.domain.ticket.TicketTestSupport.RESOLUTION;
import static com.flowdesk.domain.ticket.TicketTestSupport.TICKET_ID;
import static com.flowdesk.domain.ticket.TicketTestSupport.TITLE;
import static com.flowdesk.domain.ticket.TicketTestSupport.newTicket;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 实体语义测试：相等性只由工单标识决定，且 {@code toString} 不泄露业务内容。
 */
class TicketEqualityTest {

    @Test
    void ticketsWithTheSameIdAreEqualEvenWithDifferentState() {
        Ticket first = newTicket();
        Ticket second = newTicket();
        second.assign(ALICE, BASE.plusSeconds(60));

        assertThat(first).isEqualTo(second);
        assertThat(second).isEqualTo(first);
        assertThat(first).hasSameHashCodeAs(second);
    }

    @Test
    void ticketsWithDifferentIdsAreNotEqual() {
        Ticket first = newTicket();
        Ticket second = Ticket.create(OTHER_TICKET_ID, TITLE, DESCRIPTION, CATEGORY, PRIORITY, ALICE, BASE);

        assertThat(first).isNotEqualTo(second);
        assertThat(first.hashCode()).isNotEqualTo(second.hashCode());
    }

    @Test
    void equalTicketsCollapseInASet() {
        Set<Ticket> tickets = new HashSet<>();
        tickets.add(newTicket());
        tickets.add(newTicket());

        assertThat(tickets).hasSize(1);
    }

    @Test
    void isNotEqualToNullOrOtherTypes() {
        Ticket ticket = newTicket();

        assertThat(ticket).isNotEqualTo(null);
        assertThat(ticket).isNotEqualTo(TICKET_ID);
    }

    @Test
    void isEqualToItself() {
        Ticket ticket = newTicket();

        assertThat(ticket).isEqualTo(ticket);
    }

    @Test
    void toStringDoesNotExposeBusinessContent() {
        Ticket ticket = newTicket();
        ticket.assign(ALICE, BASE.plusSeconds(60));
        ticket.start(BASE.plusSeconds(120));
        ticket.resolve(RESOLUTION, BASE.plusSeconds(180));

        String rendered = ticket.toString();

        assertThat(rendered).contains(TICKET_ID.toString());
        assertThat(rendered).contains("RESOLVED");
        assertThat(rendered).doesNotContain(DESCRIPTION);
        assertThat(rendered).doesNotContain(RESOLUTION);
        assertThat(rendered).doesNotContain(TITLE);
    }
}
