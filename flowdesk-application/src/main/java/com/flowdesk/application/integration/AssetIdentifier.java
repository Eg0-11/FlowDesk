package com.flowdesk.application.integration;

import java.util.regex.Pattern;

/**
 * 资产标识的输入契约（FD-0016）。
 *
 * <p>格式与两个 MCP 服务已公布的契约完全一致：{@code AST-} 加<b>恰好六位</b>十进制数字。
 * 判定是<b>逐字符</b>的：不做 trim、不做大小写归一，长度上限先于正则判断
 * （避免把任意长字符串丢给正则引擎）。</p>
 *
 * <p>这里的规则是<b>独立实现</b>的一份：应用层不允许引用两个 MCP 服务模块的类型，
 * 因此同一份契约在两处各写一次，靠测试而不是靠共享类来保证一致
 * （见 {@code docs/adr/0013-main-service-mcp-client.md}）。</p>
 */
public final class AssetIdentifier {

    /** 数字位数。 */
    public static final int DIGITS = 6;

    /** 合法长度：{@code AST-} + 六位数字。 */
    public static final int LENGTH = 4 + DIGITS;

    private static final Pattern PATTERN = Pattern.compile("AST-[0-9]{" + DIGITS + "}");

    private AssetIdentifier() {
    }

    /**
     * @param raw 原始输入（可以是 {@code null}）
     * @return 是否恰好形如 {@code AST-123456}
     */
    public static boolean isValid(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > LENGTH) {
            return false;
        }
        return PATTERN.matcher(raw).matches();
    }
}
