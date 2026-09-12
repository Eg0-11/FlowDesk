package com.flowdesk.agent.ai;

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
}
