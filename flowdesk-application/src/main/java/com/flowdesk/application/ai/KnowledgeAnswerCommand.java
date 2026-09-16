package com.flowdesk.application.ai;

import com.flowdesk.application.knowledge.query.RetrieveKnowledgeQuery;

/**
 * 知识库问答命令（RAG 5/6）。
 *
 * <p>字段与知识检索接口的请求一一对应，而且<b>刻意不做任何校验</b>：
 * 它们会被原样交给 {@link com.flowdesk.application.knowledge.port.in.RetrieveKnowledgeUseCase}，
 * 由检索用例执行同一套规范化与边界检查（NFC、strip、code point 上限、控制字符、
 * topK 与 minScore 范围）。这样 {@code /ai/knowledge-answer} 与 {@code /knowledge/search}
 * 两个入口的输入语义<b>完全一致</b>，不存在第二份规则。</p>
 *
 * @param query    用户问题原文
 * @param topK     期望的证据条数上限；{@code null} 表示使用服务端默认值
 * @param minScore 相似度下限；{@code null} 表示使用服务端默认值
 */
public record KnowledgeAnswerCommand(String query, Integer topK, Double minScore) {

    /**
     * @return 等价的检索查询（不做校验，交给检索用例）
     */
    public RetrieveKnowledgeQuery toRetrievalQuery() {
        return new RetrieveKnowledgeQuery(this.query, this.topK, this.minScore);
    }
}
