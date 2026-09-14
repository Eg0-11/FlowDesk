package com.flowdesk.application.knowledge.port.out;

import com.flowdesk.domain.knowledge.KnowledgeDocument;
import java.util.Objects;

/**
 * 带版本的文档元数据快照。
 *
 * <p>版本用于对外契约（响应里的 {@code version}）与将来的演进；本阶段上传即定型、
 * 没有更新操作，因此版本恒为 {@code 0}。与工单不同，这里<b>没有</b>乐观并发的
 * compare-and-set 语义，因为没有任何更新入口。</p>
 *
 * <p>构造时校验自身不变量：一个「已存储的结果」不允许由适配器随意构造出非法状态。</p>
 *
 * @param document 文档聚合，不得为 {@code null}
 * @param version  版本号，不得为负数；新上传为 0
 */
public record VersionedKnowledgeDocument(KnowledgeDocument document, long version) {

    public VersionedKnowledgeDocument {
        Objects.requireNonNull(document, "document 不能为 null");
        if (version < 0L) {
            throw new IllegalArgumentException("version 不能为负数");
        }
    }
}
