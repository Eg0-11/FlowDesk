package com.flowdesk.bootstrap.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowdesk.application.ai.AiProviderException;
import com.flowdesk.application.ai.AiToolSmokeUseCase;
import com.flowdesk.application.ai.ToolSmokeCommand;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.flowdesk.bootstrap.web.FlowDeskProblems;

/**
 * 「模型跳过工具」不得算成功。
 *
 * <p>合成端点在这里被配置为无论是否携带工具都直接返回文本，模拟模型无视工具自行作答。
 * 此时工具确实被提供给了模型（请求体里有 tools），但一次都没有被执行，
 * 因此链路必须失败：use case 抛 {@link AiProviderException}，HTTP 映射为 502，
 * 并保留 requestId；<b>绝不能</b>返回 {@code 200 + toolCalled=false}。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=deepseek",
        "DEEPSEEK_API_KEY=test-fake-key-not-a-real-secret"
})
class ToolSmokeRequiresRealToolCallTests {

    private static SyntheticOpenAiEndpoint endpoint;

    @Autowired
    private AiToolSmokeUseCase aiToolSmokeUseCase;

    @Autowired
    private TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void pointAtSkippingModel(DynamicPropertyRegistry registry) throws IOException {
        if (endpoint == null) {
            endpoint = SyntheticOpenAiEndpoint.start(SyntheticOpenAiEndpoint.Behaviour.ANSWER_ONLY);
        }
        registry.add("spring.ai.openai.base-url", endpoint::baseUrl);
    }

    @AfterAll
    static void stopEndpoint() {
        if (endpoint != null) {
            endpoint.stop();
        }
    }

    @BeforeEach
    void resetEndpoint() {
        endpoint.clearRequests();
    }

    @Test
    void directUseCaseRejectsASkippedToolCall() {
        assertThatThrownBy(() -> this.aiToolSmokeUseCase.toolSmoke(new ToolSmokeCommand("VPN_FAILURE")))
                .isInstanceOf(AiProviderException.class);

        // 工具确实交给了模型，只是模型没用 —— 说明失败原因在链路而不是在注册
        assertThat(endpoint.requests()).isNotEmpty();
        assertThat(endpoint.requests().get(0).hasTools()).isTrue();
    }

    @Test
    void httpMapsASkippedToolCallToBadGatewayWithRequestId() {
        ResponseEntity<String> response = this.restTemplate.postForEntity("/api/v1/ai/tool-smoke",
                Map.of("issueType", "VPN_FAILURE"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        String body = response.getBody();
        assertThat(body).contains(FlowDeskProblems.CODE_AI_PROVIDER_ERROR);
        assertThat(body).contains("requestId");
        // 不得退化成 200 + toolCalled=false
        assertThat(body).doesNotContain("toolCalled");
        assertThat(body).doesNotContain("lookup_support_policy");
    }
}
