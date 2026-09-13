package com.flowdesk.application.knowledge.port.in;

import com.flowdesk.application.knowledge.query.GetKnowledgeDocumentQuery;
import com.flowdesk.application.knowledge.view.KnowledgeDocumentView;

/**
 * 知识文档查询用例的输入端口。
 *
 * <p>查询是只读操作：不写入存储、不读取时间、不生成标识。</p>
 */
public interface KnowledgeDocumentQueryUseCase {

    /**
     * 查询单个知识文档的元数据。
     *
     * @param query 查询条件
     * @return 文档视图
     * @throws com.flowdesk.application.knowledge.KnowledgeApplicationException 查询不合法或文档不存在
     */
    KnowledgeDocumentView get(GetKnowledgeDocumentQuery query);
}
