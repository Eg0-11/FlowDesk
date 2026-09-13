package com.flowdesk.domain.ticket;

import static com.flowdesk.domain.ticket.TicketTestSupport.ALICE;
import static com.flowdesk.domain.ticket.TicketTestSupport.BASE;
import static com.flowdesk.domain.ticket.TicketTestSupport.CATEGORY;
import static com.flowdesk.domain.ticket.TicketTestSupport.DESCRIPTION;
import static com.flowdesk.domain.ticket.TicketTestSupport.PRIORITY;
import static com.flowdesk.domain.ticket.TicketTestSupport.TICKET_ID;
import static com.flowdesk.domain.ticket.TicketTestSupport.TITLE;
import static com.flowdesk.domain.ticket.TicketTestSupport.assertErrorCode;
import static com.flowdesk.domain.ticket.TicketTestSupport.captureDomainException;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 工单创建规则与字符串边界测试。
 */
class TicketCreationTest {

    @Test
    void createsANewTicketInInitialState() {
        Ticket ticket = Ticket.create(TICKET_ID, TITLE, DESCRIPTION, CATEGORY, PRIORITY, ALICE, BASE);

        assertThat(ticket.id()).isEqualTo(TICKET_ID);
        assertThat(ticket.title()).isEqualTo(TITLE);
        assertThat(ticket.description()).isEqualTo(DESCRIPTION);
        assertThat(ticket.category()).isEqualTo(CATEGORY);
        assertThat(ticket.priority()).isEqualTo(PRIORITY);
        assertThat(ticket.requesterId()).isEqualTo(ALICE);
        assertThat(ticket.status()).isEqualTo(TicketStatus.NEW);
        assertThat(ticket.assigneeId()).isEmpty();
        assertThat(ticket.resolution()).isEmpty();
        assertThat(ticket.resolvedAt()).isEmpty();
        assertThat(ticket.closedAt()).isEmpty();
    }

    @Test
    void setsUpdatedAtToCreatedAt() {
        Ticket ticket = Ticket.create(TICKET_ID, TITLE, DESCRIPTION, CATEGORY, PRIORITY, ALICE, BASE);

        assertThat(ticket.createdAt()).isEqualTo(BASE);
        assertThat(ticket.updatedAt()).isEqualTo(ticket.createdAt());
    }

    @Test
    void stripsTitleAndDescription() {
        Ticket ticket = Ticket.create(TICKET_ID, "  " + TITLE + "  ", "   " + DESCRIPTION + "   ",
                CATEGORY, PRIORITY, ALICE, BASE);

        assertThat(ticket.title()).isEqualTo(TITLE);
        assertThat(ticket.description()).isEqualTo(DESCRIPTION);
    }

    @Test
    void stripsUnicodeWhitespaceFromTitleAndDescription() {
        Ticket ticket = Ticket.create(TICKET_ID, "\u3000" + TITLE + "\u3000", "\u3000" + DESCRIPTION + "\u3000",
                CATEGORY, PRIORITY, ALICE, BASE);

        assertThat(ticket.title()).isEqualTo(TITLE);
        assertThat(ticket.description()).isEqualTo(DESCRIPTION);
    }

    @Test
    void acceptsSingleCharacterTitleAndDescription() {
        Ticket ticket = Ticket.create(TICKET_ID, "x", "y", CATEGORY, PRIORITY, ALICE, BASE);

        assertThat(ticket.title()).isEqualTo("x");
        assertThat(ticket.description()).isEqualTo("y");
    }

    @Test
    void acceptsMaxLengthTitle() {
        String title = "t".repeat(Ticket.MAX_TITLE_LENGTH);

        assertThat(Ticket.create(TICKET_ID, title, DESCRIPTION, CATEGORY, PRIORITY, ALICE, BASE).title())
                .hasSize(Ticket.MAX_TITLE_LENGTH);
    }

    @Test
    void rejectsTitleOneCharacterBeyondMaxLength() {
        assertErrorCode(() -> Ticket.create(TICKET_ID, "t".repeat(Ticket.MAX_TITLE_LENGTH + 1), DESCRIPTION,
                CATEGORY, PRIORITY, ALICE, BASE), TicketErrorCode.INVALID_TITLE);
    }

