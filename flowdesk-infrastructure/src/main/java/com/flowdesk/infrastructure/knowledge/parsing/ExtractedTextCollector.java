package com.flowdesk.infrastructure.knowledge.parsing;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * 按 <b>Unicode code point</b> 限量收集解析出的文本。
 *
 * <p>为什么不能用 Tika 自带的 {@code WriteOutContentHandler}：它按 {@code char}（UTF-16 单元）
 * 计数，而本项目的上限以 code point 计 —— 一个 emoji 会被算成 2，含大量 emoji 的文档会被误判超限。</p>
 *
 * <p>超限时抛 {@link ExtractionLimitExceededException}（非受检）直接中断解析：
 * 这是「立即终止」的实现方式 —— <b>不允许</b>先把文本无限拼出来再回头检查长度。</p>
 *
 * <p>代理对跨 {@code characters} 回调被切断的情况也要处理：高代理留在缓冲区末尾、
 * 低代理在下一次回调开头，必须只算<b>一个</b> code point 且拼回原样。</p>
 */
final class ExtractedTextCollector extends DefaultHandler {

    private final int maxCodePoints;

    private final StringBuilder text = new StringBuilder();

    private int codePoints;

    private boolean pendingHighSurrogate;

    ExtractedTextCollector(int maxCodePoints) {
        this.maxCodePoints = maxCodePoints;
    }

    @Override
    public void characters(char[] chunk, int start, int length) {
        int index = start;
        int end = start + length;

        if (this.pendingHighSurrogate) {
            this.pendingHighSurrogate = false;
            if (index < end && Character.isLowSurrogate(chunk[index])) {
                // 与上一个高代理组成一个 code point：那一轮已经计过数，这里只补回字符
                this.text.append(chunk[index]);
                index++;
            }
        }

        while (index < end) {
            char current = chunk[index];
            if (Character.isHighSurrogate(current) && index + 1 < end
                    && Character.isLowSurrogate(chunk[index + 1])) {
                countOne();
                this.text.append(current).append(chunk[index + 1]);
                index += 2;
                continue;
            }
            if (Character.isHighSurrogate(current) && index + 1 == end) {
                countOne();
                this.text.append(current);
                this.pendingHighSurrogate = true;
                index++;
                continue;
            }
            countOne();
            this.text.append(current);
            index++;
        }
    }

    @Override
    public void ignorableWhitespace(char[] chunk, int start, int length) {
        characters(chunk, start, length);
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes)
            throws SAXException {

        // 块级元素之间补一个换行：否则 PDF/Word 的段落会被拼成一整行，段落边界信息就丢了
        if (!this.text.isEmpty() && !endsWithNewline() && isBlockElement(localName, qName)) {
            this.text.append('\n');
        }
    }

    private boolean endsWithNewline() {
        return this.text.length() > 0 && this.text.charAt(this.text.length() - 1) == '\n';
    }

    private static boolean isBlockElement(String localName, String qName) {
        String name = localName == null || localName.isEmpty() ? qName : localName;
        if (name == null || name.isEmpty()) {
            return false;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("p") || lower.equals("div") || lower.equals("br") || lower.equals("li")
                || lower.equals("tr") || lower.equals("h1") || lower.equals("h2") || lower.equals("h3")
                || lower.equals("h4") || lower.equals("h5") || lower.equals("h6");
    }

    private void countOne() {
        this.codePoints++;
        if (this.codePoints > this.maxCodePoints) {
            throw new ExtractionLimitExceededException(this.maxCodePoints);
        }
    }

    /**
     * @return 收集到的文本
     */
    String text() {
        return this.text.toString();
    }

    /**
     * 提取文本超过配置上限。
     */
    static final class ExtractionLimitExceededException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        ExtractionLimitExceededException(int maxCodePoints) {
            super("提取文本超过上限 " + maxCodePoints + " code points");
        }
    }
}
