package com.flowdesk.application.ticket.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 排序白名单枚举的解析测试。
 *
 * <p>这些枚举是「客户端文本 → SQL 列名」之间唯一的闸门：解析失败即拒绝，
 * 因此它们必须严格、可枚举、且不允许任何额外取值。</p>
 */
class TicketSortWhitelistTest {

    @Test
    void parsesEveryAllowedSortField() {
        assertThat(TicketSortField.fromWire("createdAt")).contains(TicketSortField.CREATED_AT);
        assertThat(TicketSortField.fromWire("updatedAt")).contains(TicketSortField.UPDATED_AT);
        assertThat(TicketSortField.fromWire("priority")).contains(TicketSortField.PRIORITY);
        assertThat(TicketSortField.fromWire("status")).contains(TicketSortField.STATUS);
    }

    @Test
    void parsesEveryAllowedDirection() {
        assertThat(TicketSortDirection.fromWire("asc")).contains(TicketSortDirection.ASC);
        assertThat(TicketSortDirection.fromWire("desc")).contains(TicketSortDirection.DESC);
    }

    @Test
    void returnsEmptyForUnknownOrNullValues() {
        assertThat(TicketSortField.fromWire(null)).isEmpty();
        assertThat(TicketSortField.fromWire("")).isEmpty();
        assertThat(TicketSortField.fromWire("UpdatedAt")).isEmpty();
        assertThat(TicketSortField.fromWire("updated_at")).isEmpty();
        assertThat(TicketSortField.fromWire("id")).isEmpty();
        assertThat(TicketSortField.fromWire("updatedAt DESC")).isEmpty();
        assertThat(TicketSortDirection.fromWire(null)).isEmpty();
        assertThat(TicketSortDirection.fromWire("ASC")).isEmpty();
        assertThat(TicketSortDirection.fromWire("descending")).isEmpty();
    }

    @Test
    void whitelistContainsExactlyTheDocumentedValues() {
        // 白名单就是「所有合法取值」本身：新增常量会让本测试失败，从而提醒同步契约文档
        assertThat(Arrays.stream(TicketSortField.values()).map(TicketSortField::wireName))
                .containsExactly("createdAt", "updatedAt", "priority", "status");
        assertThat(Arrays.stream(TicketSortDirection.values()).map(TicketSortDirection::wireName))
                .containsExactly("asc", "desc");
    }

    @Test
    void exposesAllowedValuesForErrorMessages() {
        assertThat(TicketSortField.allowedWireNames()).isEqualTo("createdAt、updatedAt、priority、status");
        assertThat(TicketSortDirection.allowedWireNames()).isEqualTo("asc、desc");
    }

    @Test
    void everyConstantRoundTripsThroughItsWireName() {
        for (TicketSortField field : TicketSortField.values()) {
            assertThat(TicketSortField.fromWire(field.wireName())).contains(field);
        }
        for (TicketSortDirection direction : TicketSortDirection.values()) {
            assertThat(TicketSortDirection.fromWire(direction.wireName())).contains(direction);
        }
    }

    @Test
    void neverReturnsNull() {
        assertThat(Optional.ofNullable(TicketSortField.fromWire("nope"))).isPresent();
        assertThat(Optional.ofNullable(TicketSortDirection.fromWire("nope"))).isPresent();
    }
}
