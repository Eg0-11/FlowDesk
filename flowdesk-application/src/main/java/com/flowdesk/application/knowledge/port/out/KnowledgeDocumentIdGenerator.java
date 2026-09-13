package com.flowdesk.application.knowledge.port.out;

import com.flowdesk.domain.knowledge.KnowledgeDocumentId;

/**
 * 知识文档标识生成端口。
 *
 * <p>标识必须由服务端生成：客户端不能决定文档标识，否则就存在「构造标识覆盖他人数据」
 * 与「用标识探测是否存在」的空间。</p>
 */
public interface KnowledgeDocumentIdGenerator {

    /**
     * @return 新的文档标识，永不返回 {@code null}
     */
    KnowledgeDocumentId nextId();
}
