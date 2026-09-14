package com.flowdesk.bootstrap.web;

/**
 * 版本前置条件的 ETag 表示与 {@code If-Match} 解析（各资源类型共用）。
 *
 * <p>工单与知识文档都用「资源版本 = ETag」的乐观并发协议，因此解析规则只应有<b>一份实现</b>：
 * 两个接口各自实现一遍，迟早会在「前导零算不算合法」这类细节上分叉。</p>
 *
 * <h2>接受的形式</h2>
 * <p>只接受<b>单个、强类型、规范十进制</b>的 ETag：{@code "0"}、{@code "1"}、{@code "25"}。
 * 解析前允许去除首尾空格。</p>
 *
 * <h2>拒绝的形式</h2>
 * <ul>
 *   <li>弱 ETag：{@code W/"0"}；</li>
 *   <li>通配符：{@code *}；</li>
 *   <li>多个 ETag：{@code "0", "1"}；</li>
 *   <li>未加引号：{@code 0}；</li>
 *   <li>含符号或非数字：{@code "-1"}、{@code "abc"}、{@code "1.0"}；</li>
 *   <li>前导零：{@code "01"}；</li>
 *   <li>超出 {@code long} 范围。</li>
 * </ul>
 *
 * <p>缺失与非法分别抛 {@link MissingIfMatchException}（→ 428）与
 * {@link InvalidIfMatchException}（→ 400 {@code INVALID_IF_MATCH}），
 * 由各自的异常处理器映射为统一错误契约。</p>
 */
public final class EntityTag {

    private static final char QUOTE = '"';

    private EntityTag() {
    }

    /**
     * @param version 版本号
     * @return 形如 {@code "3"} 的 ETag 头值
     */
    public static String format(long version) {
        return QUOTE + Long.toString(version) + QUOTE;
    }

    /**
     * 解析并校验 {@code If-Match}。
     *
     * @param ifMatchHeader 原始头值，{@code null} 表示头不存在
     * @return 解析出的版本号
     * @throws MissingIfMatchException 头不存在
     * @throws InvalidIfMatchException 头存在但格式非法
     */
    public static long requireVersion(String ifMatchHeader) {
        if (ifMatchHeader == null) {
            throw new MissingIfMatchException();
        }
        String raw = ifMatchHeader.strip();
        if (raw.length() < 2 || raw.charAt(0) != QUOTE || raw.charAt(raw.length() - 1) != QUOTE) {
            throw new InvalidIfMatchException();
        }
        String digits = raw.substring(1, raw.length() - 1);
        if (digits.isEmpty()) {
            throw new InvalidIfMatchException();
        }
        for (int index = 0; index < digits.length(); index++) {
            char character = digits.charAt(index);
            if (character < '0' || character > '9') {
                throw new InvalidIfMatchException();
            }
        }
        if (digits.length() > 1 && digits.charAt(0) == '0') {
            throw new InvalidIfMatchException();
        }
        try {
            return Long.parseLong(digits);
        }
        catch (NumberFormatException ex) {
            throw new InvalidIfMatchException();
        }
    }
}
