package com.flowdesk.domain.ticket;

import static com.flowdesk.domain.ticket.TicketTestSupport.assertErrorCode;
import static com.flowdesk.domain.ticket.TicketTestSupport.captureDomainException;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link UserId} 值对象测试。
 */
class UserIdTest {

    @Test
    void stripsSurroundingWhitespace() {
        assertThat(UserId.of("  alice  ").value()).isEqualTo("alice");
    }

    @Test
    void stripsUnicodeWhitespace() {
        assertThat(UserId.of("\u3000alice\u3000").value()).isEqualTo("alice");
    }

    @Test
    void rejectsNull() {
        assertErrorCode(() -> UserId.of(null), TicketErrorCode.INVALID_USER_ID);
    }

    @Test
    void rejectsEmptyString() {
        assertErrorCode(() -> UserId.of(""), TicketErrorCode.INVALID_USER_ID);
    }

    @Test
    void rejectsBlankStrings() {
        assertErrorCode(() -> UserId.of("   "), TicketErrorCode.INVALID_USER_ID);
        assertErrorCode(() -> UserId.of("\u3000\u3000"), TicketErrorCode.INVALID_USER_ID);
    }

    @Test
    void acceptsExactlyMaxLength() {
        String value = "u".repeat(UserId.MAX_LENGTH);

        assertThat(UserId.of(value).value()).hasSize(UserId.MAX_LENGTH);
    }

    @Test
    void acceptsMaxLengthWithSurroundingWhitespace() {
        String value = "  " + "u".repeat(UserId.MAX_LENGTH) + "  ";

        assertThat(UserId.of(value).value()).hasSize(UserId.MAX_LENGTH);
    }

    @Test
    void rejectsOneCharacterBeyondMaxLength() {
        assertErrorCode(() -> UserId.of("u".repeat(UserId.MAX_LENGTH + 1)), TicketErrorCode.INVALID_USER_ID);
    }

    @Test
    void rejectsTooLongValueEvenWhenWhitespaceWouldBeStripped() {
        assertErrorCode(() -> UserId.of("  " + "u".repeat(UserId.MAX_LENGTH + 1) + "  "),
                TicketErrorCode.INVALID_USER_ID);
    }

    @Test
    void doesNotEchoTheInvalidInputInTheException() {
        String sentinel = "sentinel" + "x".repeat(UserId.MAX_LENGTH);

        TicketDomainException ex = captureDomainException(() -> UserId.of(sentinel));

        assertThat(ex.getMessage()).doesNotContain("sentinel");
    }

    @Test
    void equalityIsBasedOnTheNormalizedValue() {
        assertThat(UserId.of(" alice ")).isEqualTo(UserId.of("alice"));
        assertThat(UserId.of("alice")).hasSameHashCodeAs(UserId.of(" alice "));
        assertThat(UserId.of("alice")).isNotEqualTo(UserId.of("bob"));
    }

    @Test
    void toStringIsTheNormalizedValue() {
        assertThat(UserId.of("  alice ")).hasToString("alice");
    }
}
