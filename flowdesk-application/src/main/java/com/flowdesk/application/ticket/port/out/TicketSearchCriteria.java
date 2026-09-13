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
 * <h2>本类型自己保证的不变量</h2>
 * <p>构造即校验，非法值直接拒绝（抛 {@link IllegalArgumentException}），
 * 因此「已经构造出来的 {@code TicketSearchCriteria} 一定是可下推的」是一条结构性保证，
 * 不依赖调用方自觉：</p>
 * <ul>
 *   <li>{@code page >= }{@value #MIN_PAGE}；</li>
 *   <li>{@code size} 在 {@value #MIN_SIZE}～{@value #MAX_SIZE} 之间；</li>
 *   <li>{@code requesterId}/{@code assigneeId}：{@code null} 表示不筛选；
 *       非 {@code null} 时必须<b>已经</b>去掉首尾空白（不做静默 strip）、非空白、
 *       长度不超过 {@value #MAX_USER_ID_LENGTH}；</li>
 *   <li>{@code keyword}：同上，长度上限 {@value #MAX_KEYWORD_LENGTH}；</li>
 *   <li>{@code sortField}/{@code direction} 非 {@code null}。</li>
 * </ul>
 *
 * <p><b>为什么不静默 strip</b>：本类型代表「已规范化」的输入。如果它在这里替调用方
 * 悄悄改值，那些「忘了 strip」的实现就会一直看不出来，同一个条件在不同调用路径上
 * 可能得到不同结果。拒绝才能让问题在产生处暴露。</p>
 *
 * <p><b>常量为什么在这里</b>：分页与长度上限是本类型的合法性边界，
 * 因此它们是唯一权威来源；查询校验器直接引用这些常量，
 * 不会出现「校验器允许 64、不变量允许 65」这类两套数字。</p>
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
 * @param keyword     标题 / 描述包含搜索；{@code null} 表示不过滤
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

    /** 页码下限。 */
    public static final int MIN_PAGE = 0;

    /** 每页条数下限。 */
    public static final int MIN_SIZE = 1;

    /** 每页条数上限。 */
    public static final int MAX_SIZE = 100;

    /** 关键字最大长度。 */
    public static final int MAX_KEYWORD_LENGTH = 200;

    /** 用户标识最大长度。 */
    public static final int MAX_USER_ID_LENGTH = 64;

    public TicketSearchCriteria {
        if (page < MIN_PAGE) {
            throw new IllegalArgumentException("page 不能小于 " + MIN_PAGE + "，实际为 " + page);
        }
        if (size < MIN_SIZE || size > MAX_SIZE) {
            throw new IllegalArgumentException("size 必须在 " + MIN_SIZE + " 到 " + MAX_SIZE + " 之间，实际为 " + size);
        }
        if (sortField == null) {
            throw new IllegalArgumentException("sortField 不能为 null");
        }
        if (direction == null) {
            throw new IllegalArgumentException("direction 不能为 null");
        }
        requesterId = requireNormalizedUserId(requesterId, "requesterId");
        assigneeId = requireNormalizedUserId(assigneeId, "assigneeId");
        keyword = requireNormalizedKeyword(keyword);
    }

    /**
     * 分页偏移量。
     *
     * @return {@code page * size}，用 {@code long} 计算，不会溢出
     */
    public long offset() {
        return (long) this.page * this.size;
    }

    private static String requireNormalizedUserId(String value, String fieldName) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空白");
        }
        if (!value.equals(value.strip())) {
            throw new IllegalArgumentException(fieldName + " 必须是已去掉首尾空白的形式");
        }
        if (value.length() > MAX_USER_ID_LENGTH) {
            throw new IllegalArgumentException(fieldName + " 长度不能超过 " + MAX_USER_ID_LENGTH);
        }
        return value;
    }

    private static String requireNormalizedKeyword(String value) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException("keyword 不能为空白");
        }
        if (!value.equals(value.strip())) {
            throw new IllegalArgumentException("keyword 必须是已去掉首尾空白的形式");
        }
        if (value.length() > MAX_KEYWORD_LENGTH) {
            throw new IllegalArgumentException("keyword 长度不能超过 " + MAX_KEYWORD_LENGTH);
        }
        return value;
    }
}
