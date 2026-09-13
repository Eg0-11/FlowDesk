package com.flowdesk.application.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowdesk.application.ticket.port.out.TicketSearchCriteria;
import com.flowdesk.application.ticket.port.out.TicketSearchResult;
import com.flowdesk.application.ticket.port.out.VersionedTicket;
import com.flowdesk.application.ticket.query.TicketSortDirection;
import com.flowdesk.application.ticket.query.TicketSortField;
import com.flowdesk.application.ticket.view.TicketPageView;
import com.flowdesk.domain.ticket.Ticket;
import com.flowdesk.domain.ticket.TicketCategory;
import com.flowdesk.domain.ticket.TicketId;
import com.flowdesk.domain.ticket.TicketPriority;
import com.flowdesk.domain.ticket.UserId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 分页结果与分页元数据的推导测试。
 *
 * <p>重点：{@code totalPages} 的取整、{@code hasNext}/{@code hasPrevious} 的边界，
 * 以及 {@code items} 的不可变性（不可变副本，而不是调用方列表的别名）。</p>
 */
class TicketPageViewTest {

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void computesMetadataForTheFirstPage() {
        TicketPageView view = pageOf(0, 20, 42, 20);

        assertThat(view.page()).isZero();
        assertThat(view.size()).isEqualTo(20);
        assertThat(view.totalElements()).isEqualTo(42);
        assertThat(view.totalPages()).isEqualTo(3);
        assertThat(view.hasNext()).isTrue();
        assertThat(view.hasPrevious()).isFalse();
    }

    @Test
    void computesMetadataForAMiddlePage() {
        TicketPageView view = pageOf(1, 20, 42, 20);

        assertThat(view.totalPages()).isEqualTo(3);
        assertThat(view.hasNext()).isTrue();
        assertThat(view.hasPrevious()).isTrue();
    }

    @Test
    void computesMetadataForTheLastPage() {
        TicketPageView view = pageOf(2, 20, 42, 2);

        assertThat(view.totalPages()).isEqualTo(3);
        assertThat(view.hasNext()).isFalse();
        assertThat(view.hasPrevious()).isTrue();
    }

    @Test
    void outOfRangePageReturnsEmptyItemsWithFullMetadata() {
        TicketPageView view = pageOf(9, 20, 42, 0);

        assertThat(view.items()).isEmpty();
        assertThat(view.totalElements()).isEqualTo(42);
        assertThat(view.totalPages()).isEqualTo(3);
        assertThat(view.hasNext()).isFalse();
        assertThat(view.hasPrevious()).isTrue();
    }

    @Test
    void emptyResultHasZeroPages() {
        TicketPageView view = pageOf(0, 20, 0, 0);

        assertThat(view.items()).isEmpty();
        assertThat(view.totalPages()).isZero();
        assertThat(view.hasNext()).isFalse();
        assertThat(view.hasPrevious()).isFalse();
    }

    @Test
    void roundsTotalPagesUp() {
        assertThat(pageOf(0, 20, 1, 1).totalPages()).isEqualTo(1);
        assertThat(pageOf(0, 20, 20, 20).totalPages()).isEqualTo(1);
        assertThat(pageOf(0, 20, 21, 20).totalPages()).isEqualTo(2);
        assertThat(pageOf(0, 100, 100, 100).totalPages()).isEqualTo(1);
        assertThat(pageOf(0, 1, 5, 1).totalPages()).isEqualTo(5);
    }

    @Test
    void totalPagesDoesNotOverflowForHugeTotals() {
        // totalElements 是 long，直接用 int 运算会溢出成负数
        TicketPageView view = pageOf(0, 20, Long.MAX_VALUE, 0);

        assertThat(view.totalPages()).isEqualTo(Long.MAX_VALUE / 20 + 1);
        assertThat(view.totalPages()).isPositive();
        assertThat(view.hasNext()).isTrue();
    }

    @Test
    void hasNextIsComputedWithLongArithmetic() {
        // page 很大时 (page + 1) * size 若用 int 计算会溢出成负数，从而错判 hasNext
        TicketPageView view = pageOf(Integer.MAX_VALUE, 100, Long.MAX_VALUE, 0);

        assertThat(view.hasNext()).isTrue();
    }

    @Test
    void itemsAreANonNullImmutableCopy() {
        List<VersionedTicket> source = new ArrayList<>();
        source.add(ticket("11111111-2222-3333-4444-555555555555", 3L));
        TicketSearchResult result = new TicketSearchResult(source, 1);
        source.clear();

        assertThat(result.items()).as("存储结果必须持有副本").hasSize(1);

        TicketPageView view = TicketPageView.from(result, criteria(0, 20));
        assertThat(view.items()).hasSize(1);
        assertThatThrownBy(() -> view.items().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(view.items().get(0).version()).isEqualTo(3L);
    }

    @Test
    void mapsEachItemWithItsOwnVersion() {
        TicketSearchResult result = new TicketSearchResult(
                List.of(ticket("11111111-2222-3333-4444-555555555555", 0L),
                        ticket("99999999-8888-7777-6666-555555555555", 7L)),
                2);

        TicketPageView view = TicketPageView.from(result, criteria(0, 20));

        assertThat(view.items()).extracting(item -> item.version()).containsExactly(0L, 7L);
        assertThat(view.items()).extracting(item -> item.id().value().toString())
                .containsExactly("11111111-2222-3333-4444-555555555555", "99999999-8888-7777-6666-555555555555");
    }

    @Test
    void echoesTheEffectiveSort() {
        TicketPageView view = TicketPageView.from(new TicketSearchResult(List.of(), 0),
                new TicketSearchCriteria(0, 20, null, null, null, null, null, null,
                        TicketSortField.PRIORITY, TicketSortDirection.ASC));

        assertThat(view.sortField()).isEqualTo(TicketSortField.PRIORITY);
        assertThat(view.direction()).isEqualTo(TicketSortDirection.ASC);
    }

    @Test
    void rejectsNullResultOrCriteria() {
        assertThatThrownBy(() -> TicketPageView.from(null, criteria(0, 20)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> TicketPageView.from(new TicketSearchResult(List.of(), 0), null))
                .isInstanceOf(NullPointerException.class);
    }

    // ---------- 辅助 ----------

    private static TicketPageView pageOf(int page, int size, long totalElements, int itemsOnPage) {
        List<VersionedTicket> items = new ArrayList<>();
        for (int index = 0; index < itemsOnPage; index++) {
            items.add(ticket(String.format("00000000-0000-0000-0000-%012d", index), 0L));
        }
        return TicketPageView.from(new TicketSearchResult(items, totalElements), criteria(page, size));
    }

    private static TicketSearchCriteria criteria(int page, int size) {
        return new TicketSearchCriteria(page, size, null, null, null, null, null, null,
                TicketSortField.UPDATED_AT, TicketSortDirection.DESC);
    }

    private static VersionedTicket ticket(String uuid, long version) {
        TicketId id = TicketId.of(UUID.fromString(uuid));
        return new VersionedTicket(Ticket.create(id, "标题", "描述", TicketCategory.OTHER,
                TicketPriority.P3, UserId.of("alice"), BASE), version);
    }
}
