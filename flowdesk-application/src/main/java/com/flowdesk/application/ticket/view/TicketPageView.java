package com.flowdesk.application.ticket.view;

import com.flowdesk.application.ticket.port.out.TicketSearchCriteria;
import com.flowdesk.application.ticket.port.out.TicketSearchResult;
import com.flowdesk.application.ticket.port.out.VersionedTicket;
import com.flowdesk.application.ticket.query.TicketSortDirection;
import com.flowdesk.application.ticket.query.TicketSortField;
import java.util.List;
import java.util.Objects;

/**
 * 工单分页视图：列表查询对外的唯一输出形态。
 *
 * <p>不可变 record。分页元数据全部由「满足条件的总数 + 本次分页参数」推导，
 * 推导过程刻意使用 {@code long} 运算，因此不会出现 {@code int} 溢出：</p>
 * <ul>
 *   <li>{@code totalPages = totalElements / size}(向上取整)；</li>
 *   <li>{@code hasNext}：下一面的起始下标是否仍小于总数；</li>
 *   <li>{@code hasPrevious = page > 0}。</li>
 * </ul>
 *
 * <p><b>越界页不是错误</b>：请求超出最后一页时返回 {@code items} 为空、
 * {@code totalElements}/{@code totalPages} 照常给出的 200 响应，
 * 而不是 404 —— 客户端翻页翻过头是正常现象。</p>
 *
 * <p>{@code items} 构造时复制为不可变列表，且每项都是
 * {@link TicketView#from(com.flowdesk.domain.ticket.Ticket, long)} 映射出的只读视图，
 * 含有该项<b>真实的版本号</b>。</p>
 *
 * @param items         当前页的工单视图，永不为 {@code null}
 * @param page          本次请求的页码（原样回显，即使越界）
 * @param size          本次请求的每页条数
 * @param totalElements 满足条件的总条数
 * @param totalPages    总页数；无数据时为 0
 * @param hasNext       是否还有下一页
 * @param hasPrevious   是否有上一页
 * @param sortField     实际生效的排序字段
 * @param direction     实际生效的排序方向
 */
public record TicketPageView(List<TicketView> items,
                             int page,
                             int size,
                             long totalElements,
                             long totalPages,
                             boolean hasNext,
                             boolean hasPrevious,
                             TicketSortField sortField,
                             TicketSortDirection direction) {

    public TicketPageView {
        Objects.requireNonNull(items, "items 不能为 null");
        Objects.requireNonNull(sortField, "sortField 不能为 null");
        Objects.requireNonNull(direction, "direction 不能为 null");
        items = List.copyOf(items);
    }

    /**
     * 由存储结果与查询条件推导分页视图。
     *
     * @param result   存储返回的结果
     * @param criteria 本次生效的查询条件
     * @return 分页视图
     */
    public static TicketPageView from(TicketSearchResult result, TicketSearchCriteria criteria) {
        Objects.requireNonNull(result, "result 不能为 null");
        Objects.requireNonNull(criteria, "criteria 不能为 null");

        long totalElements = Math.max(result.totalElements(), 0L);
        int size = criteria.size();
        long page = criteria.page();

        long totalPages = totalElements / size + (totalElements % size == 0 ? 0L : 1L);
        boolean hasNext = (page + 1) * size < totalElements;

        List<TicketView> items = result.items().stream()
                .map(TicketPageView::toView)
                .toList();

        return new TicketPageView(items, criteria.page(), size, totalElements, totalPages, hasNext,
                page > 0, criteria.sortField(), criteria.direction());
    }

    private static TicketView toView(VersionedTicket versioned) {
        return TicketView.from(versioned.ticket(), versioned.version());
    }
}
