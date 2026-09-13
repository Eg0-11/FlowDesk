package com.flowdesk.infrastructure.ticket.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowdesk.application.ticket.port.out.TicketSearchCriteria;
import com.flowdesk.application.ticket.query.TicketSortDirection;
import com.flowdesk.application.ticket.query.TicketSortField;
import com.flowdesk.domain.ticket.TicketCategory;
import com.flowdesk.domain.ticket.TicketPriority;
import com.flowdesk.domain.ticket.TicketStatus;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 动态 SQL 拼装测试（不需要数据库）。
 *
 * <p>重点验证三件事：</p>
 * <ol>
 *   <li>占位符数量与绑定值数量<b>严格一致</b>（错位是这类拼装最危险的缺陷）；</li>
 *   <li>{@code ORDER BY} 只能来自白名单，且总是以 {@code id ASC} 结尾；</li>
 *   <li>筛选值从不进入语句结构 —— 语句里只有 {@code ?}，没有调用方文本。</li>
 * </ol>
 */
class TicketSearchSqlTest {

    @Test
    void omitsWhereClauseWhenNothingIsFiltered() {
        assertThat(TicketSearchSql.whereClause(criteria())).isEmpty();
        assertThat(TicketSearchSql.filterParameters(criteria())).isEmpty();
        assertThat(TicketSearchSql.countSql(criteria())).isEqualTo("SELECT COUNT(*) FROM tickets");
    }

    @Test
    void addsOneConditionPerFilter() {
        assertThat(TicketSearchSql.whereClause(criteriaWithStatus()))
                .isEqualTo(" WHERE status = ?");
        assertThat(TicketSearchSql.whereClause(criteriaWithRequester()))
                .isEqualTo(" WHERE requester_id = ?");
        assertThat(TicketSearchSql.whereClause(criteriaWithAssignee()))
                .isEqualTo(" WHERE assignee_id = ?");
    }

    @Test
    void combinesEveryFilterWithAnd() {
        TicketSearchCriteria criteria = new TicketSearchCriteria(0, 20, TicketStatus.NEW, TicketCategory.NETWORK,
                TicketPriority.P1, "alice", "bob", "登录", TicketSortField.UPDATED_AT,
                TicketSortDirection.DESC);

        String where = TicketSearchSql.whereClause(criteria);

        assertThat(where).startsWith(" WHERE status = ? AND category = ? AND priority = ?");
        assertThat(where).contains("AND requester_id = ?");
        assertThat(where).contains("AND assignee_id = ?");
        assertThat(where).contains("(LOWER(title) LIKE ? ESCAPE '!' OR LOWER(description) LIKE ? ESCAPE '!')");
    }

    @Test
    void keywordIsWrappedInParenthesesSoItCannotEscapeTheAndChain() {
        TicketSearchCriteria criteria = new TicketSearchCriteria(0, 20, null, null, null, null, null, "x",
                TicketSortField.UPDATED_AT, TicketSortDirection.DESC);

        assertThat(TicketSearchSql.whereClause(criteria))
                .isEqualTo(" WHERE (LOWER(title) LIKE ? ESCAPE '!' OR LOWER(description) LIKE ? ESCAPE '!')");
    }

    @Test
    void keywordBindsTwoEscapedLowercasePatterns() {
        TicketSearchCriteria criteria = new TicketSearchCriteria(0, 20, null, null, null, null, null,
                "LOGIN%_!", TicketSortField.UPDATED_AT, TicketSortDirection.DESC);

        List<Object> parameters = TicketSearchSql.filterParameters(criteria);

        assertThat(parameters).hasSize(2);
        assertThat(parameters.get(0)).isEqualTo("%login!%!_!!%");
        assertThat(parameters.get(1)).isEqualTo(parameters.get(0));
    }

    @Test
    void neverPutsFilterValuesIntoTheStatement() {
        String sentinel = "sentinel'; drop table tickets--";
        TicketSearchCriteria criteria = new TicketSearchCriteria(0, 20, null, null, null, sentinel, sentinel,
                sentinel, TicketSortField.UPDATED_AT, TicketSortDirection.DESC);

        String sql = TicketSearchSql.pageSql(criteria);
        List<Object> parameters = TicketSearchSql.filterParameters(criteria);

        assertThat(sql).doesNotContain(sentinel);
        assertThat(sql).doesNotContain("drop table");
        // 值全部在参数里
        assertThat(parameters).contains(sentinel, LikePattern.contains(sentinel));
    }

    @ParameterizedTest
    @EnumSource(TicketSortField.class)
    void orderByAlwaysAppendsIdAsTheStableTieBreaker(TicketSortField field) {
        for (TicketSortDirection direction : TicketSortDirection.values()) {
            String orderBy = TicketSearchSql.orderByClause(criteria(field, direction));

            assertThat(orderBy).startsWith(" ORDER BY ");
            assertThat(orderBy).endsWith(", id ASC");
        }
    }

