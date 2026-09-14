package com.flowdesk.infrastructure.knowledge.parsing;

/**
 * 按 <b>Unicode code point</b> 限量并累积文本的写入器。
 *
 * <p>解析路径上有两个地方会产出「最终提取文本」：SAX 收集器（PDF/DOCX）与文本解码循环
 * （Markdown/TXT）。两处对「计数」的要求完全一样，因此只实现<b>一份</b>：</p>
 * <ul>
 *   <li><b>所有写入都经过同一计数器</b>：普通字符、结构换行、跨回调的代理对，
 *       没有任何一条路径可以绕过它（FD-0009-R1 修复了两个漏计/误计的口子）；</li>
 *   <li><b>不变量</b>：任意时刻 {@code text().codePointCount(0, text().length()) <= maxCodePoints}；</li>
 *   <li><b>超限即中断</b>：在追加越界字符<b>之前</b>抛出，绝不先拼出完整文本再回头检查；</li>
 *   <li><b>代理对只计一次</b>：高代理落在上一个缓冲块末尾、低代理落在下一个缓冲块开头时，
 *       它仍然是一个 code point（跨 8192 char 缓冲区的边界也成立）；</li>
 *   <li><b>空回调不破坏状态</b>：长度为 0 的 {@code characters} 调用不得清掉待配对的高代理；</li>
 *   <li><b>只丢弃文本开头的 BOM</b>：首个 code point 若是 {@code U+FEFF}，它不进输出也不占配额；
 *       其它位置的 {@code U+FEFF} 是普通内容，正常计数。</li>
 * </ul>
 *
 * <p>本类非线程安全：每次解析各自创建一个实例。</p>
 */
final class CodePointLimitedTextBuilder {

    /** UTF-8 BOM 解码后得到的字符。 */
    private static final char BYTE_ORDER_MARK = '\uFEFF';

    private final int maxCodePoints;

    private final StringBuilder text = new StringBuilder();

    private int codePoints;

    /** 待配对的高代理；{@code 0} 表示没有。 */
    private char pendingHighSurrogate;

    /**
     * @param maxCodePoints 允许写入的最大 code point 数，必须大于 0
     */
    CodePointLimitedTextBuilder(int maxCodePoints) {
        if (maxCodePoints <= 0) {
            throw new IllegalArgumentException("maxCodePoints 必须大于 0");
        }
        this.maxCodePoints = maxCodePoints;
    }

    /**
     * 追加一段 UTF-16 字符（与 SAX {@code characters} / {@code Reader.read} 的入参语义一致）。
     *
     * @param chunk  字符缓冲
     * @param start  起始下标
     * @param length 长度，可以为 0（空回调必须是无副作用的）
     */
    void append(char[] chunk, int start, int length) {
        int index = start;
        int end = start + length;

        // 上一个缓冲块末尾留下的高代理：只有在这里才能判断它是否与低代理配对
        if (this.pendingHighSurrogate != 0 && index < end) {
            char pending = this.pendingHighSurrogate;
            this.pendingHighSurrogate = 0;
            if (Character.isLowSurrogate(chunk[index])) {
                // 配对成功：计数已经在收到高代理时完成，这里只把两个 char 写进结果
                this.text.append(pending).append(chunk[index]);
                index++;
            }
            else {
                // 未配对：它已经被计数，作为独立字符写进结果
                this.text.append(pending);
            }
        }

        while (index < end) {
            char current = chunk[index];

            if (current == BYTE_ORDER_MARK && this.codePoints == 0 && this.text.isEmpty()
                    && this.pendingHighSurrogate == 0) {
                // 仅文本开头的 BOM：不进输出，也不占配额
                index++;
                continue;
            }

            if (Character.isHighSurrogate(current)) {
                if (index + 1 < end && Character.isLowSurrogate(chunk[index + 1])) {
                    countOneBeforeAppending();
                    this.text.append(current).append(chunk[index + 1]);
                    index += 2;
                    continue;
                }
                // 缓冲区末尾的高代理：先计数（上限必须当场生效），暂存以便与下一块的低代理配对
                countOneBeforeAppending();
                this.pendingHighSurrogate = current;
                index++;
                continue;
            }

            countOneBeforeAppending();
            this.text.append(current);
            index++;
        }
    }

    /**
     * 追加一个结构换行（块级元素边界）。它同样进入最终输出，因此同样占用配额。
     *
     * @throws ExtractionLimitExceededException 超过上限
     */
    void appendStructuralNewline() {
        countOneBeforeAppending();
        this.text.append('\n');
    }

    /**
     * @return 已写入的 code point 数（不含尚未配对的悬空高代理之外的任何隐藏字符）
     */
    int codePointCount() {
        return this.codePoints;
    }

    /**
     * @return 累积的文本；会先把悬空高代理落进结果，因此返回值与计数始终自洽
     */
    String text() {
        if (this.pendingHighSurrogate != 0) {
            // 悬空高代理已经计过数，这里补进输出（重复调用是幂等的）
            this.text.append(this.pendingHighSurrogate);
            this.pendingHighSurrogate = 0;
        }
        return this.text.toString();
    }

    /**
     * @return 当前是否已经写过内容（用于「段落之间才补换行」的判断）
     */
    boolean isEmpty() {
        return this.text.isEmpty() && this.pendingHighSurrogate == 0;
    }

    /**
     * @return 当前输出的最后一个字符（没有内容时返回 {@code 0}）
     */
    char lastChar() {
        if (this.pendingHighSurrogate != 0) {
            return this.pendingHighSurrogate;
        }
        return this.text.isEmpty() ? 0 : this.text.charAt(this.text.length() - 1);
    }

    private void countOneBeforeAppending() {
        if (this.codePoints + 1 > this.maxCodePoints) {
            throw new ExtractionLimitExceededException(this.maxCodePoints);
        }
        this.codePoints++;
    }
}
