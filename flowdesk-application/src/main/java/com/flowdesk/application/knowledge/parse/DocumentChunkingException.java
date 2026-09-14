package com.flowdesk.application.knowledge.parse;

import com.flowdesk.domain.knowledge.KnowledgeParseFailureCode;
import java.util.Objects;

/**
 * 切片失败：目前只有「切片数量超过上限」一种稳定原因。
 */
public class DocumentChunkingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final KnowledgeParseFailureCode failureCode;

    /**
     * @param failureCode 稳定失败码
     * @param message     服务端诊断用说明
     */
    public DocumentChunkingException(KnowledgeParseFailureCode failureCode, String message) {
        super(message);
        this.failureCode = Objects.requireNonNull(failureCode, "failureCode 不能为 null");
    }

    /**
     * @return 稳定失败码
     */
    public KnowledgeParseFailureCode failureCode() {
        return this.failureCode;
    }
}
