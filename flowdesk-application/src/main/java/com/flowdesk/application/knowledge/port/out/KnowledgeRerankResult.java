package com.flowdesk.application.knowledge.port.out;

/**
 * 单条重排结果（RAG 6/6）：原始候选下标 + 重排分。
 *
 * <p>{@code index} 是候选在<b>本次请求输入列表</b>里的下标（不是名次、不是文档标识）：
 * 上游按这个字段声明「这条分数属于哪个候选」，应用层据此把分数绑回对应切片。
 * 因此重排服务返回的顺序无关紧要 —— 只有 {@code index} 决定归属。</p>
 *
 * <p>两个字段都用包装类型，是为了让「上游没给这一项」可以被表达出来并被<b>拒绝</b>：
 * {@code null} 下标或分数不是「默认值」，而是无法审计的响应，一律判为失败。</p>
 *
 * @param index 候选在输入列表中的原始下标
 * @param score 重排分（{@code 0.0..1.0} 的有限数值）
 */
public record KnowledgeRerankResult(Integer index, Double score) {
}
