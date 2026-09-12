package com.flowdesk.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.util.Objects;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.util.DefaultUriBuilderFactory;

/**
 * 在 HTTP 传输层为发往 DeepSeek Chat Completions 端点的请求补上「关闭 thinking」标记。
 *
 * <p><b>为什么需要它</b>：Spring AI 1.1.2 的 {@code OpenAiChatModel.createRequest} 在注册了工具时
 * 会做第二次 {@code ModelOptionsUtils.merge}（把 tools 数组并进请求），这次合并会把 {@code extraBody}
 * 清空，导致配置里的 {@code extra-body: thinking.type=disabled} 在工具调用路径失效 —— 而工具调用
 * 恰恰是必须关闭 thinking 的场景。详见 {@code docs/adr/0001-deepseek-openai-compatible-transport.md}。</p>
 *
 * <p><b>目标端点如何确定</b>：这里不自行拼接路径。<code>OpenAiApi</code> 实际执行的是
 * {@code restClientBuilder.clone().baseUrl(baseUrl)} 再 {@code .post().uri(completionsPath)}，
 * 而 {@code RestClient.Builder.baseUrl(String)} 内部就是一个 {@link DefaultUriBuilderFactory}。
 * 因此本类用同一个工厂解析出目标 URI：
 * {@code new DefaultUriBuilderFactory(baseUrl).expand(completionsPath)}。
 * 该等价性由 {@code DeepSeekEndpointResolutionTests} 用 MockRestServiceServer 实测比对。</p>
 *
 * <p>特别注意：不能自行给缺前导斜杠的 completions-path 补斜杠。当 base-url 自带路径时，
 * Spring 的解析结果是直接拼接而不插入分隔符，例如
 * {@code base=https://gw.example.com/openai/v1} + {@code path=chat/completions}
 * 解析为 {@code https://gw.example.com/openai/v1chat/completions}；自行补斜杠会得到
 * {@code .../openai/v1/chat/completions}，与真实请求不符。</p>
 *
 * <p><b>作用域</b>：只修改同时满足下列全部条件的请求，任何一条不满足都按原始字节透传：</p>
 * <ol>
 *   <li>HTTP 方法为 {@code POST}；</li>
 *   <li>{@code Content-Type} 的类型为 {@code application} 且子类型为 {@code json}
 *       或 {@code *+json}（因此 {@code text/vendor+json} 不会被接受）；</li>
 *   <li>scheme、host 与有效端口与解析出的目标 URI 一致；</li>
 *   <li>path 与解析出的目标 URI 完全一致。</li>
 * </ol>
 *
 * <p>请求体已声明 {@code thinking.type=disabled} 时不再改动，避免重复写入或破坏原始字节。</p>
 */
class DeepSeekThinkingDisabledInterceptor implements ClientHttpRequestInterceptor {

    private static final String THINKING = "thinking";

    private static final String TYPE = "type";

    private static final String DISABLED = "disabled";

    private static final String APPLICATION_TYPE = "application";

    private static final String JSON_SUBTYPE = "json";

    private static final String JSON_SUBTYPE_SUFFIX = "+json";

    private final ObjectMapper objectMapper;

    private final String expectedScheme;

    private final String expectedHost;

    private final int expectedPort;

    private final String expectedPath;

    /**
     * @param objectMapper    与 Spring Boot 共用的 JSON 映射器
     * @param baseUrl         配置的 DeepSeek base-url
     * @param completionsPath 配置的 completions-path
     */
    DeepSeekThinkingDisabledInterceptor(ObjectMapper objectMapper, String baseUrl, String completionsPath) {
        this.objectMapper = objectMapper;
        URI endpoint = new DefaultUriBuilderFactory(baseUrl).expand(completionsPath);
        this.expectedScheme = endpoint.getScheme();
        this.expectedHost = endpoint.getHost();
        this.expectedPort = effectivePort(endpoint);
        this.expectedPath = endpoint.getPath();
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {

        if (body.length == 0 || !isDeepSeekChatCompletions(request) || isThinkingAlreadyDisabled(body)) {
            return execution.execute(request, body);
        }
        return execution.execute(request, bodyWithThinkingDisabled(body));
    }

    /**
     * 判断该请求是否确实是发往配置的 DeepSeek Chat Completions 端点。
     */
    private boolean isDeepSeekChatCompletions(HttpRequest request) {
        if (!HttpMethod.POST.equals(request.getMethod())) {
            return false;
        }
        if (!isJsonRequest(request)) {
            return false;
        }
        URI uri = request.getURI();
        return equalsIgnoreCase(this.expectedScheme, uri.getScheme())
                && equalsIgnoreCase(this.expectedHost, uri.getHost())
                && this.expectedPort == effectivePort(uri)
                && Objects.equals(this.expectedPath, uri.getPath());
    }

    private boolean isJsonRequest(HttpRequest request) {
        MediaType contentType = request.getHeaders().getContentType();
        if (contentType == null || !APPLICATION_TYPE.equalsIgnoreCase(contentType.getType())) {
            return false;
        }
        if (MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
            return true;
        }
        String subtype = contentType.getSubtype();
        return subtype != null
                && (JSON_SUBTYPE.equalsIgnoreCase(subtype) || subtype.toLowerCase().endsWith(JSON_SUBTYPE_SUFFIX));
    }

    private boolean isThinkingAlreadyDisabled(byte[] body) throws IOException {
        JsonNode thinking = this.objectMapper.readTree(body).path(THINKING);
        return DISABLED.equals(thinking.path(TYPE).asText());
    }

    private byte[] bodyWithThinkingDisabled(byte[] body) throws IOException {
        JsonNode root = this.objectMapper.readTree(body);
        if (!(root instanceof ObjectNode requestBody)) {
            return body;
        }
        requestBody.set(THINKING, requestBody.objectNode().put(TYPE, DISABLED));
        return this.objectMapper.writeValueAsBytes(requestBody);
    }

    private static boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    /**
     * 端口缺省时按 scheme 推导有效端口，避免 {@code https://host} 与 {@code https://host:443} 被判为不同。
     */
    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        String scheme = uri.getScheme();
        if ("https".equalsIgnoreCase(scheme)) {
            return 443;
        }
        if ("http".equalsIgnoreCase(scheme)) {
            return 80;
        }
        return -1;
    }
}
