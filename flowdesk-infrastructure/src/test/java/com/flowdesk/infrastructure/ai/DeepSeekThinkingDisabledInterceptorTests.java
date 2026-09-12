package com.flowdesk.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

/**
 * 传输层 thinking 关闭拦截器的行为测试。
 */
class DeepSeekThinkingDisabledInterceptorTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DeepSeekThinkingDisabledInterceptor interceptor = new DeepSeekThinkingDisabledInterceptor(MAPPER);

    @Test
    void addsThinkingDisabledToChatCompletionsRequest() throws Exception {
        byte[] sent = capture("https://api.deepseek.com/chat/completions",
                "{\"model\":\"deepseek-flash\",\"messages\":[],\"tools\":[]}");

        JsonNode body = MAPPER.readTree(sent);
        assertThat(body.path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(body.path("model").asText()).isEqualTo("deepseek-flash");
        assertThat(body.has("tools")).isTrue();
    }

    @Test
    void keepsOtherPathsUntouched() throws Exception {
        String original = "{\"model\":\"text-embedding-3-small\",\"input\":\"hello\"}";

        byte[] sent = capture("https://api.deepseek.com/embeddings", original);

        assertThat(new String(sent, StandardCharsets.UTF_8)).isEqualTo(original);
    }

    @Test
    void keepsEmptyBodyUntouched() throws Exception {
        byte[] sent = capture("https://api.deepseek.com/chat/completions", "");

        assertThat(sent).isEmpty();
    }

    @Test
    void doesNotRewriteWhenThinkingIsAlreadyDisabled() throws Exception {
        String original = "{\"model\":\"deepseek-flash\",\"thinking\":{\"type\":\"disabled\"}}";

        byte[] sent = capture("https://api.deepseek.com/chat/completions", original);

        assertThat(new String(sent, StandardCharsets.UTF_8)).isEqualTo(original);
    }

    @Test
    void overridesAConflictingThinkingBlock() throws Exception {
        byte[] sent = capture("https://api.deepseek.com/chat/completions",
                "{\"thinking\":{\"type\":\"enabled\"}}");

        JsonNode body = MAPPER.readTree(sent);
        assertThat(body.path("thinking").path("type").asText()).isEqualTo("disabled");
    }

    private byte[] capture(String url, String body) throws Exception {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST, URI.create(url));
        byte[][] captured = new byte[1][];
        ClientHttpRequestExecution execution = (req, sentBody) -> {
            captured[0] = sentBody;
            return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
        };

        interceptor.intercept(request, body.getBytes(StandardCharsets.UTF_8), execution);
        return captured[0];
    }
}
