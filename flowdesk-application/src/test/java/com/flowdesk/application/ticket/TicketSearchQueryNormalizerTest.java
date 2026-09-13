package com.flowdesk.application.ticket;

import static com.flowdesk.application.ticket.ApplicationTestSupport.assertApplicationError;
import static org.assertj.core.api.Assertions.assertThat;

import com.flowdesk.application.ticket.port.out.TicketSearchCriteria;
import com.flowdesk.application.ticket.query.SearchTicketsQuery;
import com.flowdesk.application.ticket.query.TicketSearchQueryNormalizer;
import com.flowdesk.application.ticket.query.TicketSortDirection;
import com.flowdesk.application.ticket.query.TicketSortField;
import com.flowdesk.domain.ticket.TicketCategory;
import com.flowdesk.domain.ticket.TicketPriority;
import com.flowdesk.domain.ticket.TicketStatus;
import org.junit.jupiter.api.Test;

/**
 * 列表查询的规范化与校验测试：默认值、每个非法参数、strip 语义。
 *
 * <p>纯 JUnit 5，不启动 Spring：校验器本身就是纯 Java 的。</p>
 */
class TicketSearchQueryNormalizerTest {

    // ---------- 默认值 ----------

    @Test
    void appliesDefaultsWhenNothingIsProvided() {
        TicketSearchCriteria criteria = TicketSearchQueryNormalizer.normalize(SearchTicketsQuery.defaults());

        assertThat(criteria.page()).isZero();
        assertThat(criteria.size()).isEqualTo(20);
        assertThat(criteria.sortField()).isEqualTo(TicketSortField.UPDATED_AT);
        assertThat(criteria.direction()).isEqualTo(TicketSortDirection.DESC);
        assertThat(criteria.status()).isNull();
        assertThat(criteria.category()).isNull();
        assertThat(criteria.priority()).isNull();
        assertThat(criteria.requesterId()).isNull();
        assertThat(criteria.assigneeId()).isNull();
        assertThat(criteria.keyword()).isNull();
    }

    @Test
    void defaultSortIsUpdatedAtDescending() {
        assertThat(TicketSortField.DEFAULT).isEqualTo(TicketSortField.UPDATED_AT);
        assertThat(TicketSortDirection.DEFAULT).isEqualTo(TicketSortDirection.DESC);
    }

    @Test
    void acceptsBoundaryValuesOfPageAndSize() {
        assertThat(TicketSearchQueryNormalizer.normalize(SearchTicketsQuery.ofPage(0, 1)).size()).isEqualTo(1);
        assertThat(TicketSearchQueryNormalizer.normalize(SearchTicketsQuery.ofPage(7, 100)).size()).isEqualTo(100);
        assertThat(TicketSearchQueryNormalizer.normalize(SearchTicketsQuery.ofPage(999_999, 20)).page())
                .isEqualTo(999_999);
    }

    // ---------- 非法分页 ----------

    @Test
    void rejectsNegativePage() {
        assertApplicationError(() -> TicketSearchQueryNormalizer.normalize(
                SearchTicketsQuery.ofPage(-1, 20)), TicketApplicationErrorCode.INVALID_QUERY);
    }

    @Test
    void rejectsSizeBelowOne() {
        assertApplicationError(() -> TicketSearchQueryNormalizer.normalize(
                SearchTicketsQuery.ofPage(0, 0)), TicketApplicationErrorCode.INVALID_QUERY);
        assertApplicationError(() -> TicketSearchQueryNormalizer.normalize(
                SearchTicketsQuery.ofPage(0, -5)), TicketApplicationErrorCode.INVALID_QUERY);
    }

    @Test
    void rejectsSizeAboveOneHundred() {
        assertApplicationError(() -> TicketSearchQueryNormalizer.normalize(
                SearchTicketsQuery.ofPage(0, 101)), TicketApplicationErrorCode.INVALID_QUERY);
    }

    // ---------- 非法枚举 ----------

    @Test
    void rejectsUnknownEnumsForStatusCategoryAndPriority() {
        assertApplicationError(() -> normalize(new SearchTicketsQuery(null, null, "PENDING", null, null, null,
                null, null, null, null)), TicketApplicationErrorCode.INVALID_QUERY);
        assertApplicationError(() -> normalize(new SearchTicketsQuery(null, null, null, "BILLING", null, null,
                null, null, null, null)), TicketApplicationErrorCode.INVALID_QUERY);
        assertApplicationError(() -> normalize(new SearchTicketsQuery(null, null, null, null, "P5", null,
                null, null, null, null)), TicketApplicationErrorCode.INVALID_QUERY);
    }

