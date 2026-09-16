package com.flowdesk.infrastructure.knowledge.rerank;

import com.flowdesk.application.knowledge.port.out.KnowledgeRerankPort;
import com.flowdesk.application.knowledge.port.out.KnowledgeRerankResult;
import java.util.List;

/**
 * 「重排未启用」时的占位端口（RAG 6/6）。
 *
 * <p>与向量化关闭时的占位实现同一个思路：Bean 始终存在，检索用例才不需要在构造期处理
 * 「端口可能缺失」。关闭状态下用例服务<b>不会</b>调用它（开关判断在调用之前），
 * 一旦真的被调用就说明装配或开关判断出了问题 —— 那时必须显式失败，
 * 而不是返回一个「看起来像没重排」的结果。</p>
 */
public final class DisabledKnowledgeRerank {

    private DisabledKnowledgeRerank() {
    }

    /**
     * 关闭状态下的占位实现：任何调用都立刻失败。
     */
    public static final class Port implements KnowledgeRerankPort {

        @Override
        public List<KnowledgeRerankResult> rerank(String normalizedQuery, List<String> candidateContents) {
            throw new IllegalStateException("未启用 flowdesk.knowledge.rerank.enabled，重排端口不应被调用");
        }
    }
}