    @Test
    void orderByMapsTimeFieldsToTheirColumns() {
        assertThat(TicketSearchSql.orderByClause(criteria(TicketSortField.CREATED_AT, TicketSortDirection.ASC)))
                .isEqualTo(" ORDER BY created_at ASC, id ASC");
        assertThat(TicketSearchSql.orderByClause(criteria(TicketSortField.UPDATED_AT, TicketSortDirection.DESC)))
                .isEqualTo(" ORDER BY updated_at DESC, id ASC");
    }

    @Test
    void orderByUsesBusinessOrderForPriorityAndStatus() {
        String priority = TicketSearchSql.orderByClause(criteria(TicketSortField.PRIORITY,
                TicketSortDirection.ASC));
        String status = TicketSearchSql.orderByClause(criteria(TicketSortField.STATUS, TicketSortDirection.DESC));

        // P1 < P2 < P3 < P4：字典序碰巧也对，但这里靠的是显式 CASE，不依赖编码巧合
        assertThat(priority).contains("CASE priority WHEN 'P1' THEN 1 WHEN 'P2' THEN 2 WHEN 'P3' THEN 3");
        assertThat(priority).contains("WHEN 'P4' THEN 4");
        assertThat(priority).endsWith("ASC, id ASC");
        // 生命周期顺序与字典序不同（ASSIGNED 字典序在 NEW 之前），必须由 CASE 决定
        assertThat(status).contains("CASE status WHEN 'NEW' THEN 1 WHEN 'ASSIGNED' THEN 2"
                + " WHEN 'IN_PROGRESS' THEN 3 WHEN 'RESOLVED' THEN 4 WHEN 'CLOSED' THEN 5");
        assertThat(status).endsWith("DESC, id ASC");
    }

    @Test
    void pageSqlBindsLimitAndOffset() {
        assertThat(TicketSearchSql.pageSql(criteria()))
                .isEqualTo("SELECT " + TicketSql.COLUMNS + " FROM tickets"
                        + " ORDER BY updated_at DESC, id ASC LIMIT ? OFFSET ?");
    }

    @Test
    void countSqlNeverOrdersOrLimits() {
        String countSql = TicketSearchSql.countSql(criteriaWithStatus());

        assertThat(countSql).isEqualTo("SELECT COUNT(*) FROM tickets WHERE status = ?");
        assertThat(countSql).doesNotContain("ORDER BY");
        assertThat(countSql).doesNotContain("LIMIT");
    }

    @Test
    void placeholderCountAlwaysMatchesBoundParameterCount() {
        TicketSearchCriteria[] all = {
                criteria(),
                criteriaWithStatus(),
                criteriaWithRequester(),
                criteriaWithAssignee(),
                new TicketSearchCriteria(0, 20, TicketStatus.NEW, null, null, null, null, null,
                        TicketSortField.CREATED_AT, TicketSortDirection.ASC),
                new TicketSearchCriteria(0, 20, null, TicketCategory.OTHER, TicketPriority.P4, null, null, null,
                        TicketSortField.PRIORITY, TicketSortDirection.ASC),
                new TicketSearchCriteria(0, 20, TicketStatus.CLOSED, TicketCategory.OTHER, TicketPriority.P1,
                        "alice", "bob", "keyword", TicketSortField.STATUS, TicketSortDirection.DESC),
        };

        for (TicketSearchCriteria criteria : all) {
            String countSql = TicketSearchSql.countSql(criteria);
            String pageSql = TicketSearchSql.pageSql(criteria);
            int filterParameters = TicketSearchSql.filterParameters(criteria).size();

            assertThat(countPlaceholders(countSql)).as("COUNT 占位符必须等于筛选值个数").isEqualTo(filterParameters);
            assertThat(countPlaceholders(pageSql)).as("分页查询占位符 = 筛选值 + LIMIT + OFFSET")
                    .isEqualTo(filterParameters + 2);
        }
    }

    private static int countPlaceholders(String sql) {
        int count = 0;
        for (int index = 0; index < sql.length(); index++) {
            if (sql.charAt(index) == '?') {
                count++;
            }
        }
        return count;
    }

    // ---------- 辅助 ----------

    private static TicketSearchCriteria criteria() {
        return criteria(TicketSortField.UPDATED_AT, TicketSortDirection.DESC);
    }

    private static TicketSearchCriteria criteria(TicketSortField field, TicketSortDirection direction) {
        return new TicketSearchCriteria(0, 20, null, null, null, null, null, null, field, direction);
    }

    private static TicketSearchCriteria criteriaWithStatus() {
        return new TicketSearchCriteria(0, 20, TicketStatus.NEW, null, null, null, null, null,
                TicketSortField.UPDATED_AT, TicketSortDirection.DESC);
    }

    private static TicketSearchCriteria criteriaWithRequester() {
        return new TicketSearchCriteria(0, 20, null, null, null, "alice", null, null,
                TicketSortField.UPDATED_AT, TicketSortDirection.DESC);
    }

    private static TicketSearchCriteria criteriaWithAssignee() {
        return new TicketSearchCriteria(0, 20, null, null, null, null, "bob", null,
                TicketSortField.UPDATED_AT, TicketSortDirection.DESC);
    }
}
