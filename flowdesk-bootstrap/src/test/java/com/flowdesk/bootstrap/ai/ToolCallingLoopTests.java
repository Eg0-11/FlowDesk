package com.flowdesk.bootstrap.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowdesk.agent.ai.SupportPolicyTools;
import com.flowdesk.agent.ai.ToolInvocation;
import com.flowdesk.agent.ai.ToolInvocationRecorder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 工具调用闭环测试。
 *
 * <p>本测试把 {@code spring.ai.openai.base-url} 指向一个在测试内启动的合成
 * OpenAI 兼容端点，其余部分（真实 profile 配置、真实 OpenAiChatModel、真实 ChatClient、
 * 真实工具执行与记录器）全部走生产代码路径。合成端点第一轮返回 tool_calls，
 * 第二轮返回最终回答，因此它验证的是完整闭环：模型要求调用工具 → 真实 Java 工具执行
 * → 工具结果回传 → 模型再次生成最终回答。</p>
 *
 * <p>它同时把真实发出的请求体抓下来，用于断言 {@code thinking.type=disabled} 确实上了线。</p>
 */
@SpringBootTest(properties = {
        "spring.profiles.active=deepseek",
        "DEEPSEEK_API_KEY=test-fake-key-not-a-real-secret"
})
class ToolCallingLoopTests {

    private static final List<String> REQUEST_BODIES = Collections.synchronizedList(new ArrayList<>());

    private static final String FINAL_ANSWER = "该工单按 P1 转派网络与接入组，首个动作是采集 VPN 客户端日志。";

    private static HttpServer stubServer;

    @Autowired
    @Qualifier("deepSeekChatClient")
    private ChatClient deepSeekChatClient;

    @Autowired
    private SupportPolicyTools supportPolicyTools;

    @DynamicPropertySource
    static void pointAtSyntheticEndpoint(DynamicPropertyRegistry registry) throws IOException {
        stubServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stubServer.createContext("/chat/completions", ToolCallingLoopTests::handle);
        stubServer.start();
        registry.add("spring.ai.openai.base-url", () -> "http://127.0.0.1:" + stubServer.getAddress().getPort());
    }

    @AfterAll
    static void stopStubServer() {
        if (stubServer != null) {
            stubServer.stop(0);
        }
    }

    @BeforeEach
    void clearCapturedRequests() {
        REQUEST_BODIES.clear();
    }

    @Test
    void completesToolCallingLoopAndReturnsFinalAnswer() {
        ToolInvocationRecorder recorder = new ToolInvocationRecorder();

        String answer = callWithTool(recorder, "loop-test-request-id");

        assertThat(answer).isEqualTo(FINAL_ANSWER);
        assertThat(recorder.invocations())
                .containsExactly(new ToolInvocation(SupportPolicyTools.TOOL_NAME, true));
        assertThat(recorder.anySucceeded()).isTrue();
        // 两轮请求：第一轮要求调用工具，第二轮携带工具结果并要求最终回答
        assertThat(REQUEST_BODIES).hasSize(2);
    }

    @Test
    void sendsThinkingDisabledAndConfiguredModelOnTheWire() {
        ToolInvocationRecorder recorder = new ToolInvocationRecorder();

        callWithTool(recorder, "wire-test-request-id");

        String firstRound = REQUEST_BODIES.get(0);
        assertThat(firstRound).contains("\"model\":\"deepseek-flash\"");
        assertThat(firstRound).contains("\"thinking\":{\"type\":\"disabled\"}");
        assertThat(firstRound).contains("\"temperature\":0.2");
        assertThat(firstRound).contains("lookup_support_policy");

        String secondRound = REQUEST_BODIES.get(1);
        // 第二轮必须把真实工具执行结果回传给模型
        assertThat(secondRound).contains("lookup_support_policy");
        assertThat(secondRound).contains("VPN_FAILURE");
        assertThat(secondRound).contains("P1");
    }

    @Test
    void doesNotExposeToolContextToTheModel() {
        ToolInvocationRecorder recorder = new ToolInvocationRecorder();

        callWithTool(recorder, "context-isolation-request-id");

        assertThat(REQUEST_BODIES.get(0)).doesNotContain(ToolInvocationRecorder.CONTEXT_KEY);
    }

    private String callWithTool(ToolInvocationRecorder recorder, String requestId) {
        return deepSeekChatClient.prompt()
                .user("工单类型：VPN_FAILURE，请先查询支持策略再给出处置建议。")
                .tools(supportPolicyTools)
                .toolContext(Map.of(
                        ToolInvocationRecorder.CONTEXT_KEY, recorder,
                        ToolInvocationRecorder.REQUEST_ID_KEY, requestId))
                .call()
                .content();
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        REQUEST_BODIES.add(body);

        boolean secondRound = body.contains("\"role\":\"tool\"");
        byte[] payload = (secondRound ? finalResponse() : toolCallResponse()).getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private static String toolCallResponse() {
        return """
                {
                  "id": "chatcmpl-stub-1",
                  "object": "chat.completion",
                  "created": 1700000000,
                  "model": "deepseek-flash",
                  "choices": [{
                    "index": 0,
                    "message": {
                      "role": "assistant",
                      "content": "",
                      "tool_calls": [{
                        "id": "call_stub_1",
                        "type": "function",
                        "function": {
                          "name": "lookup_support_policy",
                          "arguments": "{\\"issueType\\":\\"VPN_FAILURE\\"}"
                        }
                      }]
                    },
                    "finish_reason": "tool_calls"
                  }],
                  "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
                }
                """;
    }

    private static String finalResponse() {
        return """
                {
                  "id": "chatcmpl-stub-2",
                  "object": "chat.completion",
                  "created": 1700000001,
                  "model": "deepseek-flash",
                  "choices": [{
                    "index": 0,
                    "message": {
                      "role": "assistant",
                      "content": "%s"
                    },
                    "finish_reason": "stop"
                  }],
                  "usage": {"prompt_tokens": 20, "completion_tokens": 9, "total_tokens": 29}
                }
                """.formatted(FINAL_ANSWER);
    }
}
