package com.flowdesk.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

/**
 * 拦截器作用域测试：只有真正发往配置的 DeepSeek Chat Completions 端点的请求才允许被修改。
 *
 * <p>目标端点按 Spring 的解析规则得出（见 {@link DeepSeekEndpointResolutionTests}），
 * 本测试不自行拼接路径。</p>
 */
class DeepSeekThinkingDisabledInterceptorTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String BASE_URL = "https://api.deepseek.com";

    private static final String COMPLETIONS_PATH = "/chat/completions";

    private static final String ENDPOINT = "https://api.deepseek.com/chat/completions";

    private static final String REQUEST_BODY = "{\"model\":\"deepseek-flash\",\"messages\":[],\"tools\":[]}";

    private final DeepSeekThinkingDisabledInterceptor interceptor =
            new DeepSeekThinkingDisabledInterceptor(MAPPER, BASE_URL, COMPLETIONS_PATH);

    // ---------- 正确端点：注入 ----------

    @Test
    void injectsForTheDefaultBaseUrlWithLeadingSlashPath() throws Exception {
        byte[] sent = send(ENDPOINT, HttpMethod.POST, MediaType.APPLICATION_JSON, REQUEST_BODY);

        JsonNode body = MAPPER.readTree(sent);
        assertThat(body.path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(body.path("model").asText()).isEqualTo("deepseek-flash");
        assertThat(body.has("tools")).isTrue();
    }

    @Test
    void injectsForAPathPrefixedBaseUrlWithLeadingSlashPath() throws Exception {
        DeepSeekThinkingDisabledInterceptor prefixed =
                new DeepSeekThinkingDisabledInterceptor(MAPPER, "https://gateway.example.com/openai/v1",
                        COMPLETIONS_PATH);

        byte[] injected = capture(prefixed, "https://gateway.example.com/openai/v1/chat/completions");
        byte[] withoutPrefix = capture(prefixed, "https://gateway.example.com/chat/completions");

        assertThat(MAPPER.readTree(injected).path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(text(withoutPrefix))
                .as("缺少 base-url 路径前缀的 URI 不是目标端点")
                .isEqualTo(REQUEST_BODY);
    }

    @Test
    void injectsWhenTheCompletionsPathHasNoLeadingSlash() throws Exception {
        DeepSeekThinkingDisabledInterceptor relativePath =
                new DeepSeekThinkingDisabledInterceptor(MAPPER, BASE_URL, "chat/completions");

        byte[] sent = capture(relativePath, ENDPOINT);

        assertThat(MAPPER.readTree(sent).path("thinking").path("type").asText()).isEqualTo("disabled");
    }

    @Test
    void injectsOnlyAtTheUriSpringActuallyResolves() throws Exception {
        // base 自带路径 + 无前导斜杠的 completions-path：Spring 直接拼接，不插入分隔符
        DeepSeekThinkingDisabledInterceptor concatenating =
                new DeepSeekThinkingDisabledInterceptor(MAPPER, "https://gateway.example.com/openai/v1",
                        "chat/completions");

        byte[] springUri = capture(concatenating, "https://gateway.example.com/openai/v1chat/completions");
        byte[] handPaddedUri = capture(concatenating, "https://gateway.example.com/openai/v1/chat/completions");

        assertThat(MAPPER.readTree(springUri).path("thinking").path("type").asText())
                .as("Spring 实际解析出的 URI 必须被修改")
                .isEqualTo("disabled");
        assertThat(text(handPaddedUri))
                .as("人工补斜杠后的错误 URI 不得被修改")
                .isEqualTo(REQUEST_BODY);
    }

    @Test
    void injectsWhenTheDefaultPortIsSpelledOutExplicitly() throws Exception {
        byte[] sent = send("https://api.deepseek.com:443/chat/completions", HttpMethod.POST,
                MediaType.APPLICATION_JSON, REQUEST_BODY);

        assertThat(MAPPER.readTree(sent).path("thinking").path("type").asText()).isEqualTo("disabled");
    }

    @Test
    void injectsWhenContentTypeCarriesCharset() throws Exception {
        byte[] sent = send(ENDPOINT, HttpMethod.POST,
                new MediaType("application", "json", Map.of("charset", "UTF-8")), REQUEST_BODY);

        assertThat(MAPPER.readTree(sent).path("thinking").path("type").asText()).isEqualTo("disabled");
    }

    @Test
    void injectsForApplicationProblemJson() throws Exception {
        byte[] sent = send(ENDPOINT, HttpMethod.POST, MediaType.parseMediaType("application/problem+json"),
                REQUEST_BODY);

        assertThat(MAPPER.readTree(sent).path("thinking").path("type").asText()).isEqualTo("disabled");
    }

    @Test
    void injectsForApplicationVendorJson() throws Exception {
        byte[] sent = send(ENDPOINT, HttpMethod.POST, MediaType.parseMediaType("application/vnd.api+json"),
                REQUEST_BODY);

        assertThat(MAPPER.readTree(sent).path("thinking").path("type").asText()).isEqualTo("disabled");
    }

    @Test
    void injectsForUpperCaseJsonMediaType() throws Exception {
        byte[] sent = send(ENDPOINT, HttpMethod.POST, MediaType.parseMediaType("APPLICATION/JSON"), REQUEST_BODY);

        assertThat(MAPPER.readTree(sent).path("thinking").path("type").asText()).isEqualTo("disabled");
    }

    @Test
    void overridesAConflictingThinkingBlock() throws Exception {
        byte[] sent = send(ENDPOINT, HttpMethod.POST, MediaType.APPLICATION_JSON,
                "{\"thinking\":{\"type\":\"enabled\"}}");

        assertThat(MAPPER.readTree(sent).path("thinking").path("type").asText()).isEqualTo("disabled");
    }

    // ---------- 非目标请求：原始字节透传 ----------

    /**
     * 记录 Spring 自身的行为，说明为什么本类不能用
     * {@code MediaType.APPLICATION_JSON.isCompatibleWith(...)} 来判断：
     * 该方法把通配符媒体类型也视为兼容。
     */
    @Test
    void documentsThatIsCompatibleWithTreatsWildcardsAsCompatible() {
        assertThat(MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType("application/*")))
                .as("isCompatibleWith 会把 application/* 判为兼容，因此不能用于本拦截器")
                .isTrue();
    }

    @Test
    void doesNotTouchApplicationWildcard() throws Exception {
        // 通配符媒体类型无法经 setContentType 写入（Spring 会拒绝），因此用原始头设置，
        // 以真实覆盖「收到 application/* 请求」这一情形
        byte[] sent = sendWithRawContentType(ENDPOINT, "application/*", REQUEST_BODY);

        assertThat(text(sent)).isEqualTo(REQUEST_BODY);
    }

    @Test
    void doesNotTouchApplicationXml() throws Exception {
        byte[] sent = send(ENDPOINT, HttpMethod.POST, MediaType.parseMediaType("application/xml"), REQUEST_BODY);

        assertThat(text(sent)).isEqualTo(REQUEST_BODY);
    }

    @Test
    void doesNotTouchTextVendorJson() throws Exception {
        byte[] sent = send(ENDPOINT, HttpMethod.POST, MediaType.parseMediaType("text/vendor+json"), REQUEST_BODY);

        assertThat(text(sent)).isEqualTo(REQUEST_BODY);
    }

    @Test
    void doesNotTouchADifferentHostWithTheSamePath() throws Exception {
        byte[] sent = send("http://127.0.0.1:19099/chat/completions", HttpMethod.POST, MediaType.APPLICATION_JSON,
                REQUEST_BODY);

        assertThat(text(sent)).isEqualTo(REQUEST_BODY);
    }

    @Test
    void doesNotTouchADifferentPortOnTheSameHost() throws Exception {
        byte[] sent = send("https://api.deepseek.com:8443/chat/completions", HttpMethod.POST,
                MediaType.APPLICATION_JSON, REQUEST_BODY);

        assertThat(text(sent)).isEqualTo(REQUEST_BODY);
    }

    @Test
    void doesNotTouchADifferentSchemeOnTheSameHost() throws Exception {
        byte[] sent = send("http://api.deepseek.com/chat/completions", HttpMethod.POST, MediaType.APPLICATION_JSON,
                REQUEST_BODY);

        assertThat(text(sent)).isEqualTo(REQUEST_BODY);
    }

    @Test
    void doesNotTouchAWrongPathOnTheSameHost() throws Exception {
        byte[] sent = send("https://api.deepseek.com/v1/chat/completions", HttpMethod.POST,
                MediaType.APPLICATION_JSON, REQUEST_BODY);

        assertThat(text(sent)).isEqualTo(REQUEST_BODY);
    }

    @Test
    void doesNotTouchANonPostMethod() throws Exception {
        byte[] sent = send(ENDPOINT, HttpMethod.GET, MediaType.APPLICATION_JSON, REQUEST_BODY);

        assertThat(text(sent)).isEqualTo(REQUEST_BODY);
    }

    @Test
    void doesNotTouchANonJsonContentType() throws Exception {
        byte[] sent = send(ENDPOINT, HttpMethod.POST, MediaType.TEXT_PLAIN, REQUEST_BODY);

        assertThat(text(sent)).isEqualTo(REQUEST_BODY);
    }

    @Test
    void doesNotTouchARequestWithoutContentType() throws Exception {
        byte[] sent = send(ENDPOINT, HttpMethod.POST, null, REQUEST_BODY);

        assertThat(text(sent)).isEqualTo(REQUEST_BODY);
    }

    @Test
    void doesNotTouchAnEmptyBody() throws Exception {
        byte[] sent = send(ENDPOINT, HttpMethod.POST, MediaType.APPLICATION_JSON, "");

        assertThat(sent).isEmpty();
    }

    @Test
    void leavesAnAlreadyDisabledRequestByteIdentical() throws Exception {
        String original = "{\"model\":\"deepseek-flash\",\"thinking\":{\"type\":\"disabled\"}}";

        byte[] sent = send(ENDPOINT, HttpMethod.POST, MediaType.APPLICATION_JSON, original);

        assertThat(text(sent)).isEqualTo(original);
    }

    private byte[] send(String url, HttpMethod method, MediaType contentType, String body) throws Exception {
        MockClientHttpRequest request = new MockClientHttpRequest(method, URI.create(url));
        if (contentType != null) {
            request.getHeaders().setContentType(contentType);
        }
        byte[][] captured = new byte[1][];
        this.interceptor.intercept(request, bytes(body), capturing(captured));
        return captured[0];
    }

    /**
     * 用原始头写入 Content-Type，用于 {@code application/*} 这类无法经
     * {@code setContentType} 设置的媒体类型。
     */
    private byte[] sendWithRawContentType(String url, String rawContentType, String body) throws Exception {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST, URI.create(url));
        request.getHeaders().set("Content-Type", rawContentType);
        byte[][] captured = new byte[1][];
        this.interceptor.intercept(request, bytes(body), capturing(captured));
        return captured[0];
    }

    private static byte[] capture(DeepSeekThinkingDisabledInterceptor target, String url) throws Exception {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST, URI.create(url));
        request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[][] captured = new byte[1][];
        target.intercept(request, bytes(REQUEST_BODY), capturing(captured));
        return captured[0];
    }

    private static ClientHttpRequestExecution capturing(byte[][] captured) {
        return (request, body) -> {
            captured[0] = body;
            return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
        };
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }
}
