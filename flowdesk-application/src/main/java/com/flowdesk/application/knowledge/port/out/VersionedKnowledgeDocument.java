package com.flowdesk.application.knowledge.port.out;

import com.flowdesk.domain.knowledge.KnowledgeDocument;

/**
 * 带版本的文档元数据快照。
 *
 * <p>版本用于对外契约（响应里的 {@code version}）与将来的演进；本阶段上传即定型、
 * 没有更新操作，因此版本恒为 {@code 0}。与工单不同，这里<b>没有</b>乐观并发的
 * compare-and-set 语义，因为没有任何更新入口。</p>
 *
 * @param document 文档聚合
 * @param version  版本号；新上传为 0
 */
public record VersionedKnowledgeDocument(KnowledgeDocument document, long version) {
}
