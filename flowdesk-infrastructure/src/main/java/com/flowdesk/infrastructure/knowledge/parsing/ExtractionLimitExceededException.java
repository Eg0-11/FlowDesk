package com.flowdesk.infrastructure.knowledge.parsing;

/**
 * 提取文本超过配置上限：解析过程中的「立即中断」信号。
 *
 * <p>它是<b>非受检</b>异常，因此可以从 SAX 回调深处直接终止第三方解析器的工作；
 * 适配器把它统一映射为 {@code EXTRACTED_TEXT_TOO_LARGE}。</p>
 */
final class ExtractionLimitExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param maxCodePoints 配置的上限
     */
    ExtractionLimitExceededException(int maxCodePoints) {
        super("提取文本超过上限 " + maxCodePoints + " code points");
    }
}
