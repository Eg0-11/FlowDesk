package com.flowdesk.application.ticket.query;

import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 列表排序字段的<b>白名单</b>。
 *
 * <p>排序字段来自客户端，因此绝不能把客户端的原文拼进 SQL。这里的枚举是唯一合法的取值集合：
 * 客户端文本先被解析成枚举常量，SQL 片段再根据枚举常量生成 ——
 * 无法解析的文本在到达存储层之前就已被拒绝。</p>
 *
 * <p>每个常量携带对外的 {@link #wireName() 线上名称}，与 HTTP 查询参数取值一一对应
 * （{@code createdAt}、{@code updatedAt}、{@code priority}、{@code status}），
 * 解析<b>区分大小写</b>。</p>
 */
public enum TicketSortField {

    /** 按创建时间排序。 */
    CREATED_AT("createdAt"),

    /** 按最近更新时间排序。 */
    UPDATED_AT("updatedAt"),

    /** 按业务优先级排序（{@code P1} 最高）。 */
    PRIORITY("priority"),

    /** 按生命周期状态排序（{@code NEW} 最早）。 */
    STATUS("status");

    /** 未显式指定排序字段时使用的默认值。 */
    public static final TicketSortField DEFAULT = UPDATED_AT;

    private final String wireName;

    TicketSortField(String wireName) {
        this.wireName = wireName;
    }

    /**
     * @return 对外的线上名称，例如 {@code updatedAt}
     */
    public String wireName() {
        return this.wireName;
    }

    /**
     * 按线上名称解析。
     *
     * @param wireName 客户端文本；区分大小写
     * @return 匹配的常量；无法匹配时返回 {@link Optional#empty()}，绝不返回 {@code null}
     */
    public static Optional<TicketSortField> fromWire(String wireName) {
        if (wireName == null) {
            return Optional.empty();
        }
        for (TicketSortField field : values()) {
            if (field.wireName.equals(wireName)) {
                return Optional.of(field);
            }
        }
        return Optional.empty();
    }

    /**
     * @return 全部允许值的线上名称，按声明顺序，用于错误文案
     */
    public static String allowedWireNames() {
        return Stream.of(values()).map(TicketSortField::wireName).collect(Collectors.joining("、"));
    }
}
