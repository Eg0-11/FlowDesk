package com.flowdesk.application.knowledge.port.out;

import com.flowdesk.domain.knowledge.KnowledgeQueryEmbedding;
import java.util.List;

/**
 * 向量相似度检索端口（RAG 4/6）。
 *
 * <h2>契约</h2>
 * <ul>
 *   <li><b>只读</b>：实现只执行一条 {@code SELECT}，不得使用 {@code FOR UPDATE}、
 *       不得写任何表、不得修改文档状态/版本/失败码或任何向量；</li>
 *   <li>只检索<b>当前有效</b>的向量：文档状态必须是 {@code INDEXED}，
 *       且文档与向量行的 {@code provider/model/dimensions} 必须与给定描述符<b>精确匹配</b>，
 *       向量行的 {@code chunk_sha256} 必须等于对应切片的 {@code sha256}；</li>
 *   <li>按余弦<b>距离升序</b>返回（等价于相似度降序），
 *       距离相同时按 {@code documentId} 升序、再按 {@code chunkIndex} 升序 ——
 *       顺序必须是<b>确定</b>的，否则引用编号会在同一份数据上抖动；</li>
 *   <li>返回条数不超过 {@code topK}，且每个命中的分数不低于 {@code minScore}（含边界）；</li>
 *   <li>调用期间<b>不得</b>持有数据库事务，也不得与模型调用共处一个事务；</li>
 *   <li><b>失败只有一种表达方式</b>：抛应用层的 {@code KNOWLEDGE_RETRIEVAL_FAILURE}
 *       （HTTP 500）。数据库异常、结果集读取失败、行映射期的领域不变量失败都必须在实现内部
 *       先映射成它；消息与日志里<b>不得</b>出现 SQL、连接串、用户名、密码、query、向量或摘要原值；</li>
 *   <li>抛出任何<b>其它</b>错误码（例如 {@code INVALID_RETRIEVAL_QUERY}、
 *       {@code KNOWLEDGE_DOCUMENT_NOT_FOUND}、{@code EMBEDDING_PROVIDER_ERROR}）
 *       都视为<b>违反错误分类契约</b>：用例层会把它收敛为 {@code KNOWLEDGE_RETRIEVAL_FAILURE}，
 *       以免内部故障在 HTTP 上表现为 400 / 404 / 502 / 503。</li>
 * </ul>
 */
public interface KnowledgeVectorSearchPort {

    /**
     * 检索与查询向量最相似的切片。
     *
     * @param queryEmbedding 已校验的查询向量（含描述符）
     * @param minScore       相似度下限，含边界
     * @param topK           返回条数上限（已由用例服务按配置校验过）
     * @return 命中的切片，按相似度降序、并带稳定 tie-break
     */
    List<KnowledgeVectorMatch> search(KnowledgeQueryEmbedding queryEmbedding, double minScore, int topK);
}
