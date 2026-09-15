package com.flowdesk.application.knowledge.view;

import java.util.UUID;

/**
 * 单条引用结果：一条命中切片及其可审计元数据（RAG 4/6）。
 *
 * <p>{@code citationId} 由最终顺序决定（{@code K1}、{@code K2}……），{@code rank} 从 1 连续递增 ——
 * 调用方可以在答案里直接引用 {@code citationId}，并据此回到具体的文档与切片。</p>
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
 * @param score           余弦相似度（{@code 1 - 余弦距离}）
 */
public record KnowledgeCitationView(String citationId,
                                    int rank,
                                    UUID documentId,
                                    long documentVersion,
                                    String documentTitle,
                                    int chunkIndex,
                                    String chunkSha256,
                                    String content,
                                    double score) {
}
