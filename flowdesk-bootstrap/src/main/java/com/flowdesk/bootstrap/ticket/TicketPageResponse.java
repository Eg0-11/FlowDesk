package com.flowdesk.bootstrap.ticket;

import com.flowdesk.application.ticket.view.TicketPageView;
import java.util.List;
import java.util.Objects;

/**
 * 工单列表响应体。
 *
 * <p>{@code items} 复用 {@link TicketResponse}，与单条查询返回的是同一种表示 ——
 * 客户端可以用同一套解析代码处理两者，也保证每项都带<b>正确的 version</b>。</p>
 *
 * <p>列表响应<b>不返回 ETag</b>：集合没有单一版本号，给它一个 ETag 只会诱导客户端
 * 对整页做条件请求，而页内容会随任何一条工单的变化而变化。逐条的并发控制仍走
 * {@code GET /api/v1/tickets/{ticketId}} 的 ETag + 写请求的 {@code If-Match}。</p>
 *
 * <p>分页元数据全部由应用层推导后传入（{@code totalPages} 用 {@code long}，
 * 不会因 {@code int} 溢出而变成负数）。</p>
 *
 * @param items         当前页的工单，永不为 {@code null}；越界页为空数组
 * @param page          本次请求的页码
 * @param size          本次请求的每页条数
 * @param totalElements 满足条件的总条数
 * @param totalPages    总页数；无数据时为 0
 * @param hasNext       是否还有下一页
 * @param hasPrevious   是否有上一页
 * @param sort          实际生效的排序
 */
public record TicketPageResponse(List<TicketResponse> items,
                                 int page,
                                 int size,
                                 long totalElements,
                                 long totalPages,
                                 boolean hasNext,
                                 boolean hasPrevious,
                                 Sort sort) {

    public TicketPageResponse {
        Objects.requireNonNull(items, "items 不能为 null");
        Objects.requireNonNull(sort, "sort 不能为 null");
        items = List.copyOf(items);
    }

    /**
     * 由应用层分页视图转换。
     *
     * @param view 分页视图
     * @return 响应体
     */
    public static TicketPageResponse from(TicketPageView view) {
        return new TicketPageResponse(
                view.items().stream().map(TicketResponse::from).toList(),
                view.page(),
                view.size(),
                view.totalElements(),
                view.totalPages(),
                view.hasNext(),
                view.hasPrevious(),
                new Sort(view.sortField().wireName(), view.direction().wireName()));
    }

    /**
     * 排序信息。
     *
     * <p>回显的是<b>实际生效</b>的取值（包括调用方没传时套用的默认值），
     * 这样客户端不必自己推断服务端用了什么排序。</p>
     *
     * @param field     排序字段线上名称
     * @param direction 排序方向线上名称
     */
    public record Sort(String field, String direction) {
    }
}
