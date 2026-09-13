package com.flowdesk.domain.knowledge;

/**
 * 知识文档状态。
 *
 * <p>本阶段只有 {@link #UPLOADED}：原始内容已安全落盘（临时文件已原子移动为最终对象），
 * 且元数据已经入库 —— 它是「元数据与内容都存在」这一事实的标记。
 * 解析、切片、向量化等后续阶段会引入更多状态，但本任务不实现。</p>
 */
public enum KnowledgeDocumentStatus {

    /** 已上传：原始内容已存储且元数据已入库。 */
    UPLOADED
}