    @Test
    void enumParsingIsCaseSensitiveAndDoesNotStrip() {
        // 小写枚举名不接受：接口契约要求与领域枚举名完全一致
        assertApplicationError(() -> normalize(new SearchTicketsQuery(null, null, "new", null, null, null, null,
                null, null, null)), TicketApplicationErrorCode.INVALID_QUERY);
        assertApplicationError(() -> normalize(new SearchTicketsQuery(null, null, null, null, "p1", null, null,
                null, null, null)), TicketApplicationErrorCode.INVALID_QUERY);
        // 枚举值两侧的空格也不接受
        assertApplicationError(() -> normalize(new SearchTicketsQuery(null, null, " NEW", null, null, null, null,
                null, null, null)), TicketApplicationErrorCode.INVALID_QUERY);
    }

    @Test
    void acceptsEveryValidEnumValue() {
        for (TicketStatus status : TicketStatus.values()) {
            assertThat(normalize(new SearchTicketsQuery(null, null, status.name(), null, null, null, null, null,
                    null, null)).status()).isEqualTo(status);
        }
        for (TicketCategory category : TicketCategory.values()) {
            assertThat(normalize(new SearchTicketsQuery(null, null, null, category.name(), null, null, null, null,
                    null, null)).category()).isEqualTo(category);
        }
        for (TicketPriority priority : TicketPriority.values()) {
            assertThat(normalize(new SearchTicketsQuery(null, null, null, null, priority.name(), null, null, null,
                    null, null)).priority()).isEqualTo(priority);
        }
    }

    // ---------- 用户标识与关键字 ----------

    @Test
    void stripsRequesterAndAssignee() {
        TicketSearchCriteria criteria = normalize(new SearchTicketsQuery(null, null, null, null, null,
                "  alice  ", "\tbob\n", null, null, null));

        assertThat(criteria.requesterId()).isEqualTo("alice");
        assertThat(criteria.assigneeId()).isEqualTo("bob");
    }

    @Test
    void stripsUnicodeWhitespaceFromKeyword() {
        TicketSearchCriteria criteria = normalize(new SearchTicketsQuery(null, null, null, null, null, null, null,
                "\u3000无法登录\u3000", null, null));

        assertThat(criteria.keyword()).isEqualTo("无法登录");
    }

    @Test
    void rejectsBlankRequesterOrAssigneeWhenProvided() {
        assertApplicationError(() -> normalize(new SearchTicketsQuery(null, null, null, null, null, "",
                null, null, null, null)), TicketApplicationErrorCode.INVALID_QUERY);
        assertApplicationError(() -> normalize(new SearchTicketsQuery(null, null, null, null, null, "   ",
                null, null, null, null)), TicketApplicationErrorCode.INVALID_QUERY);
        assertApplicationError(() -> normalize(new SearchTicketsQuery(null, null, null, null, null, null,
                "\u3000\u3000", null, null, null)), TicketApplicationErrorCode.INVALID_QUERY);
    }

    @Test
    void rejectsOverlongRequesterOrAssignee() {
        assertApplicationError(() -> normalize(new SearchTicketsQuery(null, null, null, null, null,
                "u".repeat(65), null, null, null, null)), TicketApplicationErrorCode.INVALID_QUERY);
        assertApplicationError(() -> normalize(new SearchTicketsQuery(null, null, null, null, null, null,
                "u".repeat(65), null, null, null)), TicketApplicationErrorCode.INVALID_QUERY);
    }

    @Test
    void acceptsExactlyMaximumLengthUserIds() {
        String value = "u".repeat(64);

        assertThat(normalize(new SearchTicketsQuery(null, null, null, null, null, value, null, null, null, null))
                .requesterId()).isEqualTo(value);
    }

    @Test
    void measuresUserIdentifierLengthAfterStrip() {
        // 前后空白先被去掉，因此 64 个有效字符 + 空白仍然合法
        String padded = "  " + "u".repeat(64) + "  ";

        assertThat(normalize(new SearchTicketsQuery(null, null, null, null, null, padded, null, null, null, null))
                .requesterId()).hasSize(64);
    }

    @Test
    void rejectsBlankKeywordWhenProvided() {
        assertApplicationError(() -> normalize(new SearchTicketsQuery(null, null, null, null, null, null, null,
                "", null, null)), TicketApplicationErrorCode.INVALID_QUERY);
        assertApplicationError(() -> normalize(new SearchTicketsQuery(null, null, null, null, null, null, null,
                "   ", null, null)), TicketApplicationErrorCode.INVALID_QUERY);
        assertApplicationError(() -> normalize(new SearchTicketsQuery(null, null, null, null, null, null, null,
                "\u3000", null, null)), TicketApplicationErrorCode.INVALID_QUERY);
    }