    @Test
    void acceptsMaxLengthDescription() {
        String description = "d".repeat(Ticket.MAX_DESCRIPTION_LENGTH);

        assertThat(Ticket.create(TICKET_ID, TITLE, description, CATEGORY, PRIORITY, ALICE, BASE).description())
                .hasSize(Ticket.MAX_DESCRIPTION_LENGTH);
    }

    @Test
    void rejectsDescriptionOneCharacterBeyondMaxLength() {
        assertErrorCode(() -> Ticket.create(TICKET_ID, TITLE, "d".repeat(Ticket.MAX_DESCRIPTION_LENGTH + 1),
                CATEGORY, PRIORITY, ALICE, BASE), TicketErrorCode.INVALID_DESCRIPTION);
    }

    @Test
    void rejectsBlankTitleAndDescription() {
        assertErrorCode(() -> Ticket.create(TICKET_ID, "   ", DESCRIPTION, CATEGORY, PRIORITY, ALICE, BASE),
                TicketErrorCode.INVALID_TITLE);
        assertErrorCode(() -> Ticket.create(TICKET_ID, "\u3000", DESCRIPTION, CATEGORY, PRIORITY, ALICE, BASE),
                TicketErrorCode.INVALID_TITLE);
        assertErrorCode(() -> Ticket.create(TICKET_ID, TITLE, "   ", CATEGORY, PRIORITY, ALICE, BASE),
                TicketErrorCode.INVALID_DESCRIPTION);
    }

    @Test
    void rejectsNullTitleAndDescription() {
        assertErrorCode(() -> Ticket.create(TICKET_ID, null, DESCRIPTION, CATEGORY, PRIORITY, ALICE, BASE),
                TicketErrorCode.INVALID_TITLE);
        assertErrorCode(() -> Ticket.create(TICKET_ID, TITLE, null, CATEGORY, PRIORITY, ALICE, BASE),
                TicketErrorCode.INVALID_DESCRIPTION);
    }

    @Test
    void rejectsNullMandatoryReferences() {
        assertErrorCode(() -> Ticket.create(null, TITLE, DESCRIPTION, CATEGORY, PRIORITY, ALICE, BASE),
                TicketErrorCode.INVALID_TICKET_ID);
        assertErrorCode(() -> Ticket.create(TICKET_ID, TITLE, DESCRIPTION, null, PRIORITY, ALICE, BASE),
                TicketErrorCode.INVALID_CATEGORY);
        assertErrorCode(() -> Ticket.create(TICKET_ID, TITLE, DESCRIPTION, CATEGORY, null, ALICE, BASE),
                TicketErrorCode.INVALID_PRIORITY);
        assertErrorCode(() -> Ticket.create(TICKET_ID, TITLE, DESCRIPTION, CATEGORY, PRIORITY, null, BASE),
                TicketErrorCode.INVALID_USER_ID);
    }

    @Test
    void rejectsNullCreatedAt() {
        assertErrorCode(() -> Ticket.create(TICKET_ID, TITLE, DESCRIPTION, CATEGORY, PRIORITY, ALICE, null),
                TicketErrorCode.INVALID_TIMESTAMP);
    }

    @Test
    void doesNotEchoInvalidTitleOrDescription() {
        String titleSentinel = "SENTINELTITLE" + "t".repeat(Ticket.MAX_TITLE_LENGTH);
        String descriptionSentinel = "SENTINELDESC" + "d".repeat(Ticket.MAX_DESCRIPTION_LENGTH);

        assertThat(captureDomainException(() -> Ticket.create(TICKET_ID, titleSentinel, DESCRIPTION, CATEGORY,
                PRIORITY, ALICE, BASE)).getMessage()).doesNotContain("SENTINELTITLE");
        assertThat(captureDomainException(() -> Ticket.create(TICKET_ID, TITLE, descriptionSentinel, CATEGORY,
                PRIORITY, ALICE, BASE)).getMessage()).doesNotContain("SENTINELDESC");
    }
}
