package com.flowdesk.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * 在 HTTP 传输层为 DeepSeek 的 Chat Completions 请求补上「关闭 thinking」标记。
 *
 * <p>为什么需要它：Spring AI 1.1.2 的 {@code OpenAiChatModel.createRequest} 在注册了工具时
 * 会做第二次 {@code ModelOptionsUtils.merge}（把 tools 数组并进请求），而这次合并会把
 * {@code extraBody} 清空。实测结果：</p>
 *
 * <pre>
 * 第一次 merge 后: {"messages":[],"model":"deepseek-flash","thinking":{"type":"disabled"}}
 * 第二次 merge 后: {"messages":[],"model":"deepseek-flash"}            ← thinking 被抹掉
 * </pre>
 *
 * <p>也就是说，配置里的 {@code extra-body} 在普通聊天路径有效，但在工具调用路径失效 ——
 * 而工具调用恰恰是必须关闭 thinking 的场景。本拦截器在请求真正发出前把该字段补回去，
 * 使两条路径的行为一致。</p>
 *
 * <p>只处理路径以 {@code /chat/completions} 结尾的请求，且当请求体已声明
 * {@code thinking.type=disabled} 时不重复写入。当 Spring AI 修复上述合并缺陷后，本类可以整体删除。</p>
 */
class DeepSeekThinkingDisabledInterceptor implements ClientHttpRequestInterceptor {

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private static final String THINKING = "thinking";

    private static final String TYPE = "type";

    private static final String DISABLED = "disabled";

    private final ObjectMapper objectMapper;

    DeepSeekThinkingDisabledInterceptor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {

        if (body.length == 0 || !isChatCompletionRequest(request) || isThinkingAlreadyDisabled(body)) {
            return execution.execute(request, body);
        }
        return execution.execute(request, bodyWithThinkingDisabled(body));
    }

    private boolean isChatCompletionRequest(HttpRequest request) {
        return request.getURI().getPath().endsWith(CHAT_COMPLETIONS_PATH);
    }

    private boolean isThinkingAlreadyDisabled(byte[] body) throws IOException {
        JsonNode thinking = objectMapper.readTree(body).path(THINKING);
        return DISABLED.equals(thinking.path(TYPE).asText());
    }

    private byte[] bodyWithThinkingDisabled(byte[] body) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        if (!(root instanceof ObjectNode requestBody)) {
            return body;
        }
        requestBody.set(THINKING, requestBody.objectNode().put(TYPE, DISABLED));
        return objectMapper.writeValueAsBytes(requestBody);
    }
}
