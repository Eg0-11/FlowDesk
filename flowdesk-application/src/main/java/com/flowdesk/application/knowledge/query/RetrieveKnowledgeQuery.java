package com.flowdesk.application.knowledge.query;

/**
 * 知识检索请求（RAG 4/6）：一个用户问题 + 可选的检索参数。
 *
 * <p>三个字段都可能是 {@code null}，含义不同：{@code query} 为 {@code null} 表示
 * <b>请求不合法</b>；{@code topK} / {@code minScore} 为 {@code null} 表示
 * <b>使用服务端配置的默认值</b>。默认值与上限都不在这个类型里：它们来自
 * {@code flowdesk.knowledge.retrieval} 配置，由用例服务套用 ——
 * 输入适配器（HTTP）因此无法通过请求体影响上限。</p>
 *
 * <p>本类型只承载原始入参，<b>不做</b>任何规范化或校验：规范化（NFC、strip、控制字符、
 * code point 上限）与校验必须在用例服务里发生一次，且发生在任何模型或数据库调用之前。</p>
 *
 * @param query    用户问题原文
 * @param topK     期望返回的引用条数上限；{@code null} 表示使用默认值
 * @param minScore 余弦相似度下限（含边界）；{@code null} 表示使用默认值
 */
public record RetrieveKnowledgeQuery(String query, Integer topK, Double minScore) {
}
