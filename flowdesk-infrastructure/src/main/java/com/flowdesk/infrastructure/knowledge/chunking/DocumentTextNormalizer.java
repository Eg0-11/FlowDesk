package com.flowdesk.infrastructure.knowledge.chunking;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * 文本规范化：把解析器提取出的文本变成<b>稳定、可复现</b>的形态。
 *
 * <p>规则（顺序固定，因此结果确定）：</p>
 * <ol>
 *   <li>换行统一：{@code \r\n} 与 {@code \r} 都变成 {@code \n}；</li>
 *   <li>Unicode 规范化：{@link Normalizer.Form#NFC} —— 让「同一个字符的不同组合方式」
 *       收敛成同一种字节表示，否则同一份内容在不同来源下会切出不同的切片；</li>
 *   <li>合并无意义的连续空行：连续 3 个及以上换行压成 2 个（保留段落分隔，但不会把段落压成一行）；</li>
 *   <li>去掉整段文本的首尾空白。</li>
 * </ol>
 *
 * <p>刻意<b>不</b>做「所有空白压成一个空格」这类处理：那会把段落结构抹掉，
 * 而段落边界正是切片优先级里最高的一档。</p>
 */
public final class DocumentTextNormalizer {

    /** 连续 3 个及以上换行（含中间的空格/Tab）。 */
    private static final Pattern REDUNDANT_BLANK_LINES = Pattern.compile("\n[ \t]*\n[ \t]*\n+");

    private DocumentTextNormalizer() {
    }

    /**
     * 规范化文本。
     *
     * @param raw 原始提取文本，可为 {@code null}
     * @return 规范化后的文本；{@code null} 与空白输入返回空串
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String unified = raw.replace("\r\n", "\n").replace('\r', '\n');
        String composed = Normalizer.normalize(unified, Normalizer.Form.NFC);
        String collapsed = REDUNDANT_BLANK_LINES.matcher(composed).replaceAll("\n\n");
        return collapsed.strip();
    }
}
