package com.flowdesk.bootstrap.ai;

import com.flowdesk.application.ai.KnowledgeAnswerResult;
import com.flowdesk.application.knowledge.view.KnowledgeRetrievalView;
import com.flowdesk.bootstrap.knowledge.KnowledgeCitationResponse;
import java.util.List;

/**
 * 知识库问答响应体（RAG 5/6）。
 *
 * <p>包含三部分：</p>
 * <ul>
 *   <li><b>答案与审计信息</b>：{@code requestId}、{@code answer}、{@code grounded}、
 *       {@code usedCitationIds}（答案实际引用的证据编号）；</li>
 *   <li><b>本次检索参数</b>：provider / model / dimensions / topK / minScore（与检索接口一致）；</li>
 *   <li><b>完整检索证据</b>：{@code citations}，按检索顺序，<b>包含未被引用的切片</b>。</li>
 * </ul>
 *
 * <p>刻意<b>不回显 query</b>、不返回向量，也不返回提示词或模型原始报文。</p>
 *
 * @param requestId           服务端请求标识（与错误响应里的 {@code requestId} 同源）
 * @param answer              模型答案（无证据时为固定降级文案）
 * @param grounded            是否基于检索证据作答
 * @param usedCitationIds     答案实际引用的证据编号，按首次出现顺序去重
 * @param embeddingProvider   向量服务提供方
 * @param embeddingModel      向量模型标识
 * @param embeddingDimensions 向量维度
 * @param topK                本次生效的证据条数上限
 * @param minScore            本次生效的相似度下限
 * @param citations           完整检索证据
 */
public record KnowledgeAnswerResponse(String requestId,
                                      String answer,
                                      boolean grounded,
                                      List<String> usedCitationIds,
                                      String embeddingProvider,
                                      String embeddingModel,
                                      int embeddingDimensions,
                                      int topK,
                                      double minScore,
                                      List<KnowledgeCitationResponse> citations) {

    /**
     * @param result 问答结果
     * @return 响应体
     */
    public static KnowledgeAnswerResponse from(KnowledgeAnswerResult result) {
        KnowledgeRetrievalView retrieval = result.retrieval();
        return new KnowledgeAnswerResponse(
                result.requestId(),
                result.answer(),
                result.grounded(),
                List.copyOf(result.usedCitationIds()),
                retrieval.provider(),
                retrieval.model(),
                retrieval.dimensions(),
                retrieval.topK(),
                retrieval.minScore(),
                retrieval.citations().stream().map(KnowledgeCitationResponse::from).toList());
    }
}
