package com.flowdesk.application.knowledge.parse;

import com.flowdesk.domain.knowledge.KnowledgeParseFailureCode;
import java.util.Objects;

/**
 * 解析失败：携带<b>稳定的失败码</b>，供持久化与对外响应复用。
 *
 * <p>异常消息仅用于服务端诊断，<b>绝不</b>进入数据库、响应体或日志之外的任何地方；
 * 第三方解析器（Tika/PDFBox/POI）的异常一律作为 cause 保留在服务端。</p>
 */
public class DocumentParsingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final KnowledgeParseFailureCode failureCode;

    /**
     * @param failureCode 稳定失败码
     * @param message     服务端诊断用说明
     */
    public DocumentParsingException(KnowledgeParseFailureCode failureCode, String message) {
        this(failureCode, message, null);
    }

    /**
     * @param failureCode 稳定失败码
     * @param message     服务端诊断用说明
     * @param cause       解析器抛出的原始异常，只保留在服务端
     */
    public DocumentParsingException(KnowledgeParseFailureCode failureCode, String message, Throwable cause) {
        super(message, cause);
        this.failureCode = Objects.requireNonNull(failureCode, "failureCode 不能为 null");
    }

    /**
     * @return 稳定失败码
     */
    public KnowledgeParseFailureCode failureCode() {
        return this.failureCode;
    }
}
