package com.flowdesk.application.knowledge.port.in;

import com.flowdesk.application.knowledge.command.IndexKnowledgeDocumentCommand;
import com.flowdesk.application.knowledge.view.IndexedDocumentView;

/**
 * 索引知识文档用例输入端口（同步）。
 *
 * <p>流程是「领取 → 生成向量 → 原子落库 → 推进状态」，全部在<b>一次请求</b>内完成：
 * 这是本阶段的刻意选择（见 ADR 0007 的「限制与后续异步化方向」）。</p>
 */
public interface IndexKnowledgeDocumentUseCase {

    /**
     * 为文档的切片生成向量并原子落库，成功后状态推进为 {@code INDEXED}。
     *
     * @param command 索引命令（文档标识 + 期望版本）
     * @return 索引结果视图
     */
    IndexedDocumentView index(IndexKnowledgeDocumentCommand command);
}
