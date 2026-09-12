package com.flowdesk.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * DeepSeek 传输层定制。
 *
 * <p>把 {@link DeepSeekThinkingDisabledInterceptor} 挂到 Spring Boot 自动配置的
 * {@link RestClient.Builder} 上；Spring AI 的 OpenAI 适配器正是用这个 Builder 构造 OpenAiApi，
 * 因此我们仍然使用自动配置出来的 ChatModel，只是在请求真正发出前修正请求体。</p>
 *
 * <p>只在 {@code flowdesk.ai.enabled=true} 时生效。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "flowdesk.ai.enabled", havingValue = "true")
public class DeepSeekTransportConfiguration {

    /**
     * 注册传输层拦截器。
     *
     * @param objectMapper 与 Spring Boot 共用的 JSON 映射器
     * @return RestClient 定制器
     */
    @Bean
    RestClientCustomizer deepSeekThinkingDisabledCustomizer(ObjectMapper objectMapper) {
        return builder -> builder.requestInterceptor(new DeepSeekThinkingDisabledInterceptor(objectMapper));
    }
}
