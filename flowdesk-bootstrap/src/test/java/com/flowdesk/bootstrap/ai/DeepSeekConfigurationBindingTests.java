package com.flowdesk.bootstrap.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * deepseek profile 的配置绑定测试：确认模型名、completions 路径与 extra-body 真的绑定到了属性对象上。
 */
@SpringBootTest(properties = {
        "spring.profiles.active=deepseek",
        "DEEPSEEK_API_KEY=test-fake-key-not-a-real-secret"
})
class DeepSeekConfigurationBindingTests {

    @Autowired
    private OpenAiChatProperties chatProperties;

    @Test
    void bindsConfiguredModelName() {
        assertThat(chatProperties.getOptions().getModel()).isEqualTo("deepseek-flash");
    }

    @Test
    void bindsCompletionsPath() {
        assertThat(chatProperties.getCompletionsPath()).isEqualTo("/chat/completions");
    }

    @Test
    void bindsThinkingDisabledIntoExtraBody() {
        assertThat(chatProperties.getOptions().getExtraBody())
                .containsEntry("thinking", Map.of("type", "disabled"));
    }
}
