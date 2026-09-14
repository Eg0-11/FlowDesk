package com.flowdesk.application.knowledge.command;

import com.flowdesk.domain.knowledge.KnowledgeDocumentId;

/**
 * 索引（向量化）知识文档命令。
 *
 * <p>与解析命令一样刻意<b>不</b>包含模型名、维度、批次大小或切片内容：
 * 这些全部由服务端配置与数据库决定，客户端只能指定「哪一个文档」与「期望的版本」。</p>
 *
 * @param documentId      文档标识
 * @param expectedVersion {@code If-Match} 中声明的期望版本
 */
public record IndexKnowledgeDocumentCommand(KnowledgeDocumentId documentId, long expectedVersion) {
}
