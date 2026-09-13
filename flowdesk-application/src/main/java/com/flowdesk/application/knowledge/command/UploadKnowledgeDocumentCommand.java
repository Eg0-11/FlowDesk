package com.flowdesk.application.knowledge.command;

import com.flowdesk.application.knowledge.port.out.ContentSource;

/**
 * 上传知识文档命令。
 *
 * <p>只承载数据，不复制领域校验：标题的规范化（strip）由用例完成，
 * 「是否成立」的最终裁决在领域聚合。</p>
 *
 * @param title         文档标题
 * @param contentSource 原始内容源（纯 Java 抽象，不认识 multipart）
 */
public record UploadKnowledgeDocumentCommand(String title, ContentSource contentSource) {
}
