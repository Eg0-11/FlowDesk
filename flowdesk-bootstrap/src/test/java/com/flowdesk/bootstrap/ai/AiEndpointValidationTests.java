package com.flowdesk.bootstrap.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import com.flowdesk.bootstrap.web.FlowDeskProblems;

/**
 * 端到端参数校验：使用真实装配（真实 Controller + 真实用例实现）验证非法入参被挡在模型调用之前。
 *
 * <p>本测试的假 Key 与真实 base-url 意味着：一旦请求进入模型调用环节，结果必然是 502 而不是 400。
 * 因此收到 400 本身就证明校验发生在任何出网调用之前。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=deepseek",
        "DEEPSEEK_API_KEY=test-fake-key-not-a-real-secret"
})
class AiEndpointValidationTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void rejectsUnknownIssueTypeBeforeAnyProviderCall() {
        ResponseEntity<String> response = restTemplate.postForEntity("/api/v1/ai/tool-smoke",
                Map.of("issueType", "PRINTER_JAM"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains(FlowDeskProblems.CODE_INVALID_REQUEST);
        assertThat(response.getBody()).doesNotContain("PRINTER_JAM");
    }

    @Test
    void rejectsLowerCaseButUnknownIssueType() {
        ResponseEntity<String> response = restTemplate.postForEntity("/api/v1/ai/tool-smoke",
                Map.of("issueType", "vpn_timeout"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsBlankIssueTypeBeforeAnyProviderCall() {
        ResponseEntity<String> response = restTemplate.postForEntity("/api/v1/ai/tool-smoke",
                Map.of("issueType", "   "), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsBlankMessageBeforeAnyProviderCall() {
        ResponseEntity<String> response = restTemplate.postForEntity("/api/v1/ai/chat",
                Map.of("message", "   "), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains(FlowDeskProblems.CODE_INVALID_REQUEST);
    }

    @Test
    void rejectsTooLongMessageBeforeAnyProviderCall() {
        ResponseEntity<String> response = restTemplate.postForEntity("/api/v1/ai/chat",
                Map.of("message", "a".repeat(4001)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
