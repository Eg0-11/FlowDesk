package com.flowdesk.application.ticket.query;

/**
 * 工单列表 / 条件搜索查询（<b>原始入参</b>）。
 *
 * <p>纯 Java record，不依赖 Spring。字段全部可空，{@code null} 表示「调用方没有提供该条件」，
 * 与「提供了空值」是两件不同的事：</p>
 * <ul>
 *   <li>{@code null} —— 不使用该条件；</li>
 *   <li>非 {@code null} 但是空白（例如 HTTP 的 {@code ?requesterId=}）—— <b>非法</b>，
 *       必须报错而不是被静默忽略。</li>
 * </ul>
 *
 * <p>本 record <b>不做</b>任何规范化与校验：它只是把入参原样携带到应用层校验器
 * （{@link TicketSearchQueryNormalizer}）。校验器负责 strip、套用默认值、
 * 解析枚举与排序白名单，并产出规范化后的
 * {@link com.flowdesk.application.ticket.port.out.TicketSearchCriteria}。</p>
 *
 * <p>枚举与排序字段在这里刻意保持为 {@link String}：只有这样才能把「客户端写了一串
 * 不认识的枚举名」当成一个可校验的输入来处理，而不是在 HTTP 层就靠类型转换悄悄失败。</p>
 *
 * @param page        页码，从 0 开始；{@code null} 表示使用默认值 0
 * @param size        每页条数，1～100；{@code null} 表示使用默认值 20
 * @param status      状态精确匹配；{@code null} 表示不过滤
 * @param category    分类精确匹配；{@code null} 表示不过滤
 * @param priority    优先级精确匹配；{@code null} 表示不过滤
 * @param requesterId 请求人精确匹配（strip 后比较）；{@code null} 表示不过滤
 * @param assigneeId  处理人精确匹配（strip 后比较）；{@code null} 表示不过滤
 * @param keyword     标题 / 描述的大小写不敏感包含搜索（strip 后）；{@code null} 表示不过滤
 * @param sortBy      排序字段，取值为 {@link TicketSortField} 的线上名称；{@code null} 表示默认
 * @param direction   排序方向，取值为 {@link TicketSortDirection} 的线上名称；{@code null} 表示默认
 */
public record SearchTicketsQuery(Integer page,
                                 Integer size,
                                 String status,
                                 String category,
                                 String priority,
                                 String requesterId,
                                 String assigneeId,
                                 String keyword,
                                 String sortBy,
                                 String direction) {

    /**
     * 全部条件都不提供的查询：等价于「第一页、按更新时间倒序」。
     *
     * @return 默认查询
     */
    public static SearchTicketsQuery defaults() {
        return new SearchTicketsQuery(null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * 只指定分页的查询。
     *
     * @param page 页码，从 0 开始
     * @param size 每页条数
     * @return 查询
     */
    public static SearchTicketsQuery ofPage(Integer page, Integer size) {
        return new SearchTicketsQuery(page, size, null, null, null, null, null, null, null, null);
    }
}
