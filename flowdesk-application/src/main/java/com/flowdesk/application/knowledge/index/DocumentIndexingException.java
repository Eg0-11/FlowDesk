package com.flowdesk.application.knowledge.index;

import com.flowdesk.domain.knowledge.KnowledgeIndexFailureCode;
import java.util.Objects;

/**
 * 索引失败：携带<b>稳定的失败码</b>，供持久化与对外响应复用。
 *
 * <p>与解析失败同样的约束：异常消息只用于服务端诊断，<b>绝不</b>进入数据库、响应体或日志之外的
 * 任何地方；上游（DashScope / JDBC / 驱动）的异常一律作为 cause 保留在服务端。</p>
 */
public class DocumentIndexingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final KnowledgeIndexFailureCode failureCode;

    /**
     * @param failureCode 稳定失败码
     * @param message     服务端诊断用说明
     */
    public DocumentIndexingException(KnowledgeIndexFailureCode failureCode, String message) {
        this(failureCode, message, null);
    }

    /**
     * @param failureCode 稳定失败码
     * @param message     服务端诊断用说明
     * @param cause       上游异常，只保留在服务端
     */
    public DocumentIndexingException(KnowledgeIndexFailureCode failureCode, String message, Throwable cause) {
        super(message, cause);
        this.failureCode = Objects.requireNonNull(failureCode, "failureCode 不能为 null");
    }

    /**
     * @return 稳定失败码
     */
    public KnowledgeIndexFailureCode failureCode() {
        return this.failureCode;
    }
}
