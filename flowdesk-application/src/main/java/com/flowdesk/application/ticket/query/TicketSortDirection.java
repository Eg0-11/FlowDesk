package com.flowdesk.application.ticket.query;

import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 列表排序方向的白名单。
 *
 * <p>与 {@link TicketSortField} 同理：客户端的 {@code asc}/{@code desc} 文本必须先被解析成本枚举，
 * 存储层再据此生成固定的 SQL 关键字，绝不接受原文拼接。解析<b>区分大小写</b>。</p>
 */
public enum TicketSortDirection {

    /** 升序。 */
    ASC("asc"),

    /** 降序。 */
    DESC("desc");

    /** 未显式指定排序方向时使用的默认值。 */
    public static final TicketSortDirection DEFAULT = DESC;

    private final String wireName;

    TicketSortDirection(String wireName) {
        this.wireName = wireName;
    }

    /**
     * @return 对外的线上名称，即 {@code asc} 或 {@code desc}
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
    public static Optional<TicketSortDirection> fromWire(String wireName) {
        if (wireName == null) {
            return Optional.empty();
        }
        for (TicketSortDirection direction : values()) {
            if (direction.wireName.equals(wireName)) {
                return Optional.of(direction);
            }
        }
        return Optional.empty();
    }

    /**
     * @return 全部允许值的线上名称，按声明顺序，用于错误文案
     */
    public static String allowedWireNames() {
        return Stream.of(values()).map(TicketSortDirection::wireName).collect(Collectors.joining("、"));
    }
}
