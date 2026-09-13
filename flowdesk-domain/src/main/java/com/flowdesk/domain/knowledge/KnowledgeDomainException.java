package com.flowdesk.domain.knowledge;

import java.util.Objects;

/**
 * 知识文档领域异常。
 *
 * <p>始终携带 {@link KnowledgeErrorCode}，供上层稳定映射；异常文案只用于人工排查，
 * 不得作为契约，也<b>不得回显调用方传入的原始非法值</b>。</p>
 */
public class KnowledgeDomainException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final KnowledgeErrorCode errorCode;

    /**
     * @param errorCode 稳定的领域错误码
     * @param message   面向开发者的说明，不含原始非法输入
     */
    public KnowledgeDomainException(KnowledgeErrorCode errorCode, String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode 不能为 null");
    }

    /**
     * @return 领域错误码
     */
    public KnowledgeErrorCode errorCode() {
        return this.errorCode;
    }
}
