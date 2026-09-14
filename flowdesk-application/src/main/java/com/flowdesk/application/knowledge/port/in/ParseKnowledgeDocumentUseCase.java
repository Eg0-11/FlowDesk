package com.flowdesk.application.knowledge.port.in;

import com.flowdesk.application.knowledge.command.ParseKnowledgeDocumentCommand;
import com.flowdesk.application.knowledge.view.ParsedDocumentView;

/**
 * 解析知识文档用例的输入端口。
 *
 * <p>本阶段是<b>同步</b>解析：一次调用完成「领取 → 读取原文 → 解析 → 切片 → 原子落库」。
 * 不使用 {@code @Async}、内存队列或任何伪异步实现。</p>
 */
public interface ParseKnowledgeDocumentUseCase {

    /**
     * 同步解析一个文档。
     *
     * @param command 解析命令（文档标识 + 期望版本）
     * @return 解析结果视图
     * @throws com.flowdesk.application.knowledge.KnowledgeApplicationException 文档不存在、
     *         版本冲突、状态不允许解析或存储失败
     * @throws com.flowdesk.application.knowledge.parse.DocumentParsingException 解析失败（稳定失败码）
     * @throws com.flowdesk.application.knowledge.parse.DocumentChunkingException 切片数量超限
     */
    ParsedDocumentView parse(ParseKnowledgeDocumentCommand command);
}
