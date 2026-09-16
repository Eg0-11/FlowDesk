package com.flowdesk.application.knowledge.port.out;

import java.util.List;

/**
 * 重排出站端口（RAG 6/6）。
 *
 * <p>契约只有一句话：<b>给定规范化后的 query 与按向量顺序排列的候选正文，返回每个候选的重排分</b>。
 * 端口不认识文档标识、版本、摘要、向量或任何数据库结构 —— 重排服务只需要「问题 + 候选文本」，
 * 发送多余字段既没有用处，也会把内部标识暴露给上游。</p>
 *
 * <h2>输入</h2>
 * <ul>
 *   <li>{@code normalizedQuery}：已由 {@code KnowledgeQueryNormalizer} 规范化（NFC + strip）的问题，
 *       与送给查询向量端口的是<b>同一个字符串</b>；</li>
 *   <li>{@code candidateContents}：本次向量检索命中的切片正文，<b>顺序即向量顺序</b>
 *       （分数降序 → documentId 升序 → chunkIndex 升序）。端口必须按同样的下标记住位置。</li>
 * </ul>
 *
 * <h2>输出</h2>
 * <p>返回列表里的每一条都必须携带<b>原始下标</b>（{@code index}）与分数，由应用层把下标重新绑定到
 * 对应候选。实现<b>不得</b>按响应到达顺序去猜位置，也不得自行补齐或丢弃条目。</p>
 *
 * <h2>失败契约</h2>
 * <ul>
 *   <li>上游不可用（连接失败、超时、限流、5xx、鉴权失败）→
 *       {@link com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode#RERANK_PROVIDER_ERROR}
 *       （HTTP 502），原始异常只作为 cause；</li>
 *   <li>上游响应结构无法解释（例如根本不是 JSON、缺少结果数组）→ 同样按内部检索失败处理，
 *       <b>不</b>返回猜测的结果集；</li>
 *   <li>端口<b>不得</b>在失败时返回「原向量排序」之类的降级结果：静默降级会让调用方以为
 *       重排已经生效，而这正是本阶段要排除的假象；</li>
 *   <li>本条契约里的其它错误码（以及运行期异常）都会被用例层收敛为内部检索失败
 *       （HTTP 500），因此端口只需要表达上面两种。</li>
 * </ul>
 *
 * <h2>不重试</h2>
 * <p>本阶段不在端口内部做重试：一次检索只调用一次重排，失败即失败
 * （上游 SDK 自身的传输级重试不在此列，因为这里用的是直接 HTTP 调用）。</p>
 */
public interface KnowledgeRerankPort {

    /**
     * 对候选正文重新打分。
     *
     * @param normalizedQuery    规范化后的用户问题
     * @param candidateContents  候选切片正文，顺序即向量顺序
     * @return 每个候选的重排结果（携带原始下标）
     */
    List<KnowledgeRerankResult> rerank(String normalizedQuery, List<String> candidateContents);
}
