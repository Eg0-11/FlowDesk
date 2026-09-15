package com.flowdesk.application.knowledge.port.in;

import com.flowdesk.application.knowledge.query.RetrieveKnowledgeQuery;
import com.flowdesk.application.knowledge.view.KnowledgeRetrievalView;

/**
 * 知识检索用例输入端口（RAG 4/6）。
 *
 * <p>语义：把一段用户问题变成<b>可审计的引用结果</b> —— 查询向量、pgvector 余弦检索、
 * 命中切片与分数。它<b>不</b>生成自然语言答案、不调用 Chat 模型、不做 Rerank 与混合检索，
 * 也不修改任何文档状态或向量。</p>
 *
 * <h2>失败契约</h2>
 * <ul>
 *   <li>请求本身不合法（query 缺失/空白/超长/含控制字符，topK 或 minScore 越界）→
 *       {@code INVALID_RETRIEVAL_QUERY}（HTTP 400），且<b>不调用任何端口</b>；</li>
 *   <li>当前环境未启用向量化 → {@code KNOWLEDGE_EMBEDDING_DISABLED}（HTTP 503），
 *       且不调用模型、不访问向量表；</li>
 *   <li>上游向量服务失败（超时、限流、连接失败、5xx）→ {@code EMBEDDING_PROVIDER_ERROR}（HTTP 502）；</li>
 *   <li>模型响应非法、查询向量非法、数据库检索失败、端口返回的结果违反契约 →
 *       {@code KNOWLEDGE_RETRIEVAL_FAILURE}（HTTP 500）。</li>
 * </ul>
 */
public interface RetrieveKnowledgeUseCase {

    /**
     * 检索与问题最相关的切片，并按最终顺序生成引用编号。
     *
     * @param query 检索请求
     * @return 检索结果视图（无命中时 {@code citations} 为空列表，仍然返回成功）
     */
    KnowledgeRetrievalView retrieve(RetrieveKnowledgeQuery query);
}
