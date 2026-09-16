package com.flowdesk.agent.ai;

import com.flowdesk.application.knowledge.port.in.RetrieveKnowledgeUseCase;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 编排装配。
 *
 * <p>整个配置只在 {@code flowdesk.ai.enabled=true} 时生效：默认 profile 下不会创建任何
 * AI Bean，因此没有 API Key 也能启动，也不可能发起模型调用。</p>
 *
 * <p>这里只接收一个按名称限定的 {@link ChatClient}，不感知它是如何构造的 ——
 * 具体模型适配器属于 infrastructure 的职责。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "flowdesk.ai.enabled", havingValue = "true")
public class FlowDeskAiConfiguration {

    /**
     * 注册本地只读工具集。它不实现 {@code ToolCallbackProvider}，
     * 因此不会被自动注册为全局默认工具。
     */
    @Bean
    public SupportPolicyTools supportPolicyTools() {
        return new SupportPolicyTools();
    }

    /**
     * 注册 AI 用例实现；该 Bean 同时实现 {@code AiChatUseCase} 与 {@code AiToolSmokeUseCase}，
     * 上游按接口类型注入即可，无需知道具体实现类。
     */
    @Bean
    public ChatClientAiService chatClientAiService(
            @Qualifier("deepSeekChatClient") ChatClient deepSeekChatClient,
            SupportPolicyTools supportPolicyTools) {
        return new ChatClientAiService(deepSeekChatClient, supportPolicyTools);
    }

    /**
     * 基于检索证据的知识库问答实现（RAG 5/6）。
     *
     * <p>依赖两件事：检索用例（输入的规范化与校验唯一入口，也是「无证据不调用模型」的判断依据）
     * 与命名明确的 {@code deepSeekChatClient}。本 Bean 只在 {@code flowdesk.ai.enabled=true}
     * 时存在，因此默认 profile 下不会有任何问答能力，也不会有出网可能。</p>
     *
     * <p>注意：<b>Chat 走 DeepSeek，Embedding 走 DashScope</b>，两者职责不混用 ——
     * 本实现不接触任何 Embedding 适配器，查询向量由检索用例在 knowledge 链路内完成。</p>
     *
     * @param retrieveKnowledgeUseCase 检索用例（含输入校验与 503 开关判断）
     * @param deepSeekChatClient       DeepSeek 对话客户端
     * @return 知识库问答用例实现
     */
    @Bean
    public GroundedKnowledgeAnswerService groundedKnowledgeAnswerService(
            RetrieveKnowledgeUseCase retrieveKnowledgeUseCase,
            @Qualifier("deepSeekChatClient") ChatClient deepSeekChatClient) {
        return new GroundedKnowledgeAnswerService(retrieveKnowledgeUseCase, deepSeekChatClient);
    }
}
