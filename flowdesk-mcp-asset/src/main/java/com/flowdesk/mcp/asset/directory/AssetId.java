package com.flowdesk.mcp.asset.directory;

import java.util.regex.Pattern;

/**
 * 资产标识的输入契约（FD-0014）。
 *
 * <p>格式固定为 {@code AST-[0-9]{6}}：大写前缀 {@code AST-} 加<b>恰好六位</b>十进制数字。
 * 判定是<b>逐字符</b>的：</p>
 * <ul>
 *   <li>不做 {@code trim}、不做大小写归一 —— {@code " ast-000123 "} 与 {@code AST-00012A}
 *       都不合法，而不是被「修好」再用；</li>
 *   <li>{@code null}、空串、纯空白、超长（&gt; {@value #MAX_LENGTH}）一律不合法；</li>
 *   <li>长度上限先于正则判断，避免把任意长字符串丢给正则引擎。</li>
 * </ul>
 *
 * <p>这里<b>只</b>表达「输入是否合法」；「合法但不存在」由数据源回答（见 {@link AssetDirectory}），
 * 两者在工具层被映射成不同的稳定错误码。</p>
 */
public final class AssetId {

    /** 合法资产标识的形式。 */
    public static final Pattern PATTERN = Pattern.compile("AST-[0-9]{6}");

    /**
     * 对外公布的 JSON Schema {@code pattern}（FD-0014-R1）。
     *
     * <p>{@link #PATTERN} 是未锚定的正则，而 {@link #isValid(String)} 用的是整串匹配
     * （{@code Matcher.matches()}）。JSON Schema 的 {@code pattern} 是「部分匹配」语义，
     * 因此公布时必须显式加上 {@code ^} / {@code $} —— 否则 schema 会比执行校验更宽松，
     * 出现「文档说可以、运行期拒绝」的不一致。</p>
     */
    public static final String SCHEMA_PATTERN = "^" + "AST-[0-9]{6}" + "$";

    /** 合法长度：{@code AST-} + 六位数字。 */
    public static final int MAX_LENGTH = 10;

    private AssetId() {
    }

    /**
     * 判断原始输入是否为合法资产标识。
     *
     * @param raw 工具调用方传入的原始值
     * @return 是否恰好形如 {@code AST-123456}
     */
    public static boolean isValid(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > MAX_LENGTH) {
            return false;
        }
        return PATTERN.matcher(raw).matches();
    }
}
