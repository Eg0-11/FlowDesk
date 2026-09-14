package com.flowdesk.infrastructure.knowledge.parsing;

/**
 * 按 <b>Unicode code point</b> 限量并累积文本的写入器。
 *
 * <p>解析路径上有两个地方会产出「最终提取文本」：SAX 收集器（PDF/DOCX）与文本解码循环
 * （Markdown/TXT）。两处对「计数」的要求完全一样，因此只实现<b>一份</b>：</p>
 * <ul>
 *   <li><b>所有写入都经过同一计数器</b>：普通字符、结构换行、跨回调的代理对，
 *       没有任何一条路径可以绕过它；</li>
 *   <li><b>不变量</b>：{@code text().codePointCount(0, text().length()) <= maxCodePoints}；</li>
 *   <li><b>超限即中断</b>：在追加越界字符<b>之前</b>抛出，绝不先拼出完整文本再回头检查；</li>
 *   <li><b>保持写入顺序</b>：UTF-16 序列与输入顺序完全一致（先落高代理，再写结构换行，
 *       最后才是低代理），因此计数与最终字符串始终自洽；</li>
 *   <li><b>代理对只计一次</b>：高代理落在上一个缓冲块末尾、低代理落在下一个缓冲块开头时，
 *       它仍然是一个 code point（跨 8192 char 缓冲区的边界也成立）；</li>
 *   <li><b>空回调不破坏状态</b>：长度为 0 的 {@code characters} 调用不得清掉待配对的高代理，
 *       也不得消耗「首字符尚未判断」的状态；</li>
 *   <li><b>只有输入流的第一个 code point 才可能是 BOM</b>：它是 {@code U+FEFF} 时丢弃且不占配额；
 *       第二个 {@code U+FEFF} 就是普通内容，正常计数；</li>
 *   <li><b>{@link #text()} 是无副作用的观察</b>：它不改变待配对状态，可重复调用，
 *       且不会把「尚未配对的低代理」提前排除在外。</li>
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
     * 「输入流的第一个 code point 已经处理过」。
     *
     * <p>它必须是一个显式状态：空回调不会消耗它，而结构换行会结束它。
     * 不能用「计数为 0 且文本为空」来代替 —— 那样 {@code BOM + BOM + A}
     * 会把两个 BOM 都当成开头 BOM 丢掉。</p>
     */
    private boolean leadingCodePointResolved;

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

        // 空回调到这里就结束了：既不消耗「首字符尚未判断」的状态，也不动待配对高代理
        if (index >= end) {
            return;
        }

        // 本次调用里的第一个待处理字符是否就是「输入流的第一个 code point」
        boolean leading = !this.leadingCodePointResolved;
        this.leadingCodePointResolved = true;

        while (index < end) {
            char current = chunk[index];

            if (leading) {
                leading = false;
                if (current == BYTE_ORDER_MARK) {
                    // 只有输入流的第一个 code point 可能是 BOM：丢弃且不占配额
                    index++;
                    continue;
                }
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
     * <p>写入前先把待配对的高代理落进结果：输入里的顺序是「高代理 → 换行 → 低代理」，
     * UTF-16 输出必须保持同样的顺序，否则计数与字符串会对不上。</p>
     *
     * @throws ExtractionLimitExceededException 超过上限
     */
    void appendStructuralNewline() {
        // 结构换行也是一个「实际输入」：它会结束「首字符尚未判断」的状态
        this.leadingCodePointResolved = true;
        flushPendingHighSurrogate();
        countOneBeforeAppending();
        this.text.append('\n');
    }

    /**
     * @return 已写入的 code point 数（与 {@link #text()} 的结果一致）
     */
    int codePointCount() {
        return this.codePoints;
    }

    /**
     * 观察当前文本；<b>不改变任何状态</b>。
     *
     * <p>待配对的高代理会作为字符串尾部一并返回（它已经被计数、也必然属于最终输出），
     * 但不会因此被「落定」：随后的低代理仍能与它组成一个 supplementary code point。
     * 因此本方法可以安全地重复调用。</p>
     *
     * @return 当前的文本
     */
    String text() {
        if (this.pendingHighSurrogate == 0) {
            return this.text.toString();
        }
        return this.text.toString() + this.pendingHighSurrogate;
    }

    /**
     * @return 当前是否还没有任何输出（用于「段落之间才补换行」的判断）
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

    /**
     * 把待配对的高代理落进结果（它已经被计数）。
     */
    private void flushPendingHighSurrogate() {
        if (this.pendingHighSurrogate != 0) {
            this.text.append(this.pendingHighSurrogate);
            this.pendingHighSurrogate = 0;
        }
    }

    private void countOneBeforeAppending() {
        if (this.codePoints + 1 > this.maxCodePoints) {
            throw new ExtractionLimitExceededException(this.maxCodePoints);
        }
        this.codePoints++;
    }
}
