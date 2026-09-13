package com.flowdesk.infrastructure.ticket.persistence.jdbc;

import com.flowdesk.application.ticket.port.out.TicketSearchCriteria;
import com.flowdesk.application.ticket.query.TicketSortDirection;
import com.flowdesk.domain.ticket.TicketPriority;
import com.flowdesk.domain.ticket.TicketStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 列表 / 搜索查询的 SQL 拼装。
 *
 * <p>与 {@link TicketSql} 的分工：后者是给死的语句常量，本类负责<b>按条件拼装</b>动态部分。
 * 拼装规则只有一条 —— <b>SQL 片段必须来自本类的常量</b>：</p>
 * <ul>
 *   <li>筛选条件：每个条件对应一个写死的片段，值为 {@code ?} 占位符，<b>永不</b>拼入调用方文本；</li>
 *   <li>排序：先把客户端文本解析成
 *       {@link com.flowdesk.application.ticket.query.TicketSortField} /
 *       {@link TicketSortDirection} 枚举（白名单，解析失败在应用层就报错），
 *       再由 {@code switch} 映射成写死的列名与 {@code ASC}/{@code DESC} 关键字；</li>
 *   <li>枚举的业务顺序（{@code P1→P4}、{@code NEW→CLOSED}）由本类的常量数组给出，
 *       同样来自编译期常量，与客户端输入无关。</li>
 * </ul>
 *
 * <p>因此本类虽然在做字符串拼接，但拼接的每一个片段都是程序常量，
 * <b>不存在把用户输入拼进 SQL 的路径</b>。</p>
 *
 * <h2>为什么排序用 CASE 表达式</h2>
 * <p>{@code priority} 与 {@code status} 在库里是 {@code VARCHAR}，按字母排序得到的是
 * {@code P1,P2,P3,P4}（碰巧正确）与 {@code ASSIGNED,CLOSED,IN_PROGRESS,NEW,RESOLVED}（完全错误）。
 * 业务顺序与字典序不一致，所以用 {@code CASE} 把枚举映射成序号后再排序。
 * 另一种做法是加一个冗余的排序列，但那会改动 V1 已定的表结构，本阶段不做。</p>
 *
 * <h2>为什么总是追加 id</h2>
 * <p>四个排序字段都可能出现大量相同值（同一时刻创建、同一优先级、同一状态）。
 * 只按主字段排序时，这些并列行的相对顺序由数据库实现决定，翻页时同一行可能在两页出现或被跳过。
 * 追加 {@code id ASC} 后顺序<b>全序且稳定</b>，这是 offset 分页能自洽的前提。</p>
 */
final class TicketSearchSql {

    private static final String COUNT_PREFIX = "SELECT COUNT(*) FROM tickets";

    private static final String SELECT_PREFIX = "SELECT " + TicketSql.COLUMNS + " FROM tickets";

    /** 优先级业务顺序：{@code P1} 最高。 */
    private static final TicketPriority[] PRIORITY_ORDER = {
            TicketPriority.P1, TicketPriority.P2, TicketPriority.P3, TicketPriority.P4 };

    /** 状态生命周期顺序。 */
    private static final TicketStatus[] STATUS_ORDER = {
            TicketStatus.NEW, TicketStatus.ASSIGNED, TicketStatus.IN_PROGRESS,
            TicketStatus.RESOLVED, TicketStatus.CLOSED };

    private TicketSearchSql() {
    }

    /**
     * 统计满足条件的总条数。
     *
     * @param criteria 查询条件
     * @return 完整 SQL
     */
    static String countSql(TicketSearchCriteria criteria) {
        return COUNT_PREFIX + whereClause(criteria);
    }

