package com.flowdesk.infrastructure.ticket.persistence.jdbc;

/**
 * 关键字搜索的 LIKE 模式构造：把用户输入的<b>普通文本</b>安全地变成一个「包含匹配」模式。
 *
 * <p>问题：{@code LIKE} 的 {@code %} 与 {@code _} 是通配符。若直接把用户输入拼进模式，
 * 搜索 {@code "100%"} 会变成「以 100 开头」而不是「包含字符串 100%」，
 * 搜索 {@code "_"} 更会匹配到任意单字符的标题 —— 用户看到的是「搜什么都没筛掉」。
 * 这不是注入（参数仍然是绑定的），但它确实让<b>语义</b>被用户输入改变了。</p>
 *
 * <p>做法：选定 {@code !} 作为转义符，并在 SQL 里显式声明 {@code ESCAPE '!'}。
 * 转义时先处理转义符本身，再处理两个通配符，保证下列三条同时成立：</p>
 * <ul>
 *   <li>{@code %} → 普通百分号；</li>
 *   <li>{@code _} → 普通下划线；</li>
 *   <li>{@code !} → 普通感叹号（自身被写成 {@code !!}，不会被误当作转义前缀）。</li>
 * </ul>
 *
 * <p>例：输入 {@code "50%!_off"} 得到 {@code %50!%!!_off%}，即「包含 50%!_off 这个字面量」。</p>
 *
 * <p>{@code ESCAPE '!'} 是标准 SQL，PostgreSQL 与 H2（PostgreSQL 兼容模式）都支持。</p>
 */
final class LikePattern {

    /** 与 SQL 中 {@code ESCAPE '!'} 声明保持一致的转义符。 */
    static final char ESCAPE_CHARACTER = '!';

    /** SQL 片段里使用的转义声明，必须与 {@link #ESCAPE_CHARACTER} 一致。 */
    static final String ESCAPE_CLAUSE = " ESCAPE '!'";

    private LikePattern() {
    }

    /**
     * 构造「包含」模式：{@code %转义后的字面量%}。
     *
     * @param literal 用户输入的字面文本，不得为 {@code null}
     * @return 可直接绑定到 {@code LIKE ?} 的模式串
     */
    static String contains(String literal) {
        return "%" + escape(literal) + "%";
    }

    /**
     * 转义字面量中的 {@code !}、{@code %} 与 {@code _}。
     *
     * @param literal 用户输入的字面文本，不得为 {@code null}
     * @return 转义后的文本
     */
    static String escape(String literal) {
        StringBuilder escaped = new StringBuilder(literal.length() + 8);
        for (int index = 0; index < literal.length(); index++) {
            char character = literal.charAt(index);
            if (character == ESCAPE_CHARACTER || character == '%' || character == '_') {
                escaped.append(ESCAPE_CHARACTER);
            }
            escaped.append(character);
        }
        return escaped.toString();
    }
}
