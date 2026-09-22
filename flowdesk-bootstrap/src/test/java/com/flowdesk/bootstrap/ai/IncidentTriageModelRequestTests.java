package com.flowdesk.bootstrap.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowdesk.application.ai.IncidentTriageCommand;
import com.flowdesk.application.ai.IncidentTriageResult;
import com.flowdesk.application.ai.IncidentTriageUseCase;
import com.flowdesk.application.integration.AssetQueryResult;
import com.flowdesk.application.integration.AssetView;
import com.flowdesk.application.integration.HealthState;
import com.flowdesk.application.integration.MonitoringSnapshotQueryResult;
import com.flowdesk.application.integration.MonitoringSnapshotView;
import com.flowdesk.application.integration.SourceOrigin;
import com.flowdesk.application.integration.port.out.AssetQueryPort;
import com.flowdesk.application.integration.port.out.MonitoringSnapshotQueryPort;
import com.flowdesk.application.knowledge.port.in.RetrieveKnowledgeUseCase;
import com.flowdesk.application.knowledge.query.RetrieveKnowledgeQuery;
import com.flowdesk.application.knowledge.view.KnowledgeCitationView;
import com.flowdesk.application.knowledge.view.KnowledgeRetrievalView;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
 * 事件研判 Graph 的装配与「模型实际收到什么」（FD-0018-A）。
 *
 * <p>真实 Spring 上下文 + 真实 {@code ChatClient}（DeepSeek profile 构造）打一个本地合成的 OpenAI
 * 端点，因此模型请求是真实 HTTP 报文，可以逐字段断言：只有 system + user 两条消息、没有 tools、
 * 证据以确定性 JSON 出现且只含白名单字段、请求里没有任何端点/异常/会话/密钥材料。</p>
 *
 * <p>三个证据来源用测试替身覆盖（{@code @Primary}）：本用例验证的是编排与提示词，
 * 不需要真实 MCP 服务与真实向量库。</p>
 */
@SpringBootTest(properties = {
        "spring.profiles.active=deepseek",
        "DEEPSEEK_API_KEY=test-fake-key-not-a-real-secret",
        "spring.datasource.url=jdbc:h2:mem:flowdesk_incident_triage_it;MODE=PostgreSQL;"
                + "DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.main.allow-bean-definition-overriding=true"
})
class IncidentTriageModelRequestTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String ASSET_ID = "AST-900001";

    private static final String QUESTION = "SENTINEL-QUESTION-该资产 CPU 持续偏高如何处理？";

    private static final String CHUNK_SENTINEL = "SENTINEL-CHUNK-BODY-STEP-ONE";

    private static SyntheticOpenAiEndpoint endpoint;

    @DynamicPropertySource
    static void pointAtSyntheticEndpoint(DynamicPropertyRegistry registry) throws IOException {
        if (endpoint == null) {
            endpoint = SyntheticOpenAiEndpoint.startWithAnswer(
                    "现象 [K1]，资产在保 [A1]，监控负载偏高 [M1]，建议先扩容再观察。");
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
    private IncidentTriageUseCase incidentTriageUseCase;

    @Test
    void theModelReceivesExactlyOneSystemAndOneUserMessageWithoutTools() throws Exception {
        endpoint.clearRequests();

        IncidentTriageResult result = this.incidentTriageUseCase.triage(
                new IncidentTriageCommand(ASSET_ID, QUESTION, 5, 0.3));

        assertThat(result.grounded()).isTrue();
        assertThat(result.usedEvidenceIds()).containsExactly("K1", "A1", "M1");

        List<SyntheticOpenAiEndpoint.CapturedRequest> requests = endpoint.requests();
        assertThat(requests).as("模型只调用一次").hasSize(1);

        JsonNode body = MAPPER.readTree(requests.get(0).body());
        JsonNode messages = body.path("messages");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).path("role").asText()).isEqualTo("system");
        assertThat(messages.get(1).path("role").asText()).isEqualTo("user");
        assertThat(body.has("tools")).as("请求里不得出现 tools").isFalse();
        assertThat(body.has("tool_choice")).isFalse();

        String system = messages.get(0).path("content").asText();
        assertThat(system).as("系统消息只放规则").doesNotContain(ASSET_ID).doesNotContain(QUESTION);
        assertThat(system).contains("[K1]").contains("[A1]").contains("[M1]");

        String user = messages.get(1).path("content").asText();
        assertThat(user).as("证据确实进入了模型请求").contains(CHUNK_SENTINEL).contains(ASSET_ID);

        JsonNode data = MAPPER.readTree(payloadOf(user));
        assertThat(data.fieldNames()).toIterable().containsExactly("question", "availability", "evidence");
        assertThat(data.path("question").asText()).isEqualTo(QUESTION);
        assertThat(data.path("availability").path("knowledge").path("outcome").asText()).isEqualTo("FOUND");
        assertThat(data.path("evidence")).hasSize(3);
        assertThat(data.path("evidence").get(0).fieldNames()).toIterable()
                .containsExactly("citationId", "documentTitle", "chunkIndex", "content");
        assertThat(data.path("evidence").get(1).fieldNames()).toIterable()
                .containsExactly("evidenceId", "assetId", "assetType", "status", "source");
        assertThat(data.path("evidence").get(2).fieldNames()).toIterable()
                .containsExactly("evidenceId", "assetId", "observedAt", "health", "cpuUtilizationPercent",
                        "memoryUtilizationPercent", "activeAlertCount", "source");
    }

    @Test
    void theRequestCarriesNoEndpointKeyOrInternalMaterial() throws Exception {
        endpoint.clearRequests();

        this.incidentTriageUseCase.triage(new IncidentTriageCommand(ASSET_ID, QUESTION, 5, 0.3));

        String raw = endpoint.requests().get(0).body();
        assertThat(raw)
                .doesNotContain("http://")
                .doesNotContain("https://")
                .doesNotContain("8091")
                .doesNotContain("8092")
                .doesNotContain("Authorization")
                .doesNotContain("test-fake-key-not-a-real-secret")
                .doesNotContain("apiKey")
                .doesNotContain("documentId")
                .doesNotContain("chunkSha256")
                .doesNotContain("Exception")
                .doesNotContain("at com.flowdesk")
                .doesNotContain("jsonrpc")
                .doesNotContain("Mcp-Session-Id")
                .doesNotContain("ToolCallback");
    }

    private static String payloadOf(String userMessage) {
        String begin = "<<<FLOWDESK_TRIAGE_DATA_BEGIN>>>";
        String end = "<<<FLOWDESK_TRIAGE_DATA_END>>>";
        int from = userMessage.indexOf(begin);
        int to = userMessage.indexOf(end);
        assertThat(from).isGreaterThanOrEqualTo(0);
        assertThat(to).isGreaterThan(from);
        return userMessage.substring(from + begin.length(), to).strip();
    }

    /** 用固定虚构数据覆盖三个证据来源：本用例验证提示词与装配，不依赖真实服务。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class StubEvidenceSources {

        @Bean
        @Primary
        RetrieveKnowledgeUseCase stubRetrieveKnowledgeUseCase() {
            return (RetrieveKnowledgeQuery query) -> KnowledgeRetrievalView.vectorOrdered("dashscope",
                    "text-embedding-v4", 1024, 5, 0.3,
                    List.of(KnowledgeCitationView.vectorOnly("K1", 1,
                            UUID.fromString("11111111-2222-3333-4444-555555555555"), 1L, "VPN 故障处理手册", 0,
                            "0".repeat(64), CHUNK_SENTINEL, 0.9)));
        }

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
