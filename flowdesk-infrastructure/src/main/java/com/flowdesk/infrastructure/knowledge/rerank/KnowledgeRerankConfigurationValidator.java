package com.flowdesk.infrastructure.knowledge.rerank;

import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * 重排配置的启动期校验（RAG 6/6）。
 *
 * <p>与向量化一致的原则：配置错误必须在<b>装配阶段</b>让应用启动失败，而不是等到第一次检索
 * 才表现为 502/500。校验只读配置，<b>不</b>建立连接、<b>不</b>发起请求、<b>不</b>回显任何凭证。</p>
 *
 * <h2>Key 的来源与优先级</h2>
 * <p>与依赖库 1.1.2.2 的「模态级优先、通用兜底」一致：先取
 * {@code spring.ai.dashscope.rerank.api-key}，它有文本时优先；否则回退
 * {@code spring.ai.dashscope.api-key}（{@code dashscope-embedding} profile 把它绑定到
 * {@code ${DASHSCOPE_API_KEY:}}）。刻意<b>不</b>读取 {@code AI_DASHSCOPE_API_KEY}：
 * 「Key 从哪来」必须是启动期校验能确定性看到的事实（同 ADR 0007 的结论）。</p>
 *
 * <h2>与向量化的关系</h2>
 * <p>重排的输入是向量检索的候选，因此「启用重排但未启用向量化」是一份<b>永远不会生效</b>的配置：
 * 检索链会在更早的开关检查处返回 503，重排永远不会被调用。这种「配了却不起作用」的状态
 * 必须启动失败，否则运维会以为重排已经上线。</p>
 */
public final class KnowledgeRerankConfigurationValidator {

    /** DashScope 重排模态级连接属性：优先取它。 */
    static final String RERANK_API_KEY_PROPERTY = "spring.ai.dashscope.rerank.api-key";

    /** DashScope 通用连接属性：模态级属性没有文本时回退到它。 */
    static final String API_KEY_PROPERTY = "spring.ai.dashscope.api-key";

    private KnowledgeRerankConfigurationValidator() {
    }

    /**
     * 校验重排配置本身（模型名、Endpoint、超时）。
     *
     * @param rerank 重排配置
     * @throws IllegalStateException 配置不合法
     */
    public static void requireValidConfiguration(KnowledgeRerankProperties rerank) {
        rerank.validate();
    }

    /**
     * 校验 DashScope API Key 的<b>真实配置值</b>非空（仅在启用重排时）。
     *
     * @param rerank      重排配置
     * @param environment 配置环境
     * @throws IllegalStateException 已启用重排但 Key 缺失、为空或纯空白
     */
    public static void requireDashScopeApiKey(KnowledgeRerankProperties rerank, Environment environment) {
        if (!rerank.isEnabled()) {
            return;
        }
        if (resolveApiKey(environment) == null) {
            throw new IllegalStateException("已启用 DashScope 文本重排，但 API Key 缺失或为空："
                    + "请设置环境变量 DASHSCOPE_API_KEY（dashscope-embedding profile 通过 "
                    + API_KEY_PROPERTY + " 绑定它），或直接配置 " + RERANK_API_KEY_PROPERTY
                    + "（模态级，优先）与 " + API_KEY_PROPERTY + "（通用，兜底）。"
                    + "空白字符不算有效 Key。");
        }
    }

    /**
     * 校验「启用重排」与「启用向量化」的一致性。
     *
     * @param rerank    重排配置
     * @param embeddingEnabled 是否启用文档向量化
     * @throws IllegalStateException 启用了重排但没有启用向量化
     */
    public static void requireEmbeddingEnabled(KnowledgeRerankProperties rerank, boolean embeddingEnabled) {
        if (rerank.isEnabled() && !embeddingEnabled) {
            throw new IllegalStateException("已启用 flowdesk.knowledge.rerank.enabled，但 "
                    + "flowdesk.knowledge.embedding.enabled=false：重排只对向量检索的候选生效，"
                    + "当前配置下重排永远不会被调用（检索会直接返回 503）。"
                    + "请同时启用 flowdesk.knowledge.embedding.enabled，或关闭重排。");
        }
    }

    /**
     * 按「模态级优先、通用兜底」解析 DashScope API Key。
     *
     * <p>公开给装配层复用（{@code KnowledgeConfiguration} 用它把 Key 传给适配器），
     * 这样「校验用哪个 Key」与「适配器用哪个 Key」必然是同一个值。</p>
     *
     * @param environment 配置环境
     * @return 生效的 Key；两条都没有非空白文本时为 {@code null}
     */
    public static String resolveApiKey(Environment environment) {
        String modal = environment.getProperty(RERANK_API_KEY_PROPERTY);
        if (StringUtils.hasText(modal)) {
            return modal;
        }
        String common = environment.getProperty(API_KEY_PROPERTY);
        return StringUtils.hasText(common) ? common : null;
    }
}