    /**
     * 取当前页数据。
     *
     * <p>{@code LIMIT ? OFFSET ?} 在 PostgreSQL 与 H2 的 PostgreSQL 兼容模式下语法一致，
     * 且两个值都是绑定的整数，不做字符串拼接。</p>
     *
     * @param criteria 查询条件
     * @return 完整 SQL
     */
    static String pageSql(TicketSearchCriteria criteria) {
        return SELECT_PREFIX + whereClause(criteria) + orderByClause(criteria) + " LIMIT ? OFFSET ?";
    }

    /**
     * 筛选条件的绑定值，<b>顺序与 {@link #whereClause} 中占位符的出现顺序严格一致</b>。
     *
     * @param criteria 查询条件
     * @return 参数列表，可为空列表
     */
    static List<Object> filterParameters(TicketSearchCriteria criteria) {
        List<Object> parameters = new ArrayList<>(7);
        if (criteria.status() != null) {
            parameters.add(criteria.status().name());
        }
        if (criteria.category() != null) {
            parameters.add(criteria.category().name());
        }
        if (criteria.priority() != null) {
            parameters.add(criteria.priority().name());
        }
        if (criteria.requesterId() != null) {
            parameters.add(criteria.requesterId());
        }
        if (criteria.assigneeId() != null) {
            parameters.add(criteria.assigneeId());
        }
        if (criteria.keyword() != null) {
            // 两边都是小写：数据库列经 LOWER()，绑定值在 Java 侧转换，大小写因此不敏感
            String pattern = LikePattern.contains(criteria.keyword().toLowerCase(Locale.ROOT));
            parameters.add(pattern);
            parameters.add(pattern);
        }
        return parameters;
    }

    /**
     * {@code WHERE} 子句；无条件时返回空字符串。
     *
     * <p>关键字同时匹配标题与描述，用括号包住，保证与其它条件仍是 AND 组合，
     * 不会因为 {@code OR} 的优先级把筛选条件放跑。</p>
     */
    static String whereClause(TicketSearchCriteria criteria) {
        List<String> conditions = new ArrayList<>(6);
        if (criteria.status() != null) {
            conditions.add("status = ?");
        }
        if (criteria.category() != null) {
            conditions.add("category = ?");
        }
        if (criteria.priority() != null) {
            conditions.add("priority = ?");
        }
        if (criteria.requesterId() != null) {
            conditions.add("requester_id = ?");
        }
        if (criteria.assigneeId() != null) {
            conditions.add("assignee_id = ?");
        }
        if (criteria.keyword() != null) {
            conditions.add("(LOWER(title) LIKE ?" + LikePattern.ESCAPE_CLAUSE
                    + " OR LOWER(description) LIKE ?" + LikePattern.ESCAPE_CLAUSE + ")");
        }
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }

    /**
     * {@code ORDER BY} 子句：白名单映射 + 稳定的 {@code id ASC} 兜底键。
     */
    static String orderByClause(TicketSearchCriteria criteria) {
        String direction = switch (criteria.direction()) {
            case ASC -> "ASC";
            case DESC -> "DESC";
        };
        String primary = switch (criteria.sortField()) {
            case CREATED_AT -> "created_at";
            case UPDATED_AT -> "updated_at";
            case PRIORITY -> sequenceExpression("priority", PRIORITY_ORDER);
            case STATUS -> sequenceExpression("status", STATUS_ORDER);
        };
        return " ORDER BY " + primary + " " + direction + ", id ASC";
    }

    /**
     * 把枚举列映射成业务序号表达式。
     *
     * <p>取值来自编译期常量数组，用 {@code ELSE} 兜住理论上的未知取值
     * （数据库 CHECK 约束已保证不会出现，这里只是防御）。</p>
     */
    private static String sequenceExpression(String column, Enum<?>[] order) {
        StringBuilder expression = new StringBuilder("CASE ").append(column);
        for (int index = 0; index < order.length; index++) {
            expression.append(" WHEN '").append(order[index].name()).append("' THEN ").append(index + 1);
        }
        return expression.append(" ELSE ").append(order.length + 1).append(" END").toString();
    }
}
