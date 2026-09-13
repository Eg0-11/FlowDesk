package com.flowdesk.application.knowledge;

import java.util.Objects;

/**
 * 知识文档应用层异常。
 *
 * <p>始终携带 {@link KnowledgeApplicationErrorCode}；文案面向人工排查，
 * <b>不得回显原始文件名、标题、内容键、本地路径或 SQL</b>。</p>
 */
public class KnowledgeApplicationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final KnowledgeApplicationErrorCode errorCode;

    /**
     * @param errorCode 稳定的应用层错误码
     * @param message   面向开发者的说明，不含原始输入与内部细节
     */
    public KnowledgeApplicationException(KnowledgeApplicationErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    /**
     * @param errorCode 稳定的应用层错误码
     * @param message   面向开发者的说明，不含原始输入与内部细节
     * @param cause     服务端内部原因；<b>只保留在服务端</b>，绝不进入对外响应
     */
    public KnowledgeApplicationException(KnowledgeApplicationErrorCode errorCode, String message,
            Throwable cause) {

        super(message, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode 不能为 null");
    }

    /**
     * @return 应用层错误码
     */
    public KnowledgeApplicationErrorCode errorCode() {
        return this.errorCode;
    }
}
