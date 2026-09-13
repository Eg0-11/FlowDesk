package com.flowdesk.application.knowledge.port.in;

import com.flowdesk.application.knowledge.command.UploadKnowledgeDocumentCommand;
import com.flowdesk.application.knowledge.view.KnowledgeDocumentView;

/**
 * 上传知识文档用例的输入端口。
 */
public interface UploadKnowledgeDocumentUseCase {

    /**
     * 上传一个知识文档：把原始内容安全落盘并写入元数据。
     *
     * <p>时序固定为：校验命令与文件元数据 → 生成文档标识 → 流式写入临时文件并计算摘要 →
     * 校验通过后原子移动到最终位置 → 插入元数据 → 返回视图。
     * <b>文件复制期间不持有任何数据库事务</b>。</p>
     *
     * @param command 上传命令
     * @return 新文档的只读视图（不含内容键）
     * @throws com.flowdesk.application.knowledge.KnowledgeApplicationException 任何一步失败
     */
    KnowledgeDocumentView upload(UploadKnowledgeDocumentCommand command);
}
