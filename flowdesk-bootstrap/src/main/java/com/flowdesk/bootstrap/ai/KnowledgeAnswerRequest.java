package com.flowdesk.bootstrap.ai;

/**
 * 知识库问答请求体（RAG 5/6）。
 *
 * <p>三个字段与知识检索接口完全一致，而且这里<b>不做</b>任何 Bean Validation：
 * 规范化与边界校验只发生在检索用例里（NFC → strip → 判空 → code point 上限 → 控制字符；
 * topK 与 minScore 范围），这样 {@code /ai/knowledge-answer} 与 {@code /knowledge/search}
 * 的输入语义不会分叉 —— 「2000 个有效字符 + 首尾空白」在两边都合法。</p>
 *
 * <p>请求体缺失（空 body）时控制器按 {@code query=null} 处理，与 {@code {}} 得到同一条
 * 「检索请求不合法」的 400 契约。</p>
 *
 * @param query    用户问题
 * @param topK     期望的证据条数上限；缺省用服务端默认值
 * @param minScore 相似度下限；缺省用服务端默认值
 */
public record KnowledgeAnswerRequest(String query, Integer topK, Double minScore) {
}
