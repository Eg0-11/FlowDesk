package com.flowdesk.bootstrap.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowdesk.agent.ai.SupportPolicyTools;
import com.flowdesk.application.ai.AiChatUseCase;
import com.flowdesk.application.ai.AiToolSmokeUseCase;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 默认 profile 的启动契约：没有 DeepSeek 环境变量时必须能启动，并且不存在任何可发起模型调用的组件。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AiDisabledContextTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void startsWithAiDisabledAndWithoutAnyModelInfrastructure() {
        assertThat(applicationContext.getEnvironment().getProperty("flowdesk.ai.enabled", Boolean.class)).isFalse();
        assertThat(applicationContext.getEnvironment().getProperty("spring.ai.model.chat")).isEqualTo("none");

        // 没有任何 ChatModel / ChatClient Bean，也就不存在发起网络调用的可能
        assertThat(applicationContext.getBeanNamesForType(ChatModel.class)).isEmpty();
        assertThat(applicationContext.getBeanNamesForType(ChatClient.class)).isEmpty();

        assertThat(applicationContext.getBeanNamesForType(AiChatUseCase.class)).isEmpty();
        assertThat(applicationContext.getBeanNamesForType(AiToolSmokeUseCase.class)).isEmpty();
        assertThat(applicationContext.getBeanNamesForType(SupportPolicyTools.class)).isEmpty();
    }

    @Test
    void aiEndpointsAreNotRegisteredWhenAiIsDisabled() {
        ResponseEntity<String> response = restTemplate.postForEntity("/api/v1/ai/chat",
                Map.of("message", "请用一句话介绍 FlowDesk"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void actuatorHealthStillWorks() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("UP");
    }
}
