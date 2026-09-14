package com.flowdesk.application.knowledge.command;

import com.flowdesk.domain.knowledge.KnowledgeDocumentId;

/**
 * 解析（领取并同步执行）知识文档命令。
 *
 * <p>刻意<b>不</b>包含文件路径、内容键与解析器名称：这些都由服务端自己决定，
 * 客户端只能指定「哪一个文档」以及「期望的版本」。</p>
 *
 * @param documentId      文档标识
 * @param expectedVersion {@code If-Match} 中声明的期望版本
 */
public record ParseKnowledgeDocumentCommand(KnowledgeDocumentId documentId, long expectedVersion) {
}
