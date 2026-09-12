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

/**
 * 在 HTTP 传输层为发往 DeepSeek Chat Completions 端点的请求补上「关闭 thinking」标记。
 *
 * <p><b>为什么需要它</b>：Spring AI 1.1.2 的 {@code OpenAiChatModel.createRequest} 在注册了工具时
 * 会做第二次 {@code ModelOptionsUtils.merge}（把 tools 数组并进请求），这次合并会把 {@code extraBody}
 * 清空，导致配置里的 {@code extra-body: thinking.type=disabled} 在工具调用路径失效 —— 而工具调用
 * 恰恰是必须关闭 thinking 的场景。详见 {@code docs/adr/0001-deepseek-openai-compatible-transport.md}。</p>
 *
 * <p><b>作用域</b>：只修改同时满足下列全部条件的请求，任何一条不满足都原样透传：</p>
 * <ol>
 *   <li>HTTP 方法为 {@code POST}；</li>
 *   <li>{@code Content-Type} 为 JSON（{@code application/json} 或 {@code application/*+json}）；</li>
 *   <li>scheme、host 与有效端口与配置的 DeepSeek base-url 完全一致；</li>
 *   <li>path 与「base-url 的 path + 配置的 completions-path」完全一致。</li>
 * </ol>
 *
 * <p>不使用 {@code endsWith("/chat/completions")} 这类宽松判断：同一个 JVM 里的其它客户端
 * （向量库、其它 OpenAI 兼容提供方、本地回环服务）都可能命中同名路径，宽判断会误改它们的请求。</p>
 *
 * <p>请求体已声明 {@code thinking.type=disabled} 时不再改动，避免重复写入或破坏原始字节。</p>
 */
class DeepSeekThinkingDisabledInterceptor implements ClientHttpRequestInterceptor {

    private static final String THINKING = "thinking";

    private static final String TYPE = "type";

    private static final String DISABLED = "disabled";

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
        URI base = URI.create(baseUrl.trim());
        this.expectedScheme = base.getScheme();
        this.expectedHost = base.getHost();
        this.expectedPort = effectivePort(base);
        this.expectedPath = joinPaths(base.getPath(), completionsPath);
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
        return equalsIgnoreCase(expectedScheme, uri.getScheme())
                && equalsIgnoreCase(expectedHost, uri.getHost())
                && expectedPort == effectivePort(uri)
                && Objects.equals(expectedPath, normalizePath(uri.getPath()));
    }

    private boolean isJsonRequest(HttpRequest request) {
        MediaType contentType = request.getHeaders().getContentType();
        if (contentType == null) {
            return false;
        }
        if (MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
            return true;
        }
        String subtype = contentType.getSubtype();
        return subtype != null && subtype.endsWith(JSON_SUBTYPE_SUFFIX);
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

    /**
     * 把 base-url 的 path 与 completions-path 拼成一个确定路径，容忍多余的斜杠。
     */
    private static String joinPaths(String basePath, String completionsPath) {
        String prefix = (basePath == null || basePath.isEmpty() || "/".equals(basePath))
                ? "" : stripTrailingSlash(basePath);
        if (completionsPath == null || completionsPath.isEmpty()) {
            return prefix;
        }
        String suffix = completionsPath.startsWith("/") ? completionsPath : "/" + completionsPath;
        return prefix + suffix;
    }

    private static String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        return path;
    }

    private static String stripTrailingSlash(String value) {
        String result = value;
        while (result.length() > 1 && result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
