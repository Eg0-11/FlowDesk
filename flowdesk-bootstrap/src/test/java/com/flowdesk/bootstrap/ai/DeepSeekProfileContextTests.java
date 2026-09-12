package com.flowdesk.bootstrap.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowdesk.agent.ai.SupportPolicyTools;
import com.flowdesk.application.ai.AiChatUseCase;
import com.flowdesk.application.ai.AiToolSmokeUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * deepseek profile 的装配契约：使用测试假 Key 时上下文必须能装配完成，
 * 但本测试只加载上下文，不会发起任何真实模型调用。
 *
 * <p>假 Key 只是为了让自动配置完成 Bean 创建，测试不会触发网络请求。</p>
 */
@SpringBootTest(properties = {
        "spring.profiles.active=deepseek",
        "DEEPSEEK_API_KEY=test-fake-key-not-a-real-secret"
})
class DeepSeekProfileContextTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void assemblesAiBeansWithAFakeKey() {
        assertThat(applicationContext.getEnvironment().getProperty("flowdesk.ai.enabled", Boolean.class)).isTrue();
        assertThat(applicationContext.getEnvironment().getProperty("spring.ai.model.chat")).isEqualTo("openai");

        assertThat(applicationContext.getBeanNamesForType(ChatModel.class)).isNotEmpty();
        assertThat(applicationContext.getBeanNamesForType(ChatClient.class)).contains("deepSeekChatClient");
        assertThat(applicationContext.getBeanNamesForType(AiChatUseCase.class)).hasSize(1);
        assertThat(applicationContext.getBeanNamesForType(AiToolSmokeUseCase.class)).hasSize(1);
        assertThat(applicationContext.getBeanNamesForType(SupportPolicyTools.class)).hasSize(1);
    }

    @Test
    void registersNoDefaultToolProviders() {
        // 基础设施不注册任何默认工具：工具只能在单次请求上通过 .tools(...) 注册
        assertThat(applicationContext.getBeanNamesForType(ToolCallbackProvider.class)).isEmpty();
    }
}
