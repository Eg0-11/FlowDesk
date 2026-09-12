package com.flowdesk.bootstrap.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowdesk.agent.ai.ToolInvocationRecorder;
import com.flowdesk.application.ai.AiChatUseCase;
import com.flowdesk.application.ai.AiToolSmokeUseCase;
import com.flowdesk.application.ai.ChatCommand;
import com.flowdesk.application.ai.ChatResult;
import com.flowdesk.application.ai.ToolCallOutcome;
import com.flowdesk.application.ai.ToolSmokeCommand;
import com.flowdesk.application.ai.ToolSmokeResult;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 生产链路集成测试。
 *
 * <p>本测试<b>只通过 application 层的用例接口驱动</b>（{@link AiChatUseCase}、
 * {@link AiToolSmokeUseCase}），不注入 {@code ChatClient} 或 {@code SupportPolicyTools}，
 * 因此经过的是完整生产链路：Controller 层所依赖的用例 → {@code ChatClientAiService} 编排
 * → Spring AI {@code ChatClient} → 真实工具执行 → 请求级记录器。</p>
 *
 * <p>模型侧由 {@link SyntheticOpenAiEndpoint} 承担（本地回环，不是 DeepSeek），
 * 它同时记录每一轮真实发出的请求体，使本测试能够断言“模型究竟收到了什么”。</p>
 */
@SpringBootTest(properties = {
        "spring.profiles.active=deepseek",
        "DEEPSEEK_API_KEY=test-fake-key-not-a-real-secret"
})
class ToolCallingLoopTests {

    private static SyntheticOpenAiEndpoint endpoint;

    @Autowired
    private AiChatUseCase aiChatUseCase;

    @Autowired
    private AiToolSmokeUseCase aiToolSmokeUseCase;

    @DynamicPropertySource
    static void pointAtSyntheticEndpoint(DynamicPropertyRegistry registry) throws IOException {
        if (endpoint == null) {
            endpoint = SyntheticOpenAiEndpoint.start(SyntheticOpenAiEndpoint.Behaviour.TOOL_CALL_THEN_ANSWER);
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
    void plainChatSendsNoToolsAtAll() {
        ChatResult result = this.aiChatUseCase.chat(new ChatCommand("请用一句话介绍 FlowDesk"));

        assertThat(result.requestId()).isNotBlank();
        assertThat(result.answer()).isEqualTo(SyntheticOpenAiEndpoint.PLAIN_ANSWER);

        List<SyntheticOpenAiEndpoint.CapturedRequest> requests = endpoint.requests();
        assertThat(requests).hasSize(1);
        SyntheticOpenAiEndpoint.CapturedRequest request = requests.get(0);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.model()).isEqualTo("deepseek-flash");
        assertThat(request.hasTools()).as("普通聊天绝不能把工具发给模型").isFalse();
        assertThat(request.hasToolResult()).isFalse();
        assertThat(request.thinkingType()).isEqualTo("disabled");
    }

    @Test
    void toolSmokeRunsTheWholeLoopThroughTheUseCaseInterface() {
        ToolSmokeResult result = this.aiToolSmokeUseCase.toolSmoke(new ToolSmokeCommand("VPN_FAILURE"));

        // 7) 最终结果的工具调用摘要正确
        assertThat(result.requestId()).isNotBlank();
        assertThat(result.toolCalled()).isTrue();
        assertThat(result.toolCalls())
                .containsExactly(new ToolCallOutcome("lookup_support_policy", true));
        // 4) 最终回答来自第二轮
        assertThat(result.answer()).isEqualTo(SyntheticOpenAiEndpoint.FINAL_ANSWER);

        List<SyntheticOpenAiEndpoint.CapturedRequest> requests = endpoint.requests();
        assertThat(requests).hasSize(2);

        SyntheticOpenAiEndpoint.CapturedRequest first = requests.get(0);
        SyntheticOpenAiEndpoint.CapturedRequest second = requests.get(1);

        // 2) 第一轮确实把工具提供给了模型
        assertThat(first.hasTools()).isTrue();
        assertThat(first.containsText("lookup_support_policy")).isTrue();
        assertThat(first.hasToolResult()).isFalse();

        // 3) 工具被真实执行，且结果在第二轮回传（P1 / 网络与接入组来自真实工具返回值）
        assertThat(second.hasToolResult()).isTrue();
        assertThat(second.containsText("VPN_FAILURE")).isTrue();
        assertThat(second.containsText("P1")).isTrue();
        assertThat(second.containsText("网络与接入组")).isTrue();

        // 5) 两轮都必须关闭 thinking
        assertThat(first.thinkingType()).isEqualTo("disabled");
        assertThat(second.thinkingType()).isEqualTo("disabled");
    }

    @Test
    void neverLeaksToolContextOrRequestIdIntoModelRequests() {
        ToolSmokeResult result = this.aiToolSmokeUseCase.toolSmoke(new ToolSmokeCommand("ACCOUNT_LOCK"));

        assertThat(endpoint.requests()).isNotEmpty();
        assertThat(endpoint.requests()).allSatisfy(request -> {
            assertThat(request.containsText(ToolInvocationRecorder.CONTEXT_KEY))
                    .as("调用记录器不得进入模型请求体").isFalse();
            assertThat(request.containsText(ToolInvocationRecorder.REQUEST_ID_KEY)).isFalse();
            assertThat(request.containsText(result.requestId()))
                    .as("requestId 不得进入模型请求体").isFalse();
        });
    }
}
