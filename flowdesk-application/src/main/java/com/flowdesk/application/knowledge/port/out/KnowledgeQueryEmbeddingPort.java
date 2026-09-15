package com.flowdesk.application.knowledge.port.out;

import com.flowdesk.domain.knowledge.EmbeddingDescriptor;

/**
 * 查询向量生成端口（RAG 4/6）。
 *
 * <h2>为什么不复用 {@link KnowledgeEmbeddingPort}</h2>
 * <p>文档侧端口把 {@code textType} 固定为 {@code document}（DashScope 的检索语义约定），
 * 而查询侧必须用 {@code query}：两者在同一个向量空间里但语义不同，混用会让相似度整体偏斜。
 * 因此查询走<b>独立端口</b>，接口差异体现在契约上而不是一个布尔参数上。</p>
 *
 * <p>两者复用<b>同一个 {@code EmbeddingModel} 与同一份 {@link EmbeddingDescriptor}</b>：
 * 查询向量必须与库里的文档向量由同一个模型、同样的维度生成，否则余弦相似度没有意义。</p>
 *
 * <h2>契约</h2>
 * <ul>
 *   <li>一次调用只处理<b>一个</b>查询，返回<b>恰好一条</b>向量；</li>
 *   <li>实现必须按描述符声明的 model/dimensions 调用上游，并显式使用查询语义；
 *       <b>不得</b>调用 {@code EmbeddingModel.dimensions()} 去探测维度（那可能发起远端请求）；</li>
 *   <li>上游异常（超时、限流、连接失败、5xx）必须映射为应用层的
 *       {@code EMBEDDING_PROVIDER_ERROR}；上游响应结构或向量非法映射为
 *       {@code KNOWLEDGE_RETRIEVAL_FAILURE}。两者都只保留 cause，消息里不得出现
 *       query、向量、API Key 或上游响应体；</li>
 *   <li>实现<b>不得</b>把 query 写进异常消息、日志或响应（查询是用户数据）；</li>
 *   <li>调用期间<b>不得</b>持有数据库事务。</li>
 * </ul>
 */
public interface KnowledgeQueryEmbeddingPort {

    /**
     * 为用户问题生成一条查询向量。
     *
     * @param query      已规范化（NFC + strip）的用户问题
     * @param descriptor 与文档侧共用的向量描述符
     * @return 恰好一条查询向量
     */
    float[] embedQuery(String query, EmbeddingDescriptor descriptor);
}
