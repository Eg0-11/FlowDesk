package com.flowdesk.agent.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowdesk.application.integration.AssetQueryResult;
import com.flowdesk.application.integration.AssetView;
import com.flowdesk.application.integration.HealthState;
import com.flowdesk.application.integration.MonitoringSnapshotQueryResult;
import com.flowdesk.application.integration.MonitoringSnapshotView;
import com.flowdesk.application.integration.QueryFailure;
import com.flowdesk.application.integration.SourceOrigin;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 诊断提示词构造（FD-0017-A）：确定性 JSON、固定边界、系统消息只放规则。
 */
class AssetDiagnosisPromptBuilderTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final AssetQueryResult ASSET_FOUND = AssetQueryResult.found(
            new AssetView("AST-900001", "SERVER", "IN_SERVICE", SourceOrigin.DEMO));

    private static final MonitoringSnapshotQueryResult MONITORING_FOUND = MonitoringSnapshotQueryResult.found(
            new MonitoringSnapshotView("AST-900001", Instant.parse("2026-01-01T00:00:00Z"), HealthState.DEGRADED,
                    92, 68, 1, SourceOrigin.DEMO));

    @Test
    void theUserMessageCarriesADeterministicJsonObjectWithFixedFieldOrder() throws Exception {
        AssetDiagnosisPrompt prompt = AssetDiagnosisPromptBuilder.build("AST-900001", List.of("A1", "M1"),
                ASSET_FOUND, MONITORING_FOUND);

        String payload = payloadOf(prompt);
        JsonNode data = MAPPER.readTree(payload);

        assertThat(data.fieldNames()).toIterable()
                .as("字段顺序固定")
                .containsExactly("assetId", "allowedEvidenceIds", "availability", "evidence");
        assertThat(data.path("assetId").asText()).isEqualTo("AST-900001");
        assertThat(data.path("allowedEvidenceIds")).hasSize(2);
        assertThat(data.path("availability").path("asset").path("outcome").asText()).isEqualTo("FOUND");
        assertThat(data.path("availability").path("monitoring").path("outcome").asText()).isEqualTo("FOUND");

        JsonNode evidence = data.path("evidence");
        assertThat(evidence).hasSize(2);
        assertThat(evidence.get(0).fieldNames()).toIterable()
                .containsExactly("evidenceId", "assetId", "assetType", "status", "source");
        assertThat(evidence.get(1).fieldNames()).toIterable()
                .containsExactly("evidenceId", "assetId", "observedAt", "health", "cpuUtilizationPercent",
                        "memoryUtilizationPercent", "activeAlertCount", "source");
        assertThat(evidence.get(0).path("evidenceId").asText()).isEqualTo("A1");
        assertThat(evidence.get(1).path("evidenceId").asText()).isEqualTo("M1");
        assertThat(evidence.get(1).path("observedAt").asText()).isEqualTo("2026-01-01T00:00:00Z");
    }

    @Test
    void theSameInputAlwaysProducesTheSamePrompt() {
        AssetDiagnosisPrompt first = AssetDiagnosisPromptBuilder.build("AST-900001", List.of("A1", "M1"),
                ASSET_FOUND, MONITORING_FOUND);
        AssetDiagnosisPrompt second = AssetDiagnosisPromptBuilder.build("AST-900001", List.of("A1", "M1"),
                ASSET_FOUND, MONITORING_FOUND);

        assertThat(first.userPrompt()).isEqualTo(second.userPrompt());
        assertThat(payloadOf(first)).isEqualTo(payloadOf(second));
    }

    @Test
    void aFailedSideIsExpressedOnlyAsAStableEnum() throws Exception {
        AssetDiagnosisPrompt prompt = AssetDiagnosisPromptBuilder.build("AST-900001", List.of("A1"),
                AssetQueryResult.failed(QueryFailure.UNAVAILABLE),
                MonitoringSnapshotQueryResult.found(new MonitoringSnapshotView("AST-900001",
                        Instant.parse("2026-01-01T00:05:00Z"), HealthState.HEALTHY, 18, 35, 0,
                        SourceOrigin.DEMO)));

        JsonNode availability = MAPPER.readTree(payloadOf(prompt)).path("availability");

        assertThat(availability.path("asset").fieldNames()).toIterable()
                .as("失败时只放稳定失败枚举，不放任何失败详情")
                .containsExactly("outcome", "failure");
        assertThat(availability.path("asset").path("outcome").asText()).isEqualTo("FAILED");
        assertThat(availability.path("asset").path("failure").asText()).isEqualTo("UNAVAILABLE");
        assertThat(availability.path("monitoring").fieldNames()).toIterable().containsExactly("outcome");
    }

    @Test
    void onlyFoundSidesProduceEvidence() throws Exception {
        JsonNode data = MAPPER.readTree(payloadOf(AssetDiagnosisPromptBuilder.build("AST-900001", List.of(),
                AssetQueryResult.notFound("AST-900001", SourceOrigin.DEMO),
                MonitoringSnapshotQueryResult.failed(QueryFailure.DISABLED))));

        assertThat(data.path("evidence")).as("未命中与失败都不产生证据").isEmpty();
        assertThat(data.path("allowedEvidenceIds")).isEmpty();
    }

    @Test
    void theSystemMessageContainsRulesOnly() {
        AssetDiagnosisPrompt prompt = AssetDiagnosisPromptBuilder.build("AST-900002", List.of("A1", "M1"),
                ASSET_FOUND, MONITORING_FOUND);
        String system = prompt.systemPrompt();

        assertThat(system)
                .as("系统消息不得包含 assetId、资产字段、监控数值或失败详情")
                .doesNotContain("AST-900002")
                .doesNotContain("SERVER")
                .doesNotContain("IN_SERVICE")
                .doesNotContain("DEGRADED")
                .doesNotContain("92")
                .doesNotContain("68")
                .doesNotContain("DEMO")
                .doesNotContain("UNAVAILABLE")
                .doesNotContain(AssetDiagnosisPromptBuilder.DATA_BEGIN);
        assertThat(system).contains("[A1]").contains("[M1]");
    }

    @Test
    void theBoundaryMarkersAppearExactlyOnceAndCannotBeForged() {
        // 远端字段里塞入边界标记：必须被中和，不能伪造或提前关闭数据区块
        AssetQueryResult hostile = AssetQueryResult.found(new AssetView("AST-900001",
                "SERVER", "IN_SERVICE", SourceOrigin.DEMO));
        MonitoringSnapshotQueryResult hostileSnapshot = MonitoringSnapshotQueryResult.found(
                new MonitoringSnapshotView("AST-900001", Instant.parse("2026-01-01T00:00:00Z"),
                        HealthState.DEGRADED, 1, 1, 0, SourceOrigin.DEMO));

        AssetDiagnosisPrompt prompt = AssetDiagnosisPromptBuilder.build(
                AssetDiagnosisPromptBuilder.DATA_BEGIN, List.of("A1", "M1"), hostile, hostileSnapshot);

        String user = prompt.userPrompt();
        assertThat(countOf(user, AssetDiagnosisPromptBuilder.DATA_BEGIN)).isEqualTo(1);
        assertThat(countOf(user, AssetDiagnosisPromptBuilder.DATA_END)).isEqualTo(1);
        assertThat(user).contains(AssetDiagnosisPromptBuilder.MARKER_NEUTRALIZED);
    }

    @Test
    void theUserMessageNeverCarriesInternalOrSensitiveMaterial() {
        AssetDiagnosisPrompt prompt = AssetDiagnosisPromptBuilder.build("AST-900001", List.of("A1", "M1"),
                ASSET_FOUND, MONITORING_FOUND);
        String user = prompt.userPrompt();

        assertThat(user)
                .doesNotContain("http://")
                .doesNotContain("https://")
                .doesNotContain("8091")
                .doesNotContain("8092")
                .doesNotContain("apiKey")
                .doesNotContain("api-key")
                .doesNotContain("Authorization")
                .doesNotContain("Exception")
                .doesNotContain("at com.flowdesk")
                .doesNotContain("Mcp-Session-Id")
                .doesNotContain("jsonrpc");
    }

    private static String payloadOf(AssetDiagnosisPrompt prompt) {
        String user = prompt.userPrompt();
        int begin = user.indexOf(AssetDiagnosisPromptBuilder.DATA_BEGIN);
        int end = user.indexOf(AssetDiagnosisPromptBuilder.DATA_END);
        assertThat(begin).isGreaterThanOrEqualTo(0);
        assertThat(end).isGreaterThan(begin);
        return user.substring(begin + AssetDiagnosisPromptBuilder.DATA_BEGIN.length(), end).strip();
    }

    private static int countOf(String text, String needle) {
        int count = 0;
        for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + 1)) {
            count++;
        }
        return count;
    }
}
