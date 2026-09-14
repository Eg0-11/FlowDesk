package com.flowdesk.infrastructure.knowledge.parsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * {@link CodePointLimitedTextBuilder} 的状态语义测试（FD-0009-R2）。
 *
 * <p>这个写入器是「提取上限」的唯一实现点，因此它的状态机必须逐条锁定：</p>
 * <ul>
 *   <li><b>首字符状态</b>：只有输入流的<b>第一个</b> code point 才可能是被丢弃的 BOM，
 *       第二个 {@code U+FEFF} 就是普通内容；空回调不消耗该状态，结构换行会结束它；</li>
 *   <li><b>写入顺序</b>：UTF-16 序列与输入顺序一致（先落高代理，再写结构换行，最后是低代理）；</li>
 *   <li><b>{@code text()} 无副作用</b>：不改变待配对状态、可重复调用；</li>
 *   <li><b>既有行为不变</b>：跨回调代理对只计一次、空回调不破坏 pending、超限先抛后写、
 *       所有写入路径（普通字符/ignorableWhitespace/结构换行）都计入配额。</li>
 * </ul>
 */
class CodePointLimitedTextBuilderTest {

    private static final char HIGH_SURROGATE = '\uD83D';   // 📄 的前半

    private static final char LOW_SURROGATE = '\uDCF4';    // 📄 的后半

    private static final char BOM = '\uFEFF';

    // ---------- 一、首字符（BOM）状态 ----------

    @Test
    void onlyTheVeryFirstCodePointIsTreatedAsABom() {
        CodePointLimitedTextBuilder builder = new CodePointLimitedTextBuilder(10);
        builder.append(new char[] { BOM, BOM, 'A' }, 0, 3);

        // 第一个 BOM 被丢弃；第二个 BOM 是普通内容
        assertThat(builder.text()).isEqualTo("\uFEFFA");
        assertThat(builder.codePointCount()).isEqualTo(2);
    }

    @Test
    void doubleBomOverTheLimitFailsBeforeWritingTheNextCharacter() {
        CodePointLimitedTextBuilder builder = new CodePointLimitedTextBuilder(1);

        assertThatThrownBy(() -> builder.append(new char[] { BOM, BOM, 'A' }, 0, 3))
                .as("第二个 BOM 已用掉唯一配额，写 A 之前必须抛出")
                .isInstanceOf(ExtractionLimitExceededException.class);

        assertThat(builder.text()).isEqualTo("\uFEFF");
        assertThat(builder.codePointCount()).isEqualTo(1);
    }

    @Test
    void anEmptyCallbackDoesNotConsumeTheLeadingState() {
        CodePointLimitedTextBuilder builder = new CodePointLimitedTextBuilder(5);
        builder.append(new char[0], 0, 0);
        builder.append(new char[] { BOM, 'A' }, 0, 2);

        assertThat(builder.text()).as("空回调之后，BOM 仍然是输入流的第一个 code point")
                .isEqualTo("A");
    }

    @Test
    void aStructuralNewlineEndsTheLeadingState() {
        CodePointLimitedTextBuilder builder = new CodePointLimitedTextBuilder(5);
        builder.appendStructuralNewline();
        builder.append(new char[] { BOM, 'A' }, 0, 2);

        assertThat(builder.text()).as("结构换行已是第一个实际输入，之后的 BOM 必须按普通内容计数")
                .isEqualTo("\n\uFEFFA");
        assertThat(builder.codePointCount()).isEqualTo(3);
    }

    @Test
    void aByteOrderMarkAfterOrdinaryTextIsContent() {
        CodePointLimitedTextBuilder builder = new CodePointLimitedTextBuilder(5);
        builder.append(new char[] { 'A' }, 0, 1);
        builder.append(new char[] { BOM }, 0, 1);

        assertThat(builder.text()).isEqualTo("A\uFEFF");
    }

    @Test
    void aByteOrderMarkOnlyIsDroppedEntirely() {
        CodePointLimitedTextBuilder builder = new CodePointLimitedTextBuilder(3);
        builder.append(new char[] { BOM }, 0, 1);

        assertThat(builder.text()).isEmpty();
        assertThat(builder.codePointCount()).isZero();
    }

    // ---------- 二、写入顺序 ----------

    @Test
    void aStructuralNewlineFlushesThePendingHighSurrogateFirst() {
        CodePointLimitedTextBuilder builder = new CodePointLimitedTextBuilder(5);
        builder.append(new char[] { HIGH_SURROGATE }, 0, 1);

        builder.appendStructuralNewline();

        assertThat(builder.text())
                .as("高代理必须先于结构换行落地，否则 UTF-16 顺序被重排")
                .isEqualTo(new String(new char[] { HIGH_SURROGATE, '\n' }));
        assertThat(builder.codePointCount()).isEqualTo(2);
        assertThat(builder.text().codePointCount(0, builder.text().length())).isEqualTo(2);
    }

    @Test
    void highSurrogateNewlineLowSurrogateKeepsInputOrderAndCount() {
        CodePointLimitedTextBuilder builder = new CodePointLimitedTextBuilder(5);
        builder.append(new char[] { HIGH_SURROGATE }, 0, 1);
        builder.appendStructuralNewline();
        builder.append(new char[] { LOW_SURROGATE }, 0, 1);

        String text = builder.text();
        assertThat(text).isEqualTo(new String(new char[] { HIGH_SURROGATE, '\n', LOW_SURROGATE }));
        assertThat(builder.codePointCount()).as("3 个 code point：高代理、换行、低代理各一个").isEqualTo(3);
        assertThat(text.codePointCount(0, text.length())).isEqualTo(3);
    }

