package com.flowdesk.application.knowledge.view;

import java.util.List;

/**
 * 检索结果视图：检索接口的返回形态（RAG 4/6）。
 *
 * <p>回显本次检索<b>使用的参数</b>（provider / model / dimensions / topK / minScore），
 * 这样调用方与排障者都能确认「这次用的是什么模型、什么阈值」——
 * 但<b>不回显 query 本身</b>（用户问题不需要、也不应该被服务端回抄一遍）。</p>
 *
 * <p>无命中时 {@code citations} 是空列表，仍然是成功响应（不是 404）：
 * 「检索没有结果」是正常结果，不是错误。</p>
 *
 * @param provider   向量服务提供方（与文档侧一致）
 * @param model      向量模型标识
 * @param dimensions 向量维度
 * @param topK       本次生效的返回条数上限
 * @param minScore   本次生效的相似度下限
 * @param citations  引用结果，按相似度降序、编号 K1、K2……
 */
public record KnowledgeRetrievalView(String provider,
                                     String model,
                                     int dimensions,
                                     int topK,
                                     double minScore,
                                     List<KnowledgeCitationView> citations) {
}
