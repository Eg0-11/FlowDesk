package com.flowdesk.infrastructure.knowledge.parsing;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * 按 <b>Unicode code point</b> 限量收集解析出的文本（PDF/DOCX 路径）。
 *
 * <p>为什么不能用 Tika 自带的 {@code WriteOutContentHandler}：它按 {@code char}（UTF-16 单元）
 * 计数，而本项目的上限以 code point 计 —— 一个 emoji 会被算成 2，含大量 emoji 的文档会被误判超限。</p>
 *
 * <p>实际计数与代理对处理全在 {@link CodePointLimitedTextBuilder} 里，本类只负责把 SAX 事件
 * 翻译成「写入字符」与「写入结构换行」两种操作：<b>每一个进入最终文本的字符都必须经过
 * 那个写入器</b>，包括块级元素之间自动补的换行（FD-0009-R1 之前这里漏计过）。</p>
 *
 * <p>超限时抛 {@link ExtractionLimitExceededException}（非受检）直接中断解析：
 * 这是「立即终止」的实现方式 —— <b>不允许</b>先把文本无限拼出来再回头检查长度。</p>
 */
final class ExtractedTextCollector extends DefaultHandler {

    private final CodePointLimitedTextBuilder builder;

    ExtractedTextCollector(int maxCodePoints) {
        this.builder = new CodePointLimitedTextBuilder(maxCodePoints);
    }

    @Override
    public void characters(char[] chunk, int start, int length) {
        this.builder.append(chunk, start, length);
    }

    @Override
    public void ignorableWhitespace(char[] chunk, int start, int length) {
        // 可忽略空白同样会进入输出，因此走同一条限量路径
        this.builder.append(chunk, start, length);
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes)
            throws SAXException {

        // 块级元素之间补一个换行：否则 PDF/Word 的段落会被拼成一整行，段落边界信息就丢了。
        // 这个换行也是输出的一部分，因此同样占用配额（并在超限时当场抛出）。
        if (!this.builder.isEmpty() && this.builder.lastChar() != '\n' && isBlockElement(localName, qName)) {
            this.builder.appendStructuralNewline();
        }
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

    /**
     * @return 收集到的文本；其 code point 数必然不超过构造时的上限
     */
    String text() {
        return this.builder.text();
    }

    /**
     * @return 已计入配额的 code point 总数（供测试断言不变量）
     */
    int codePointCount() {
        return this.builder.codePointCount();
    }
}
