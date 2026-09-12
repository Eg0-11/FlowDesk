package com.flowdesk.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiConnectionProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * DeepSeek 传输层定制。
 *
 * <p>把 {@link DeepSeekThinkingDisabledInterceptor} 挂到 Spring Boot 自动配置的
 * {@link RestClient.Builder} 上；Spring AI 的 OpenAI 适配器正是用这个 Builder 构造 {@code OpenAiApi}，
 * 因此仍然使用自动配置出来的 {@code ChatModel}，只在请求真正发出前修正请求体。</p>
 *
 * <p>拦截器的目标端点按 Spring AI 自身的解析规则计算，保证与实际使用的端点一致：
 * base-url 取 {@code spring.ai.openai.chat.base-url} 优先、否则 {@code spring.ai.openai.base-url}；
 * completions-path 取 {@code spring.ai.openai.chat.completions-path}。</p>
 *
 * <p>只在 {@code flowdesk.ai.enabled=true} 时生效。该定制只覆盖阻塞式 {@link RestClient}
 * （即非流式调用）；FlowDesk 当前不使用流式输出。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "flowdesk.ai.enabled", havingValue = "true")
public class DeepSeekTransportConfiguration {

    /**
     * 注册传输层拦截器。
     *
     * @param objectMapper        与 Spring Boot 共用的 JSON 映射器
     * @param connectionProperties {@code spring.ai.openai.*} 连接属性
     * @param chatProperties       {@code spring.ai.openai.chat.*} 对话属性
     * @return RestClient 定制器
     */
    @Bean
    RestClientCustomizer deepSeekThinkingDisabledCustomizer(ObjectMapper objectMapper,
            OpenAiConnectionProperties connectionProperties,
            OpenAiChatProperties chatProperties) {

        String baseUrl = StringUtils.hasText(chatProperties.getBaseUrl())
                ? chatProperties.getBaseUrl() : connectionProperties.getBaseUrl();
        DeepSeekThinkingDisabledInterceptor interceptor =
                new DeepSeekThinkingDisabledInterceptor(objectMapper, baseUrl, chatProperties.getCompletionsPath());
        return builder -> builder.requestInterceptor(interceptor);
    }
}
