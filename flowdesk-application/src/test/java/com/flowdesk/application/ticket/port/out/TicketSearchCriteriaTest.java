package com.flowdesk.application.ticket.port.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowdesk.application.ticket.query.TicketSortDirection;
import com.flowdesk.application.ticket.query.TicketSortField;
import org.junit.jupiter.api.Test;

/**
 * {@link TicketSearchCriteria} 自身不变量的边界测试。
 *
 * <p>这一层是「已经规范化」的类型，因此它必须自己拒绝非法值，而不是依赖调用方先校验：
 * 构造成功即意味着对象可安全下推到存储。测试直接构造，不经过任何校验器。</p>
 */
class TicketSearchCriteriaTest {

    // ---------- 分页 ----------

    @Test
    void acceptsPageBoundaries() {
        assertThat(criteria(0, 1).page()).isZero();
        assertThat(criteria(999_999, 100).page()).isEqualTo(999_999);
    }

    @Test
    void rejectsNegativePage() {
        assertThatIllegalArgumentException().isThrownBy(() -> criteria(-1, 20))
                .withMessageContaining("page");
        assertThatIllegalArgumentException().isThrownBy(() -> criteria(Integer.MIN_VALUE, 20))
                .withMessageContaining("page");
    }

    @Test
    void acceptsSizeBoundaries() {
        assertThat(criteria(0, TicketSearchCriteria.MIN_SIZE).size()).isEqualTo(1);
        assertThat(criteria(0, TicketSearchCriteria.MAX_SIZE).size()).isEqualTo(100);
    }

    @Test
    void rejectsSizeOutOfRange() {
        assertThatIllegalArgumentException().isThrownBy(() -> criteria(0, 0)).withMessageContaining("size");
        assertThatIllegalArgumentException().isThrownBy(() -> criteria(0, -1)).withMessageContaining("size");
        assertThatIllegalArgumentException().isThrownBy(() -> criteria(0, 101)).withMessageContaining("size");
        assertThatIllegalArgumentException().isThrownBy(() -> criteria(0, Integer.MAX_VALUE))
                .withMessageContaining("size");
    }

    @Test
    void offsetUsesLongArithmetic() {
        assertThat(criteria(0, 20).offset()).isZero();
        assertThat(criteria(3, 20).offset()).isEqualTo(60L);
        // int 运算会溢出成负数，long 不会
        assertThat(criteria(Integer.MAX_VALUE, TicketSearchCriteria.MAX_SIZE).offset())
                .isEqualTo(214_748_364_700L);
    }

    // ---------- 用户标识 ----------

    @Test
    void nullUserIdentifiersMeanNoFilter() {
        TicketSearchCriteria value = new TicketSearchCriteria(0, 20, null, null, null, null, null, null,
                TicketSortField.UPDATED_AT, TicketSortDirection.DESC);

        assertThat(value.requesterId()).isNull();
        assertThat(value.assigneeId()).isNull();
        assertThat(value.keyword()).isNull();
    }

    @Test
    void acceptsNormalizedUserIdentifiersAtTheirLimit() {
        String value = "u".repeat(TicketSearchCriteria.MAX_USER_ID_LENGTH);

        assertThat(criteriaWith("requesterId", value).requesterId()).hasSize(64);
        assertThat(criteriaWith("assigneeId", value).assigneeId()).hasSize(64);
    }

    @Test
    void rejectsBlankUserIdentifiers() {
        for (String blank : new String[] { "", " ", "   ", "\t", "\u3000" }) {
            assertThatIllegalArgumentException().isThrownBy(() -> criteriaWith("requesterId", blank))
                    .withMessageContaining("requesterId");
            assertThatIllegalArgumentException().isThrownBy(() -> criteriaWith("assigneeId", blank))
                    .withMessageContaining("assigneeId");
        }
    }

    @Test
    void rejectsUserIdentifiersThatWereNotStripped() {
        // 不静默 strip：带首尾空白就是「没规范化」，必须拒绝
        assertThatIllegalArgumentException().isThrownBy(() -> criteriaWith("requesterId", " alice"))
                .withMessageContaining("requesterId");
        assertThatIllegalArgumentException().isThrownBy(() -> criteriaWith("assigneeId", "bob "))
                .withMessageContaining("assigneeId");
        assertThatIllegalArgumentException().isThrownBy(() -> criteriaWith("requesterId", "\u3000alice"))
                .withMessageContaining("requesterId");
    }

