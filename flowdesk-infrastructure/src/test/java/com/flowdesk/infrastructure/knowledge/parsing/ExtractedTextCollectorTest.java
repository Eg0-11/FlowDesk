package com.flowdesk.infrastructure.knowledge.parsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * {@link ExtractedTextCollector} / {@link CodePointLimitedTextBuilder} 的限量语义测试（FD-0009-R1）。
 *
 * <p>FD-0009 里有两个漏计/误计的口子：结构换行没有计入配额、空回调会清掉待配对的高代理。
 * 这里逐个锁定修复后的行为，并断言全局不变量：
 * {@code text().codePointCount(0, text().length()) <= maxCodePoints}。</p>
 */
class ExtractedTextCollectorTest {

    private static final char HIGH_SURROGATE = '\uD83D';   // 📄 的前半

    private static final char LOW_SURROGATE = '\uDCF4';    // 📄 的后半

    private static final char BOM = '\uFEFF';

    // ---------- 结构换行 ----------

    @Test
    void structuralNewlineCountsAgainstTheLimit() throws Exception {
        ExtractedTextCollector collector = new ExtractedTextCollector(1);
        collector.characters(new char[] { 'A' }, 0, 1);

        assertThatThrownBy(() -> collector.startElement("", "p", "p", null))
                .as("块级元素之间补的换行也是输出的一部分，必须占用配额")
                .isInstanceOf(ExtractionLimitExceededException.class);

        assertThat(collector.text()).as("超限时不得把越界字符写进结果").isEqualTo("A");
    }

    @Test
    void structuralNewlineIsWrittenWhenThereIsRoom() throws Exception {
        ExtractedTextCollector collector = new ExtractedTextCollector(3);
        collector.characters(new char[] { 'A' }, 0, 1);
        collector.startElement("", "p", "p", null);
        collector.characters(new char[] { 'B' }, 0, 1);

        assertThat(collector.text()).isEqualTo("A\nB");
        assertThat(collector.codePointCount()).isEqualTo(3);
    }

    @Test
    void structuralNewlineIsNeitherPrependedNorDuplicated() throws Exception {
        ExtractedTextCollector collector = new ExtractedTextCollector(10);

        // 开头没有内容时不插换行
        collector.startElement("", "p", "p", null);
        assertThat(collector.text()).isEmpty();

        collector.characters(new char[] { 'A' }, 0, 1);
        // 连续两个块级元素只插一个换行
        collector.startElement("", "p", "p", null);
        collector.startElement("", "div", "div", null);
        assertThat(collector.text()).isEqualTo("A\n");

        // 已经以换行结尾时不重复插
        collector.characters(new char[] { '\n' }, 0, 1);
        collector.startElement("", "p", "p", null);
        assertThat(collector.text()).isEqualTo("A\n\n");

        collector.characters(new char[] { 'B' }, 0, 1);
        collector.startElement("", "p", "p", null);
        assertThat(collector.text()).isEqualTo("A\n\nB\n");
    }

    @Test
    void nonBlockElementsDoNotInsertNewlines() throws Exception {
        ExtractedTextCollector collector = new ExtractedTextCollector(10);
        collector.characters(new char[] { 'A' }, 0, 1);
        collector.startElement("", "span", "span", null);

        assertThat(collector.text()).isEqualTo("A");
    }

    // ---------- 所有写入路径都计数 ----------

    @Test
    void ignorableWhitespaceAlsoCounts() {
        ExtractedTextCollector collector = new ExtractedTextCollector(1);
        collector.ignorableWhitespace(new char[] { ' ' }, 0, 1);

        assertThat(collector.codePointCount()).isEqualTo(1);
        assertThatThrownBy(() -> collector.characters(new char[] { 'A' }, 0, 1))
                .isInstanceOf(ExtractionLimitExceededException.class);
    }

    @Test
    void limitIsEnforcedBeforeAppendingTheOverLimitCharacter() {
        ExtractedTextCollector collector = new ExtractedTextCollector(3);

        assertThatThrownBy(() -> collector.characters(new char[] { 'A', 'B', 'C', 'D' }, 0, 4))
                .isInstanceOf(ExtractionLimitExceededException.class);

        assertThat(collector.text()).isEqualTo("ABC");
        assertThat(collector.codePointCount()).isLessThanOrEqualTo(3);
    }

    @Test
    void theOutputInvariantHoldsForLongMixedInput() {
        ExtractedTextCollector collector = new ExtractedTextCollector(50);
        StringBuilder chunk = new StringBuilder();
        for (int index = 0; index < 40; index++) {
            chunk.append("中文").append(HIGH_SURROGATE).append(LOW_SURROGATE).append('A');
        }
        char[] characters = chunk.toString().toCharArray();

        assertThatThrownBy(() -> collector.characters(characters, 0, characters.length))
                .isInstanceOf(ExtractionLimitExceededException.class);

        String text = collector.text();
        assertThat(text.codePointCount(0, text.length()))
                .as("无论从哪条路径写入，最终输出的 code point 数都不得超过上限")
                .isLessThanOrEqualTo(50);
    }

