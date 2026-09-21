package com.flowdesk.bootstrap.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowdesk.application.ai.AssetDiagnosisCommand;
import com.flowdesk.application.ai.AssetDiagnosisResult;
import com.flowdesk.application.ai.AssetDiagnosisUseCase;
import com.flowdesk.application.integration.AssetQueryResult;
import com.flowdesk.application.integration.AssetView;
import com.flowdesk.application.integration.HealthState;
import com.flowdesk.application.integration.MonitoringSnapshotQueryResult;
import com.flowdesk.application.integration.MonitoringSnapshotView;
import com.flowdesk.application.integration.SourceOrigin;
import com.flowdesk.application.integration.port.out.AssetQueryPort;
import com.flowdesk.application.integration.port.out.MonitoringSnapshotQueryPort;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 诊断 Agent 的装配与「模型实际收到什么」（FD-0017-A）。
 *
 * <p>用<b>真实</b>的 Spring 上下文 + 真实 {@code ChatClient}（由 DeepSeek profile 构造）打一个
 * 本地合成的 OpenAI 端点，因此模型请求是真实 HTTP 报文，可以逐字段断言：</p>
 * <ul>
 *   <li>请求里只有 system 与 user 两条消息；</li>
 *   <li>请求里<b>没有</b> {@code tools}（也不注册任何 {@code ToolCallback}）；</li>
 *   <li>数据以确定性 JSON 出现，字段与顺序符合契约，边界标记各出现一次；</li>
 *   <li>请求里不含端点、异常信息、密钥或任何无关内部字段。</li>
 * </ul>
 *
 * <p>两个查询端口用测试替身覆盖（{@code @Primary}）：本用例验证的是诊断编排与提示词，
 * 不需要真实 MCP 服务；FD-0016 的 MCP 客户端配置与两个 MCP 服务都没有被改动。</p>
 */
@SpringBootTest(properties = {
        // DeepSeek profile 打开 AI 编排；Key 是假值，模型端被合成端点接管
        "spring.profiles.active=deepseek",
        "DEEPSEEK_API_KEY=test-fake-key-not-a-real-secret",
        "spring.datasource.url=jdbc:h2:mem:flowdesk_asset_diagnosis_it;MODE=PostgreSQL;"
                + "DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.main.allow-bean-definition-overriding=true"
})
class AssetDiagnosisModelRequestTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String ASSET_ID = "AST-900001";

    private static SyntheticOpenAiEndpoint endpoint;

    @DynamicPropertySource
    static void pointAtSyntheticEndpoint(DynamicPropertyRegistry registry) throws IOException {
        if (endpoint == null) {
            endpoint = SyntheticOpenAiEndpoint.startWithAnswer("资产在用 [A1]，监控负载偏高 [M1]。");
        }
        registry.add("spring.ai.openai.base-url", endpoint::baseUrl);
    }

    @AfterAll
    static void stopEndpoint() {
        if (endpoint != null) {
            endpoint.stop();
        }
    }

    @Autowired
    private AssetDiagnosisUseCase assetDiagnosisUseCase;

    @Test
    void aDiagnosisSendsExactlyOneSystemAndOneUserMessageWithoutTools() throws Exception {
        endpoint.clearRequests();

        AssetDiagnosisResult result = this.assetDiagnosisUseCase.diagnose(new AssetDiagnosisCommand(ASSET_ID));

        assertThat(result.grounded()).isTrue();
        assertThat(result.usedEvidenceIds()).containsExactly("A1", "M1");

        List<SyntheticOpenAiEndpoint.CapturedRequest> requests = endpoint.requests();
        assertThat(requests).as("只调用模型一次").hasSize(1);

        JsonNode body = MAPPER.readTree(requests.get(0).body());
        JsonNode messages = body.path("messages");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).path("role").asText()).isEqualTo("system");
        assertThat(messages.get(1).path("role").asText()).isEqualTo("user");
        assertThat(body.has("tools")).as("模型请求里不得出现 tools").isFalse();
        assertThat(body.has("tool_choice")).isFalse();

        String system = messages.get(0).path("content").asText();
        String user = messages.get(1).path("content").asText();

        assertThat(system).as("系统消息只放规则").doesNotContain(ASSET_ID).doesNotContain("SERVER");
        assertThat(system).contains("[A1]").contains("[M1]");

        assertThat(countOf(user, "<<<FLOWDESK_DIAGNOSIS_DATA_BEGIN>>>")).isEqualTo(1);
        assertThat(countOf(user, "<<<FLOWDESK_DIAGNOSIS_DATA_END>>>")).isEqualTo(1);

        JsonNode data = MAPPER.readTree(payloadOf(user));
        assertThat(data.fieldNames()).toIterable()
                .containsExactly("assetId", "allowedEvidenceIds", "availability", "evidence");
        assertThat(data.path("assetId").asText()).isEqualTo(ASSET_ID);
        assertThat(data.path("allowedEvidenceIds")).hasSize(2);
        assertThat(data.path("availability").path("asset").path("outcome").asText()).isEqualTo("FOUND");
        assertThat(data.path("availability").path("monitoring").path("outcome").asText()).isEqualTo("FOUND");
        assertThat(data.path("evidence")).hasSize(2);
        assertThat(data.path("evidence").get(0).path("evidenceId").asText()).isEqualTo("A1");
        assertThat(data.path("evidence").get(1).path("evidenceId").asText()).isEqualTo("M1");
    }

    @Test
    void theModelRequestCarriesNoEndpointExceptionOrInternalField() throws Exception {
        endpoint.clearRequests();

        this.assetDiagnosisUseCase.diagnose(new AssetDiagnosisCommand(ASSET_ID));

        String raw = endpoint.requests().get(0).body();
        assertThat(raw)
                .doesNotContain("http://")
                .doesNotContain("https://")
                .doesNotContain("8091")
                .doesNotContain("8092")
                .doesNotContain("Authorization")
                .doesNotContain("test-fake-key-not-a-real-secret")
                .doesNotContain("apiKey")
                .doesNotContain("Exception")
                .doesNotContain("at com.flowdesk")
                .doesNotContain("jsonrpc")
                .doesNotContain("Mcp-Session-Id")
                .doesNotContain("ToolCallback");
    }

    private static String payloadOf(String userMessage) {
        String begin = "<<<FLOWDESK_DIAGNOSIS_DATA_BEGIN>>>";
        String end = "<<<FLOWDESK_DIAGNOSIS_DATA_END>>>";
        int from = userMessage.indexOf(begin);
        int to = userMessage.indexOf(end);
        assertThat(from).isGreaterThanOrEqualTo(0);
        assertThat(to).isGreaterThan(from);
        return userMessage.substring(from + begin.length(), to).strip();
    }

    private static int countOf(String text, String needle) {
        int count = 0;
        for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + 1)) {
            count++;
        }
        return count;
    }

    /** 用固定虚构数据覆盖两个查询端口：本用例验证提示词与装配，不依赖真实 MCP 服务。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class StubPorts {

        @Bean
        @Primary
        AssetQueryPort stubAssetQueryPort() {
            return assetId -> AssetQueryResult.found(
                    new AssetView(assetId, "SERVER", "IN_SERVICE", SourceOrigin.DEMO));
        }

        @Bean
        @Primary
        MonitoringSnapshotQueryPort stubMonitoringSnapshotQueryPort() {
            return assetId -> MonitoringSnapshotQueryResult.found(new MonitoringSnapshotView(assetId,
                    Instant.parse("2026-01-01T00:00:00Z"), HealthState.DEGRADED, 92, 68, 1, SourceOrigin.DEMO));
        }
    }
}
