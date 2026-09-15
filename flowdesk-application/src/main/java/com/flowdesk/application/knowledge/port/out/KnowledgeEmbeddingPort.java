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
 *   <li>返回的列表必须与入参文本<b>一一对应</b>，且<b>已经恢复为请求顺序</b>：
 *       第 i 个向量就是第 i 段文本的向量。调用方因此可以直接按位置使用，
 *       不需要（也不得）自己去对齐；</li>
 *   <li>基础设施适配器<b>可以并且应该</b>使用供应商返回的<b>稳定 index</b>（Spring AI 的
 *       {@code Embedding.getIndex()}）把原始响应归位到请求位置。这属于<b>协议映射</b>
 *       （把上游声明的字段翻译成本端顺序），<b>不是</b>猜测，也<b>不是</b>静默纠错 ——
 *       供应商明确声明的 index 是协议的一部分；
 *       反之，<b>不</b>得按「响应列表的先后顺序」去猜哪条向量属于哪段文本；</li>
 *   <li>以下情况必须拒绝（抛 {@link com.flowdesk.application.knowledge.index.DocumentIndexingException}，
 *       失败码 {@code INVALID_EMBEDDING_RESPONSE}）：结果数量与请求数量不一致；
 *       某条结果的 index 为 {@code null}、为负、越界、重复，或存在缺失的位置。
 *       <b>不</b>静默跳过、<b>不</b>覆盖已有位置、<b>不</b>猜测；</li>
 *   <li>返回值只做「搬运与协议映射」，业务合法性仍由应用层统一校验
 *       （数量、维度恰好匹配、{@code NaN}/{@code ±Infinity}、全零）；</li>
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
     * @return 与 {@code texts} 一一对应、且已恢复为请求顺序的向量列表
     */
    List<float[]> embedAll(List<String> texts, EmbeddingDescriptor descriptor);
}