    // ---------- 代理对 ----------

    @Test
    void supplementaryCodePointSplitAcrossCallbacksCountsOnce() {
        ExtractedTextCollector collector = new ExtractedTextCollector(1);
        collector.characters(new char[] { HIGH_SURROGATE }, 0, 1);
        collector.characters(new char[] { LOW_SURROGATE }, 0, 1);

        assertThat(collector.codePointCount()).as("跨回调的代理对只算一个 code point").isEqualTo(1);
        assertThat(collector.text()).isEqualTo(new String(new char[] { HIGH_SURROGATE, LOW_SURROGATE }));
    }

    @Test
    void emptyCallbackDoesNotBreakPendingSurrogateState() {
        ExtractedTextCollector collector = new ExtractedTextCollector(1);
        collector.characters(new char[] { HIGH_SURROGATE }, 0, 1);
        collector.characters(new char[0], 0, 0);
        collector.characters(new char[] { LOW_SURROGATE }, 0, 1);

        assertThat(collector.codePointCount()).as("空回调不得把待配对的高代理丢掉").isEqualTo(1);
        assertThat(collector.text()).hasSize(2);
        assertThat(collector.text().codePointCount(0, collector.text().length())).isEqualTo(1);
    }

    @Test
    void aDanglingHighSurrogateIsStillCountedAndKept() {
        ExtractedTextCollector collector = new ExtractedTextCollector(2);
        collector.characters(new char[] { 'A' }, 0, 1);
        collector.characters(new char[] { HIGH_SURROGATE }, 0, 1);

        assertThat(collector.codePointCount()).isEqualTo(2);
        assertThat(collector.text()).hasSize(2);
    }

    @Test
    void completeSurrogatePairWithNoRoomLeftFails() {
        ExtractedTextCollector collector = new ExtractedTextCollector(1);
        collector.characters(new char[] { 'A' }, 0, 1);

        assertThatThrownBy(() -> collector.characters(new char[] { HIGH_SURROGATE, LOW_SURROGATE }, 0, 2))
                .isInstanceOf(ExtractionLimitExceededException.class);
        assertThat(collector.text()).isEqualTo("A");
    }

    // ---------- BOM ----------

    @Test
    void leadingByteOrderMarkIsDroppedAndDoesNotConsumeQuota() {
        ExtractedTextCollector collector = new ExtractedTextCollector(3);
        collector.characters(new char[] { BOM, 'A', 'B', 'C' }, 0, 4);

        assertThat(collector.text()).isEqualTo("ABC");
        assertThat(collector.codePointCount()).isEqualTo(3);
    }

    @Test
    void aByteOrderMarkOnItsOwnProducesNothing() {
        ExtractedTextCollector collector = new ExtractedTextCollector(1);
        collector.characters(new char[] { BOM }, 0, 1);

        assertThat(collector.text()).isEmpty();
        assertThat(collector.codePointCount()).isZero();
    }

    @Test
    void aNonLeadingByteOrderMarkCountsAsContent() {
        ExtractedTextCollector collector = new ExtractedTextCollector(3);
        collector.characters(new char[] { 'A', BOM, BOM }, 0, 3);

        // 只有「第一个 code point 位置」的 BOM 被丢弃，其余按普通内容计数
        assertThat(collector.text()).isEqualTo("A\uFEFF\uFEFF");
        assertThat(collector.codePointCount()).isEqualTo(3);
        assertThatThrownBy(() -> collector.characters(new char[] { BOM }, 0, 1))
                .isInstanceOf(ExtractionLimitExceededException.class);
    }

    @Test
    void aByteOrderMarkSplitAcrossCallbacksIsHandledLikeAnyOtherCharacter() {
        ExtractedTextCollector collector = new ExtractedTextCollector(2);
        collector.characters(new char[] { BOM }, 0, 1);
        collector.characters(new char[] { 'A' }, 0, 1);
        collector.characters(new char[] { BOM }, 0, 1);

        assertThat(collector.text()).isEqualTo("A\uFEFF");
    }

    // ---------- 构造参数 ----------

    @Test
    void rejectsNonPositiveLimits() {
        assertThatThrownBy(() -> new ExtractedTextCollector(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExtractedTextCollector(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new ExtractedTextCollector(1)).doesNotThrowAnyException();
    }

    @Test
    void emptyCollectorProducesAnEmptyString() {
        assertThat(new ExtractedTextCollector(5).text()).isEmpty();
    }
}
