package com.flowdesk.bootstrap.knowledge;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.flowdesk.application.knowledge.view.KnowledgeCitationView;
import java.util.UUID;

/**
 * 单条引用结果的响应体（RAG 4/6，RAG 6/6 增加重排分）。
 *
 * <p>{@code citationId}（{@code K1}、{@code K2}……）与 {@code rank} 由服务端按<b>最终顺序</b>确定
 * （启用重排时就是重排后的顺序），调用方可以在答案里引用它并回到具体文档与切片。</p>
 *
 * <h2>两个分数不是一回事</h2>
 * <ul>
 *   <li>{@code score}：<b>向量余弦相似度</b>，始终来自向量检索，不因重排而改变；</li>
 *   <li>{@code rerankScore}：<b>重排分</b>，只在本次顺序由重排决定时出现
 *       （{@link JsonInclude.Include#NON_NULL}：未使用重排时<b>不输出该字段</b>，
 *       因此关闭重排时的 JSON 与之前逐字段一致）。它是<b>当前请求内的相对分</b>，
 *       只用于本次候选排序，不能当作跨请求比较的绝对质量分。</li>
 * </ul>
 *
 * <p><b>不含向量</b>：向量对调用方没有意义，也不会出现在响应里。</p>
 *
 * @param citationId      引用编号
 * @param rank            名次，从 1 连续递增
 * @param documentId      文档标识
 * @param documentVersion 文档当前版本
 * @param documentTitle   文档标题
 * @param chunkIndex      切片序号
 * @param chunkSha256     切片内容摘要
 * @param content         切片正文
 * @param score           向量余弦相似度
 * @param rerankScore     重排分；未使用重排时不输出
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KnowledgeCitationResponse(String citationId,
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
     * @param view 引用视图
     * @return 响应体
     */
    public static KnowledgeCitationResponse from(KnowledgeCitationView view) {
        return new KnowledgeCitationResponse(view.citationId(), view.rank(), view.documentId(),
                view.documentVersion(), view.documentTitle(), view.chunkIndex(), view.chunkSha256(),
                view.content(), view.score(), view.rerankScore());
    }
}