    @Test
    void rejectsOverlongUserIdentifiers() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> criteriaWith("requesterId", "u".repeat(65))).withMessageContaining("requesterId");
        assertThatIllegalArgumentException().isThrownBy(
                () -> criteriaWith("assigneeId", "u".repeat(65))).withMessageContaining("assigneeId");
    }

    // ---------- 关键字 ----------

    @Test
    void acceptsKeywordAtItsLimit() {
        assertThat(criteriaWith("keyword", "k".repeat(TicketSearchCriteria.MAX_KEYWORD_LENGTH)).keyword())
                .hasSize(200);
    }

    @Test
    void rejectsBlankKeyword() {
        for (String blank : new String[] { "", " ", "\u3000\u3000" }) {
            assertThatIllegalArgumentException().isThrownBy(() -> criteriaWith("keyword", blank))
                    .withMessageContaining("keyword");
        }
    }

    @Test
    void rejectsKeywordThatWasNotStripped() {
        assertThatIllegalArgumentException().isThrownBy(() -> criteriaWith("keyword", " 登录"))
                .withMessageContaining("keyword");
        assertThatIllegalArgumentException().isThrownBy(() -> criteriaWith("keyword", "登录 "))
                .withMessageContaining("keyword");
    }

    @Test
    void rejectsOverlongKeyword() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> criteriaWith("keyword", "k".repeat(201))).withMessageContaining("keyword");
    }

    @Test
    void keepsLikeWildcardsAsOrdinaryCharacters() {
        // 通配符属于「普通字符」，本类型不做任何转义（转义是 SQL 方言细节，在适配器里做）
        assertThat(criteriaWith("keyword", "50%!_off").keyword()).isEqualTo("50%!_off");
    }

    // ---------- 排序 ----------

    @Test
    void rejectsNullSortFieldOrDirection() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TicketSearchCriteria(0, 20, null, null, null,
                null, null, null, null, TicketSortDirection.DESC)).withMessageContaining("sortField");
        assertThatIllegalArgumentException().isThrownBy(() -> new TicketSearchCriteria(0, 20, null, null, null,
                null, null, null, TicketSortField.UPDATED_AT, null)).withMessageContaining("direction");
    }

    @Test
    void neverThrowsNullPointerExceptionForInvalidInput() {
        // 拒绝方式统一为 IllegalArgumentException，调用方不必区分两种异常
        assertThatThrownBy(() -> new TicketSearchCriteria(0, 20, null, null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- 常量只有一个权威来源 ----------

    @Test
    void exposesTheAuthoritativeLimits() {
        assertThat(TicketSearchCriteria.MIN_PAGE).isZero();
        assertThat(TicketSearchCriteria.MIN_SIZE).isEqualTo(1);
        assertThat(TicketSearchCriteria.MAX_SIZE).isEqualTo(100);
        assertThat(TicketSearchCriteria.MAX_KEYWORD_LENGTH).isEqualTo(200);
        assertThat(TicketSearchCriteria.MAX_USER_ID_LENGTH).isEqualTo(64);
    }

    // ---------- 辅助 ----------

    private static TicketSearchCriteria criteria(int page, int size) {
        return new TicketSearchCriteria(page, size, null, null, null, null, null, null,
                TicketSortField.UPDATED_AT, TicketSortDirection.DESC);
    }

    /**
     * 构造一个只设置了指定字符串条件的查询，便于逐一验证边界。
     */
    private static TicketSearchCriteria criteriaWith(String fieldName, String value) {
        return new TicketSearchCriteria(0, 20, null, null, null,
                "requesterId".equals(fieldName) ? value : null,
                "assigneeId".equals(fieldName) ? value : null,
                "keyword".equals(fieldName) ? value : null,
                TicketSortField.UPDATED_AT, TicketSortDirection.DESC);
    }
}
