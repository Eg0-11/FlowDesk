package com.flowdesk.application.knowledge.port.out;

import com.flowdesk.domain.knowledge.EmbeddingDescriptor;
import java.util.List;

/**
 * 切片向量生成端口。
 *
 * <h2>契约</h2>
 * <ul>
 *   <li>入参是<b>纯文本列表</b>与描述符；实现必须按描述符声明的 provider/model/dimensions 调用上游，
 *       不得使用「默认模型」或让上游决定维度；</li>
 *   <li>返回的列表必须与入参<b>一一对应且顺序一致</b>（第 i 个向量属于第 i 段文本）；
 *       <b>不做</b>「按索引字段重排」这类纠错 —— 上游给了错序的响应就应当让它失败，
 *       而不是把可能错位的向量写进库；</li>
 *   <li>返回值只做「搬运算术」，合法性由应用层统一校验（数量、维度、NaN/Infinity、全零）；</li>
 *   <li>失败必须抛 {@link com.flowdesk.application.knowledge.index.DocumentIndexingException}，
 *       并携带稳定的 {@code KnowledgeIndexFailureCode}；上游异常文本只作为 cause，
 *       <b>不得</b>包含在消息里；</li>
 *   <li>实现<b>不得</b>记录切片正文、向量数值、API Key 或完整的上游响应；</li>
 *   <li>调用期间<b>不得</b>持有数据库事务。</li>
 * </ul>
 */
public interface KnowledgeEmbeddingPort {

    /**
     * 批量生成向量。
     *
     * @param texts      切片文本，非空且数量不超过调用方约定的批次上限
     * @param descriptor 向量描述符
     * @return 与 {@code texts} 一一对应的向量列表
     */
    List<float[]> embedAll(List<String> texts, EmbeddingDescriptor descriptor);
}
