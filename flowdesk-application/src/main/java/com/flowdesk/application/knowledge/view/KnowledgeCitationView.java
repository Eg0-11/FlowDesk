package com.flowdesk.application.knowledge.view;

import java.util.UUID;

/**
 * 单条引用结果：一条命中切片及其可审计元数据（RAG 4/6，RAG 6/6 增加重排分）。
 *
 * <p>{@code citationId} 由最终顺序决定（{@code K1}、{@code K2}……），{@code rank} 从 1 连续递增 ——
 * 调用方可以在答案里直接引用 {@code citationId}，并据此回到具体的文档与切片。</p>
 *
 * <h2>两个分数分别是什么</h2>
 * <ul>
 *   <li>{@code score}：<b>向量余弦相似度</b>（{@code 1 - 余弦距离}）。它始终来自向量检索阶段，
 *       排序模式变成 {@link KnowledgeRankingMode#RERANK} 之后也<b>不会</b>被重排分覆盖 ——
 *       偷换语义会让「这个 0.87 到底是什么分」再也说不清；</li>
 *   <li>{@code rerankScore}：<b>重排分</b>，只在本次顺序由重排决定时才有值（否则为 {@code null}）。
 *       它是<b>当前请求内的相对分</b>，只用于对本次候选排序，
 *       <b>不是</b>跨请求可比较的绝对质量分（同一切片在不同问题、不同候选集合下分数不可比）。</li>
 * </ul>
 *
 * <p><b>不含向量</b>：向量对调用方没有意义，也不应该出现在响应里。</p>
 *
 * @param citationId      引用编号，形如 {@code K1}
 * @param rank            名次，从 1 连续递增
 * @param documentId      文档标识
 * @param documentVersion 该文档当前版本
 * @param documentTitle   文档标题
 * @param chunkIndex      切片序号
 * @param chunkSha256     切片内容摘要
 * @param content         切片正文
 * @param score           向量余弦相似度（始终是向量分，不因重排而改变）
 * @param rerankScore     重排分；未使用重排时为 {@code null}
 */
public record KnowledgeCitationView(String citationId,
                                    int rank,
                                    UUID documentId,
                                    long documentVersion,
                                    String documentTitle,
                                    int chunkIndex,
                                    String chunkSha256,
                                    String content,
                                    double score,
                                    Double rerankScore) {

    /**
     * 造一条只有向量分的引用（未使用重排时使用）。
     *
     * @param citationId      引用编号
     * @param rank            名次
     * @param documentId      文档标识
     * @param documentVersion 文档版本
     * @param documentTitle   文档标题
     * @param chunkIndex      切片序号
     * @param chunkSha256     切片摘要
     * @param content         切片正文
     * @param score           向量余弦相似度
     * @return 引用视图（{@code rerankScore} 为 {@code null}）
     */
    public static KnowledgeCitationView vectorOnly(String citationId, int rank, UUID documentId,
            long documentVersion, String documentTitle, int chunkIndex, String chunkSha256, String content,
            double score) {

        return new KnowledgeCitationView(citationId, rank, documentId, documentVersion, documentTitle,
                chunkIndex, chunkSha256, content, score, null);
    }
}
