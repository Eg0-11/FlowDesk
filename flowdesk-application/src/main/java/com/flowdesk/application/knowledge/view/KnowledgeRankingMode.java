package com.flowdesk.application.knowledge.view;

/**
 * 本次检索结果的排序模式（RAG 6/6）。
 *
 * <p>它回答的是「这一份证据顺序<b>由什么决定</b>」，而不是「重排开关是否打开」：
 * 开关打开但候选不足（0 或 1 条）时不会调用重排服务，此时顺序仍然由向量相似度决定，
 * 因此如实报告 {@link #VECTOR_SIMILARITY}。</p>
 *
 * <ul>
 *   <li>{@link #VECTOR_SIMILARITY}：顺序 = 向量余弦相似度降序（含「重排开启但无需调用」的情形）；</li>
 *   <li>{@link #RERANK}：顺序 = 重排分降序（同分时保持原向量排名）。</li>
 * </ul>
 *
 * <p>调用方因此可以区分「这份顺序是向量排序」与「这份顺序已经过重排」，
 * 而不必去猜开关状态；{@code rerankScore} 只在 {@link #RERANK} 模式下存在。</p>
 */
public enum KnowledgeRankingMode {

    /** 按向量余弦相似度排序。 */
    VECTOR_SIMILARITY,

    /** 按重排分排序。 */
    RERANK
}
