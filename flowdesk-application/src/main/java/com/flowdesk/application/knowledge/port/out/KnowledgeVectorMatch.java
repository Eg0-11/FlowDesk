package com.flowdesk.application.knowledge.port.out;

import com.flowdesk.domain.knowledge.KnowledgeDocumentId;
import com.flowdesk.domain.knowledge.Sha256Digest;

/**
 * 一次向量检索的命中结果（框架无关）。
 *
 * <p>基础设施实现（pgvector JDBC 适配器）负责把行映射成本类型，
 * <b>不</b>负责排序之外的任何「加工」：它按 SQL 的排序返回，映射时保持行顺序。</p>
 *
 * <p>本类型刻意<b>不做</b>字段校验：契约校验（分数有限且落在 0..1、不低于阈值、
 * 排序与去重）由用例服务统一执行，这样「端口返回了违反契约的结果」是可测的，
 * 而且不会被记录成「数据本来就有问题」。非法结果一律视为服务端内部错误，
 * 绝不被静默排序、去重、截断或修正。</p>
 *
 * @param documentId      命中切片所属文档
 * @param documentVersion 该文档当前版本（引用可审计性的一部分）
 * @param documentTitle   文档标题
 * @param chunkIndex      切片序号
 * @param chunkSha256     切片内容摘要（与库中切片一致，供调用方复核）
 * @param content         切片正文
 * @param score           余弦相似度，{@code 1 - 余弦距离}
 */
public record KnowledgeVectorMatch(KnowledgeDocumentId documentId,
                                   long documentVersion,
                                   String documentTitle,
                                   int chunkIndex,
                                   Sha256Digest chunkSha256,
                                   String content,
                                   double score) {
}
