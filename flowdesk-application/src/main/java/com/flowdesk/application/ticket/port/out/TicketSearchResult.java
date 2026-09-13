package com.flowdesk.application.ticket.port.out;

import java.util.List;
import java.util.Objects;

/**
 * 一次列表查询的结果：当前页的数据 + 满足条件的总数。
 *
 * <p>只携带 {@code totalElements} 而不携带「总页数 / 是否有下一页」：
 * 那两项是由总数与分页参数<b>推导</b>出来的展示语义，属于视图层的职责
 * （见 {@link com.flowdesk.application.ticket.view.TicketPageView}），
 * 存储适配器不该重复实现它们。</p>
 *
 * <p>{@code items} 在构造时被复制为<b>不可变列表</b>：适配器返回的列表之后怎么变都与本结果无关，
 * 调用方也不可能通过本结果改动存储。</p>
 *
 * @param items         当前页的数据，永不为 {@code null}，越界页为空列表
 * @param totalElements 满足筛选条件的总条数，永不为负数
 */
public record TicketSearchResult(List<VersionedTicket> items, long totalElements) {

    public TicketSearchResult {
        Objects.requireNonNull(items, "items 不能为 null");
        if (totalElements < 0) {
            throw new IllegalArgumentException("totalElements 不能为负数");
        }
        items = List.copyOf(items);
    }
}
