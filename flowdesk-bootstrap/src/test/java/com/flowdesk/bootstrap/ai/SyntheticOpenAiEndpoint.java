package com.flowdesk.bootstrap.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 测试用的合成 OpenAI 兼容端点。
 *
 * <p>它不是 DeepSeek，也不会访问网络：只在本地回环端口上按行为脚本应答，
 * 并把收到的每一个请求（方法、Content-Type、URI、请求体）原样记录下来，
 * 供测试断言真实发往模型端的报文。</p>
 */
final class SyntheticOpenAiEndpoint {

    /** 应答行为。 */
    enum Behaviour {

        /** 携带工具时先返回一次 tool_calls，收到工具结果后返回最终回答 —— 正常闭环。 */
        TOOL_CALL_THEN_ANSWER,

        /** 无论是否携带工具都直接返回文本 —— 模拟“模型跳过工具”。 */
        ANSWER_ONLY
    }

    static final String PLAIN_ANSWER = "FlowDesk 是一个把企业工单流转与知识运营打通并引入大模型能力的平台。";

    static final String FINAL_ANSWER = "该工单按 P1 优先级转派网络与接入组，首个处理动作是采集 VPN 客户端日志并切换备用接入点。";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;

    private final List<CapturedRequest> requests = Collections.synchronizedList(new ArrayList<>());

    private final Behaviour behaviour;

    private SyntheticOpenAiEndpoint(Behaviour behaviour) throws IOException {
        this.behaviour = behaviour;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/chat/completions", this::handle);
        this.server.start();
    }

    /**
     * 启动一个端点实例。
     */
    static SyntheticOpenAiEndpoint start(Behaviour behaviour) throws IOException {
        return new SyntheticOpenAiEndpoint(behaviour);
    }

    /**
     * @return 可直接作为 {@code spring.ai.openai.base-url} 使用的地址
     */
    String baseUrl() {
        return "http://127.0.0.1:" + this.server.getAddress().getPort();
    }

    List<CapturedRequest> requests() {
        return List.copyOf(this.requests);
    }

    void clearRequests() {
        this.requests.clear();
    }

    void stop() {
        this.server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        this.requests.add(new CapturedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                exchange.getRequestURI().toString(),
                body));

        String response = renderResponse(body);
        byte[] payload = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private String renderResponse(String body) {
        if (this.behaviour == Behaviour.ANSWER_ONLY) {
            return envelope("chatcmpl-stub-plain", PLAIN_ANSWER, "stop", null);
        }
        if (body.contains("\"role\":\"tool\"")) {
            return envelope("chatcmpl-stub-final", FINAL_ANSWER, "stop", null);
        }
        if (body.contains("\"tools\"")) {
            return envelope("chatcmpl-stub-tool", "", "tool_calls",
                    "\"tool_calls\": [{\"id\": \"call_stub_1\", \"type\": \"function\","
                            + " \"function\": {\"name\": \"lookup_support_policy\","
                            + " \"arguments\": \"{\\\"issueType\\\":\\\"VPN_FAILURE\\\"}\"}}]");
        }
        return envelope("chatcmpl-stub-plain", PLAIN_ANSWER, "stop", null);
    }

    private static String envelope(String id, String content, String finishReason, String extraMessageFields) {
        return """
                {
                  "id": "%s",
                  "object": "chat.completion",
                  "created": 1700000000,
                  "model": "deepseek-flash",
                  "choices": [{
                    "index": 0,
                    "message": {"role": "assistant", "content": "%s"%s},
                    "finish_reason": "%s"
                  }],
                  "usage": {"prompt_tokens": 20, "completion_tokens": 10, "total_tokens": 30}
                }
                """.formatted(id, content, extraMessageFields == null ? "" : ", " + extraMessageFields, finishReason);
    }

    /**
     * 一次被记录下来的模型请求。
     *
     * @param method      HTTP 方法
     * @param contentType Content-Type 头
     * @param uri         请求 URI
     * @param body        请求体原文
     */
    record CapturedRequest(String method, String contentType, String uri, String body) {

        private JsonNode json() {
            try {
                return MAPPER.readTree(this.body);
            } catch (IOException ex) {
                throw new IllegalStateException("请求体不是合法 JSON", ex);
            }
        }

        boolean hasTools() {
            JsonNode tools = json().path("tools");
            return tools.isArray() && !tools.isEmpty();
        }

        boolean hasToolResult() {
            return this.body.contains("\"role\":\"tool\"");
        }

        String model() {
            return json().path("model").asText();
        }

        /** @return 请求体声明的 thinking.type，未声明时返回空串 */
        String thinkingType() {
            return json().path("thinking").path("type").asText();
        }

        boolean containsText(String text) {
            return this.body.contains(text);
        }
    }
}