    @Test
    void aPairSplitAcrossCallbacksStillCountsOnce() {
        CodePointLimitedTextBuilder builder = new CodePointLimitedTextBuilder(1);
        builder.append(new char[] { HIGH_SURROGATE }, 0, 1);
        builder.append(new char[] { LOW_SURROGATE }, 0, 1);

        assertThat(builder.codePointCount()).isEqualTo(1);
        assertThat(builder.text()).hasSize(2);
        assertThat(builder.text().codePointCount(0, builder.text().length())).isEqualTo(1);
    }

    @Test
    void anEmptyCallbackDoesNotBreakThePendingSurrogate() {
        CodePointLimitedTextBuilder builder = new CodePointLimitedTextBuilder(1);
        builder.append(new char[] { HIGH_SURROGATE }, 0, 1);
        builder.append(new char[0], 0, 0);
        builder.append(new char[] { LOW_SURROGATE }, 0, 1);

        assertThat(builder.codePointCount()).isEqualTo(1);
        assertThat(builder.text()).hasSize(2);
    }

    @Test
    void anUnpairedHighSurrogateAtTheEndStaysInTheOutput() {
        CodePointLimitedTextBuilder builder = new CodePointLimitedTextBuilder(2);
        builder.append(new char[] { 'A' }, 0, 1);
        builder.append(new char[] { HIGH_SURROGATE }, 0, 1);

        assertThat(builder.text()).hasSize(2);
        assertThat(builder.codePointCount()).isEqualTo(2);
    }

    // ---------- 三、text() 是无副作用的观察 ----------

    @Test
    void textDoesNotResolveAPendingSurrogate() {
        CodePointLimitedTextBuilder builder = new CodePointLimitedTextBuilder(1);
        builder.append(new char[] { HIGH_SURROGATE }, 0, 1);

        assertThat(builder.text()).as("观察时把高代理一并返回，但状态不变")
                .isEqualTo(String.valueOf(HIGH_SURROGATE));

        builder.append(new char[] { LOW_SURROGATE }, 0, 1);

        assertThat(builder.codePointCount()).as("text() 之后低代理仍应与高代理组成一个 code point")
                .isEqualTo(1);
        assertThat(builder.text()).hasSize(2);
        assertThat(builder.text().codePointCount(0, builder.text().length())).isEqualTo(1);
    }

    @Test
    void repeatedTextCallsAreIdempotent() {
        CodePointLimitedTextBuilder builder = new CodePointLimitedTextBuilder(5);
        builder.append(new char[] { 'A', HIGH_SURROGATE }, 0, 2);

        String first = builder.text();
        String second = builder.text();
        String third = builder.text();

        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
        assertThat(builder.codePointCount()).isEqualTo(2);

        // 观察之后继续写入，结果仍然正确
        builder.append(new char[] { LOW_SURROGATE, 'B' }, 0, 2);
        assertThat(builder.text()).isEqualTo("A" + HIGH_SURROGATE + LOW_SURROGATE + "B");
        assertThat(builder.codePointCount()).isEqualTo(3);
    }

    @Test
    void isEmptyAndLastCharAccountForThePendingSurrogate() {
        CodePointLimitedTextBuilder builder = new CodePointLimitedTextBuilder(5);
        assertThat(builder.isEmpty()).isTrue();
        assertThat(builder.lastChar()).isEqualTo((char) 0);

        builder.append(new char[] { HIGH_SURROGATE }, 0, 1);

        assertThat(builder.isEmpty()).isFalse();
        assertThat(builder.lastChar()).isEqualTo(HIGH_SURROGATE);
    }

    // ---------- 四、保留既有行为 ----------

    @Test
    void theLimitIsCheckedBeforeAppending() {
        CodePointLimitedTextBuilder builder = new CodePointLimitedTextBuilder(3);

        assertThatThrownBy(() -> builder.append(new char[] { 'A', 'B', 'C', 'D' }, 0, 4))
                .isInstanceOf(ExtractionLimitExceededException.class);

        assertThat(builder.text()).isEqualTo("ABC");
        assertThat(builder.codePointCount()).isEqualTo(3);
    }

    @Test
    void aCompleteSurrogatePairWithNoRoomLeftFails() {
        CodePointLimitedTextBuilder builder = new CodePointLimitedTextBuilder(1);
        builder.append(new char[] { 'A' }, 0, 1);

        assertThatThrownBy(() -> builder.append(new char[] { HIGH_SURROGATE, LOW_SURROGATE }, 0, 2))
                .isInstanceOf(ExtractionLimitExceededException.class);
        assertThat(builder.text()).isEqualTo("A");
    }

    @Test
    void theOutputInvariantHoldsForLongMixedInput() {
        CodePointLimitedTextBuilder builder = new CodePointLimitedTextBuilder(40);
        StringBuilder chunk = new StringBuilder();
        for (int index = 0; index < 30; index++) {
            chunk.append("中文").append(HIGH_SURROGATE).append(LOW_SURROGATE).append('A');
        }
        char[] characters = chunk.toString().toCharArray();

        assertThatThrownBy(() -> builder.append(characters, 0, characters.length))
                .isInstanceOf(ExtractionLimitExceededException.class);

        String text = builder.text();
        assertThat(text.codePointCount(0, text.length())).isLessThanOrEqualTo(40);
        assertThat(builder.codePointCount()).isLessThanOrEqualTo(40);
    }

    @Test
    void rejectsNonPositiveLimits() {
        assertThatThrownBy(() -> new CodePointLimitedTextBuilder(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CodePointLimitedTextBuilder(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
