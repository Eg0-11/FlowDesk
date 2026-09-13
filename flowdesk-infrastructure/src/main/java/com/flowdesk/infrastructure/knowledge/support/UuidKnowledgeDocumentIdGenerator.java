package com.flowdesk.infrastructure.knowledge.support;

import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentIdGenerator;
import com.flowdesk.domain.knowledge.KnowledgeDocumentId;
import java.util.UUID;

/**
 * 基于 {@link UUID#randomUUID()} 的文档标识生成器。
 *
 * <p>标识由服务端生成，客户端无法指定；内容键也由标识派生，因此不存在「构造键覆盖他人内容」
 * 的可能。</p>
 */
public final class UuidKnowledgeDocumentIdGenerator implements KnowledgeDocumentIdGenerator {

    @Override
    public KnowledgeDocumentId nextId() {
        return KnowledgeDocumentId.of(UUID.randomUUID());
    }
}
