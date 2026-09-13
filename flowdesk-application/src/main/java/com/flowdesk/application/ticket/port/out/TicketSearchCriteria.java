package com.flowdesk.application.ticket.port.out;

import com.flowdesk.application.ticket.query.TicketSortDirection;
import com.flowdesk.application.ticket.query.TicketSortField;
import com.flowdesk.domain.ticket.TicketCategory;
import com.flowdesk.domain.ticket.TicketPriority;
import com.flowdesk.domain.ticket.TicketStatus;

/**
 * 工单列表查询条件（<b>已规范化</b>）。
 *
 * <p>与 {@link com.flowdesk.application.ticket.query.SearchTicketsQuery} 的区别是：
 * 这里的每个字段都是最终取值 —— 分页与排序字段已套用默认值，枚举已是领域枚举常量，
 * 字符串已 strip 并通过长度校验。存储适配器拿到它就可以直接下推，
 * <b>不需要也不允许</b>再做任何解析或容错。</p>
 *
 * <p>分页采用 offset 分页：{@link #offset()} 用 {@code long} 计算，避免
 * {@code page * size} 在 {@code int} 上溢出。</p>
 *
 * @param page        页码，从 0 开始，非负
 * @param size        每页条数，1～100
 * @param status      状态精确匹配；{@code null} 表示不过滤
 * @param category    分类精确匹配；{@code null} 表示不过滤
 * @param priority    优先级精确匹配；{@code null} 表示不过滤
 * @param requesterId 请求人精确匹配；{@code null} 表示不过滤
 * @param assigneeId  处理人精确匹配；{@code null} 表示不过滤
 * @param keyword     标题 / 描述包含搜索（已 strip）；{@code null} 表示不过滤
 * @param sortField   排序字段，非 {@code null}
 * @param direction   排序方向，非 {@code null}
 */
public record TicketSearchCriteria(int page,
                                   int size,
                                   TicketStatus status,
                                   TicketCategory category,
                                   TicketPriority priority,
                                   String requesterId,
                                   String assigneeId,
                                   String keyword,
                                   TicketSortField sortField,
                                   TicketSortDirection direction) {

    public TicketSearchCriteria {
        if (page < 0) {
            throw new IllegalArgumentException("page 不能为负数");
        }
        if (size < 1) {
            throw new IllegalArgumentException("size 必须为正数");
        }
        if (sortField == null) {
            throw new IllegalArgumentException("sortField 不能为 null");
        }
        if (direction == null) {
            throw new IllegalArgumentException("direction 不能为 null");
        }
    }

    /**
     * 分页偏移量。
     *
     * @return {@code page * size}，用 {@code long} 计算，不会溢出
     */
    public long offset() {
        return (long) this.page * this.size;
    }
}
