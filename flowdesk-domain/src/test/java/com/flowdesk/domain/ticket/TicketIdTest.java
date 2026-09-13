package com.flowdesk.domain.ticket;

import static com.flowdesk.domain.ticket.TicketTestSupport.assertErrorCode;
import static com.flowdesk.domain.ticket.TicketTestSupport.captureDomainException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link TicketId} 值对象测试。
 */
class TicketIdTest {

    private static final UUID UUID_VALUE = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void createsFromUuid() {
        TicketId id = TicketId.of(UUID_VALUE);

        assertThat(id.value()).isEqualTo(UUID_VALUE);
    }

    @Test
    void rejectsNullUuid() {
        assertErrorCode(() -> TicketId.of(null), TicketErrorCode.INVALID_TICKET_ID);
    }

    @Test
    void parsesAValidUuidString() {
        TicketId id = TicketId.parse("11111111-2222-3333-4444-555555555555");

        assertThat(id.value()).isEqualTo(UUID_VALUE);
    }

    @Test
    void parsesUpperCaseUuidString() {
        TicketId id = TicketId.parse("11111111-2222-3333-4444-555555555555".toUpperCase());

        assertThat(id).isEqualTo(TicketId.of(UUID_VALUE));
    }

    @Test
    void rejectsNullString() {
        assertErrorCode(() -> TicketId.parse(null), TicketErrorCode.INVALID_TICKET_ID);
    }

    @Test
    void rejectsMalformedStrings() {
        assertThatThrownBy(() -> TicketId.parse("not-a-uuid"))
                .isInstanceOf(TicketDomainException.class);

        assertErrorCode(() -> TicketId.parse(""), TicketErrorCode.INVALID_TICKET_ID);
        assertErrorCode(() -> TicketId.parse("11111111-2222-3333-4444"), TicketErrorCode.INVALID_TICKET_ID);
        assertErrorCode(() -> TicketId.parse("11111111-2222-3333-4444-55555555555z"),
                TicketErrorCode.INVALID_TICKET_ID);
    }

    @Test
    void rejectsStringWithSurroundingWhitespace() {
        // 解析是严格模式：不接受首尾空白
        assertErrorCode(() -> TicketId.parse(" 11111111-2222-3333-4444-555555555555 "),
                TicketErrorCode.INVALID_TICKET_ID);
    }

    @Test
    void doesNotEchoTheInvalidInputInTheException() {
        String sentinel = "sentinel-ticket-id-abcdef";

        TicketDomainException ex = captureDomainException(() -> TicketId.parse(sentinel));

        assertThat(ex.getMessage()).doesNotContain(sentinel);
        assertThat(ex.getMessage()).doesNotContain("sentinel");
    }

    @Test
    void equalityIsBasedOnTheUuid() {
        assertThat(TicketId.of(UUID_VALUE)).isEqualTo(TicketId.parse(UUID_VALUE.toString()));
        assertThat(TicketId.of(UUID_VALUE)).hasSameHashCodeAs(TicketId.of(UUID_VALUE));
        assertThat(TicketId.of(UUID_VALUE)).isNotEqualTo(TicketId.of(UUID.randomUUID()));
    }

    @Test
    void toStringIsTheUuidText() {
        assertThat(TicketId.of(UUID_VALUE)).hasToString(UUID_VALUE.toString());
    }
}
