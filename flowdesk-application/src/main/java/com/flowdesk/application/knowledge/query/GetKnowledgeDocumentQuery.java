package com.flowdesk.application.knowledge.query;

import com.flowdesk.domain.knowledge.KnowledgeDocumentId;

/**
 * 查询单个知识文档。
 *
 * @param documentId 文档标识
 */
public record GetKnowledgeDocumentQuery(KnowledgeDocumentId documentId) {
}
