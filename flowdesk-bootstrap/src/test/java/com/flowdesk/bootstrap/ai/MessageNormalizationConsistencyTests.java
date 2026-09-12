package com.flowdesk.bootstrap.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowdesk.application.ai.AiChatUseCase;
import com.flowdesk.application.ai.AiRequestException;
import com.flowdesk.application.ai.ChatCommand;
import java.io.IOException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * HTTP 入口与直接调用 use case 的消息校验语义必须完全一致。
 *
 * <p>规则是「先 {@code strip()} 标准化首尾空白 → 判空 → 判最大 4000 字符」。
 * 因此「4000 个有效字符 + 首尾空白」是合法输入（HTTP 与 use case 都必须接受），
 * 而「strip 后 4001 个字符」必须被拒绝。</p>
 *
 * <p>每个用例都同时对两条入口断言，任何一条分叉都会让本测试失败。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=deepseek",
        "DEEPSEEK_API_KEY=test-fake-key-not-a-real-secret"
})
class MessageNormalizationConsistencyTests {

    private static final int MAX = ChatCommand.MAX_MESSAGE_LENGTH;

    private static SyntheticOpenAiEndpoint endpoint;

    @Autowired
    private AiChatUseCase aiChatUseCase;

    @Autowired
    private TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void pointAtSyntheticEndpoint(DynamicPropertyRegistry registry) throws IOException {
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

    @Test
    void acceptsFourThousandValidCharactersWithSurroundingWhitespace() {
        String value = "  " + "a".repeat(MAX) + "   ";

        assertAcceptedByBothEntryPoints(value);
    }

    @Test
    void acceptsExactlyFourThousandCharactersWithoutWhitespace() {
        assertAcceptedByBothEntryPoints("a".repeat(MAX));
    }

    @Test
    void rejectsFourThousandAndOneCharactersAfterStrip() {
        assertRejectedByBothEntryPoints("a".repeat(MAX + 1));
    }

    @Test
    void rejectsFourThousandAndOneValidCharactersWrappedInWhitespace() {
        assertRejectedByBothEntryPoints(" " + "a".repeat(MAX + 1) + "  ");
    }

    @Test
    void rejectsNullMessage() {
        assertRejectedByBothEntryPoints(null);
    }

    @Test
    void rejectsEmptyMessage() {
        assertRejectedByBothEntryPoints("");
    }

    @Test
    void rejectsAsciiWhitespaceOnlyMessage() {
        assertRejectedByBothEntryPoints("     ");
    }

    @Test
    void rejectsUnicodeWhitespaceOnlyMessage() {
        // U+3000 不被 String.trim() 处理，但会被 String.strip() 去掉 —— 正是两条入口必须一致的地方
        assertRejectedByBothEntryPoints("\u3000\u3000\u3000");
    }

    private void assertAcceptedByBothEntryPoints(String value) {
        ResponseEntity<String> response = postChat(toJson(value));

        assertThat(response.getStatusCode())
                .as("HTTP 入口应接受该消息")
                .isEqualTo(HttpStatus.OK);
        assertThat(this.aiChatUseCase.chat(new ChatCommand(value)).answer())
                .as("use case 入口应接受同一条消息")
                .isEqualTo(SyntheticOpenAiEndpoint.PLAIN_ANSWER);
    }

    private void assertRejectedByBothEntryPoints(String value) {
        ResponseEntity<String> response = postChat(toJson(value));

        assertThat(response.getStatusCode())
                .as("HTTP 入口应拒绝该消息")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains(AiExceptionHandler.CODE_INVALID_REQUEST);

        assertThatThrownBy(() -> this.aiChatUseCase.chat(new ChatCommand(value)))
                .as("use case 入口应拒绝同一条消息")
                .isInstanceOf(AiRequestException.class);
    }

    private ResponseEntity<String> postChat(String jsonBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return this.restTemplate.postForEntity("/api/v1/ai/chat", new HttpEntity<>(jsonBody, headers), String.class);
    }

    private static String toJson(String value) {
        if (value == null) {
            return "{\"message\":null}";
        }
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == '"' || character == '\\') {
                escaped.append('\\');
            }
            if (character == '\u3000') {
                escaped.append("\\u3000");
                continue;
            }
            escaped.append(character);
        }
        return "{\"message\":\"" + escaped + "\"}";
    }
}
