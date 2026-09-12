package com.flowdesk.infrastructure.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * DeepSeek 模型适配装配。
 *
 * <p>本类只做一件事：把 Spring AI 自动配置出来的 {@link ChatModel} 包装成一个
 * 命名明确的 {@link ChatClient} Bean，供 agent 层按名称注入。</p>
 *
 * <p>刻意不注册任何默认工具、默认系统提示词或默认 Advisor —— 工具注册属于每次请求的编排决策，
 * 由 agent 层在单次调用上显式完成。</p>
 *
 * <p>整份配置只在 {@code flowdesk.ai.enabled=true} 时生效；默认 profile 下连 ChatModel 都不会被
 * 自动配置（{@code spring.ai.model.chat=none}），因此不存在任何出网可能。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "flowdesk.ai.enabled", havingValue = "true")
public class DeepSeekChatClientConfiguration {

    /**
     * DeepSeek 对话客户端：底层是 OpenAI 兼容的 Chat Completions 接口。
     *
     * @param chatModel Spring AI 自动配置的模型（deepseek profile 下由 OpenAI 适配器提供）
     * @return 命名明确的 ChatClient
     */
    @Bean("deepSeekChatClient")
    public ChatClient deepSeekChatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }
}