    @Test
    void acceptsKeywordOfExactlyTwoHundredCharacters() {
        String keyword = "k".repeat(200);

        assertThat(normalize(new SearchTicketsQuery(null, null, null, null, null, null, null, keyword, null, null))
                .keyword()).hasSize(200);
    }

    @Test
    void rejectsOverlongKeyword() {
        assertApplicationError(() -> normalize(new SearchTicketsQuery(null, null, null, null, null, null, null,
                "k".repeat(201), null, null)), TicketApplicationErrorCode.INVALID_QUERY);
        // 长度在 strip 之后判定
        assertApplicationError(() -> normalize(new SearchTicketsQuery(null, null, null, null, null, null, null,
                "  " + "k".repeat(201) + "  ", null, null)), TicketApplicationErrorCode.INVALID_QUERY);
    }

    @Test
    void keepsLikeWildcardsAsOrdinaryCharactersInTheKeyword() {
        // 应用层不做转义（那是 SQL 方言细节），但要保证这些字符原样保留、不被丢弃
        TicketSearchCriteria criteria = normalize(new SearchTicketsQuery(null, null, null, null, null, null, null,
                "50%!_off", null, null));

        assertThat(criteria.keyword()).isEqualTo("50%!_off");
    }

    // ---------- 排序 ----------

    @Test
    void acceptsEverySortFieldAndDirection() {
        for (TicketSortField field : TicketSortField.values()) {
            assertThat(normalize(new SearchTicketsQuery(null, null, null, null, null, null, null, null,
                    field.wireName(), null)).sortField()).isEqualTo(field);
        }
        assertThat(normalize(new SearchTicketsQuery(null, null, null, null, null, null, null, null, null, "asc"))
                .direction()).isEqualTo(TicketSortDirection.ASC);
        assertThat(normalize(new SearchTicketsQuery(null, null, null, null, null, null, null, null, null, "desc"))
                .direction()).isEqualTo(TicketSortDirection.DESC);
    }

    @Test
    void rejectsUnknownSortFieldAndDirection() {
        assertApplicationError(() -> normalize(new SearchTicketsQuery(null, null, null, null, null, null, null,
                null, "id", null)), TicketApplicationErrorCode.INVALID_QUERY);
        assertApplicationError(() -> normalize(new SearchTicketsQuery(null, null, null, null, null, null, null,
                null, "updated_at", null)), TicketApplicationErrorCode.INVALID_QUERY);
        assertApplicationError(() -> normalize(new SearchTicketsQuery(null, null, null, null, null, null, null,
                null, null, "ASC")), TicketApplicationErrorCode.INVALID_QUERY);
        assertApplicationError(() -> normalize(new SearchTicketsQuery(null, null, null, null, null, null, null,
                null, null, "descending")), TicketApplicationErrorCode.INVALID_QUERY);
    }

    @Test
    void rejectsMaliciousSortText() {
        // 排序取值只能来自白名单：任何想混进 SQL 的文本都只会得到一个校验错误
        assertApplicationError(() -> normalize(new SearchTicketsQuery(null, null, null, null, null, null, null,
                null, "updated_at DESC; DROP TABLE tickets--", null)),
                TicketApplicationErrorCode.INVALID_QUERY);
        assertApplicationError(() -> normalize(new SearchTicketsQuery(null, null, null, null, null, null, null,
                null, null, "asc, (SELECT 1)")), TicketApplicationErrorCode.INVALID_QUERY);
    }

    @Test
    void rejectsNullQuery() {
        assertApplicationError(() -> TicketSearchQueryNormalizer.normalize(null),
                TicketApplicationErrorCode.INVALID_QUERY);
    }

    @Test
    void errorMessagesNeverEchoTheClientInput() {
        String sentinel = "sentinel-sort-value";

        RuntimeException ex = ApplicationTestSupport.capture(() -> normalize(new SearchTicketsQuery(null, null,
                null, null, null, null, null, null, sentinel, null)));

        assertThat(ex.getMessage()).doesNotContain(sentinel);
        assertThat(ex.getMessage()).contains("sortBy");
    }

    // ---------- 辅助 ----------

    private static TicketSearchCriteria normalize(SearchTicketsQuery query) {
        return TicketSearchQueryNormalizer.normalize(query);
    }
}
