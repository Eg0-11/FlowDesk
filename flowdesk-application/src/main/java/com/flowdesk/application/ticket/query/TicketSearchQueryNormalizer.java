package com.flowdesk.application.ticket.query;

import com.flowdesk.application.ticket.TicketApplicationErrorCode;
import com.flowdesk.application.ticket.TicketApplicationException;
import com.flowdesk.application.ticket.port.out.TicketSearchCriteria;
import com.flowdesk.domain.ticket.TicketCategory;
import com.flowdesk.domain.ticket.TicketPriority;
import com.flowdesk.domain.ticket.TicketStatus;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 列表查询的<b>规范化与校验</b>：把原始 {@link SearchTicketsQuery} 变成可直接下推到存储的
 * {@link TicketSearchCriteria}。
 *
 * <p>纯静态、无状态、不依赖 Spring。规则集中在这一处，HTTP 边界与应用层服务调用的是同一份实现，
 * 因此「接口怎么报错」与「用例怎么判定」不会各说各话。</p>
 *
 * <h2>规则</h2>
 * <ul>
 *   <li>{@code page}：默认 {@value #DEFAULT_PAGE}，不得小于
 *       {@link TicketSearchCriteria#MIN_PAGE}；</li>
 *   <li>{@code size}：默认 {@value #DEFAULT_SIZE}，必须在
 *       {@link TicketSearchCriteria#MIN_SIZE}～{@link TicketSearchCriteria#MAX_SIZE} 之间；</li>
 *   <li>{@code status}/{@code category}/{@code priority}：按领域枚举名<b>精确匹配</b>（区分大小写），
 *       不接受首尾空白（客户端不应给枚举值加空格）；</li>
 *   <li>{@code requesterId}/{@code assigneeId}：先 {@link String#strip()}，再判空白与长度上限
 *       {@link TicketSearchCriteria#MAX_USER_ID_LENGTH}；</li>
 *   <li>{@code keyword}：先 {@link String#strip()}，再判空白与长度上限
 *       {@link TicketSearchCriteria#MAX_KEYWORD_LENGTH}；</li>
 *   <li>{@code sortBy}/{@code direction}：按白名单解析，无法解析即报错。</li>
 * </ul>
 *
 * <p><b>数值只有一个权威来源</b>：分页上下限与两个长度上限都定义在
 * {@link TicketSearchCriteria} 上，本类只引用它们，不重复写数字。</p>
 *
 * <p>所有失败都抛 {@link TicketApplicationErrorCode#INVALID_QUERY}，且<b>文案固定</b>：
 * 不回显客户端传入的原始值（错误信息里只说「哪个参数不合法、允许什么」）。</p>
 */
public final class TicketSearchQueryNormalizer {

    /** 默认页码。 */
    public static final int DEFAULT_PAGE = 0;

    /** 默认每页条数。 */
    public static final int DEFAULT_SIZE = 20;

    private TicketSearchQueryNormalizer() {
    }

    /**
     * 规范化并校验查询。
     *
     * <p>产出的 {@link TicketSearchCriteria} 会再次校验自身不变量（那一层不静默 strip），
     * 因此本方法对字符串的 strip 是<b>唯一</b>的规范化动作，且发生在构造之前。</p>
     *
     * @param query 原始查询；不得为 {@code null}
     * @return 规范化后的存储查询条件
     * @throws TicketApplicationException 查询为 {@code null}，或任一参数不合法
     */
    public static TicketSearchCriteria normalize(SearchTicketsQuery query) {
        if (query == null) {
            throw invalid("查询条件不能为空");
        }

        return new TicketSearchCriteria(
                normalizePage(query.page()),
                normalizeSize(query.size()),
                enumValue(TicketStatus.class, query.status(), "status"),
                enumValue(TicketCategory.class, query.category(), "category"),
                enumValue(TicketPriority.class, query.priority(), "priority"),
                normalizeUserId(query.requesterId(), "requesterId"),
                normalizeUserId(query.assigneeId(), "assigneeId"),
                normalizeKeyword(query.keyword()),
                normalizeSortField(query.sortBy()),
                normalizeDirection(query.direction()));
    }

    private static int normalizePage(Integer page) {
        if (page == null) {
            return DEFAULT_PAGE;
        }
        if (page < TicketSearchCriteria.MIN_PAGE) {
            throw invalid("page 不能小于 " + TicketSearchCriteria.MIN_PAGE);
        }
        return page;
    }

    private static int normalizeSize(Integer size) {
        if (size == null) {
            return DEFAULT_SIZE;
        }
        if (size < TicketSearchCriteria.MIN_SIZE || size > TicketSearchCriteria.MAX_SIZE) {
            throw invalid("size 必须在 " + TicketSearchCriteria.MIN_SIZE + " 到 "
                    + TicketSearchCriteria.MAX_SIZE + " 之间");
        }
        return size;
    }

    /**
     * 枚举取值必须先转成领域枚举常量：这样存储层拿到的一定是合法取值，而不是客户端文本。
     */
    private static <E extends Enum<E>> E enumValue(Class<E> type, String raw, String fieldName) {
        if (raw == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw);
        }
        catch (IllegalArgumentException ex) {
            throw invalid(fieldName + " 取值不合法，允许值：" + names(type));
        }
    }

    private static String normalizeUserId(String raw, String fieldName) {
        if (raw == null) {
            return null;
        }
        String stripped = raw.strip();
        if (stripped.isEmpty()) {
            throw invalid(fieldName + " 不能为空白");
        }
        if (stripped.length() > TicketSearchCriteria.MAX_USER_ID_LENGTH) {
            throw invalid(fieldName + " 长度不能超过 " + TicketSearchCriteria.MAX_USER_ID_LENGTH + " 个字符");
        }
        return stripped;
    }

    private static String normalizeKeyword(String raw) {
        if (raw == null) {
            return null;
        }
        String stripped = raw.strip();
        if (stripped.isEmpty()) {
            throw invalid("keyword 不能为空白");
        }
        if (stripped.length() > TicketSearchCriteria.MAX_KEYWORD_LENGTH) {
            throw invalid("keyword 长度不能超过 " + TicketSearchCriteria.MAX_KEYWORD_LENGTH + " 个字符");
        }
        return stripped;
    }

    private static TicketSortField normalizeSortField(String raw) {
        if (raw == null) {
            return TicketSortField.DEFAULT;
        }
        return TicketSortField.fromWire(raw)
                .orElseThrow(() -> invalid("sortBy 取值不合法，允许值：" + TicketSortField.allowedWireNames()));
    }

    private static TicketSortDirection normalizeDirection(String raw) {
        if (raw == null) {
            return TicketSortDirection.DEFAULT;
        }
        return TicketSortDirection.fromWire(raw)
                .orElseThrow(() -> invalid("direction 取值不合法，允许值："
                        + TicketSortDirection.allowedWireNames()));
    }

    private static String names(Class<? extends Enum<?>> type) {
        return Stream.of(type.getEnumConstants()).map(Enum::name).collect(Collectors.joining("、"));
    }

    private static TicketApplicationException invalid(String detail) {
        return new TicketApplicationException(TicketApplicationErrorCode.INVALID_QUERY, detail);
    }
}
