package com.flowdesk.bootstrap.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flowdesk.application.ai.AiProviderException;
import com.flowdesk.application.ai.AiRequestException;
import com.flowdesk.application.ai.AssetDiagnosisCommand;
import com.flowdesk.application.ai.AssetDiagnosisResult;
import com.flowdesk.application.ai.AssetDiagnosisUseCase;
import com.flowdesk.application.integration.AssetIdentifier;
import com.flowdesk.application.integration.AssetQueryResult;
import com.flowdesk.application.integration.AssetView;
import com.flowdesk.application.integration.HealthState;
import com.flowdesk.application.integration.MonitoringSnapshotQueryResult;
import com.flowdesk.application.integration.MonitoringSnapshotView;
import com.flowdesk.application.integration.QueryFailure;
import com.flowdesk.application.integration.SourceOrigin;
import com.flowdesk.bootstrap.web.FlowDeskProblems;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 资产诊断 HTTP 接口的契约测试（FD-0017-B）。
 *
 * <p>替换的是 Web 层的直接协作者 —— 应用层用例接口 {@link AssetDiagnosisUseCase} ——
 * <b>不</b>对 {@code ChatClient}、查询端口或编排类做任何深打桩：这一层要证明的是
 * 「控制器不做校验、不做编排、只做 DTO 转换」，以及响应形状与状态码。</p>
 *
 * <p>被反复验证的核心承诺有四条：</p>
 * <ul>
 *   <li><b>200 不代表两个依赖都成功</b>：部分命中、两侧未命中、两侧 {@code FAILED}/{@code DISABLED}
 *       都是 200，数据状态只能从 {@code asset.outcome}/{@code monitoring.outcome} 与
 *       {@code failure} 读出来；</li>
 *   <li><b>字段集合由 outcome 决定</b>：未命中的一侧不输出伪造详情，
 *       失败的一侧不输出编号与来源，{@code null} 字段直接省略；</li>
 *   <li><b>输入校验不在这一层</b>：空 body、{@code {}}、非法编号全部原样交给用例判定，
 *       HTTP 层不复制 {@code AssetIdentifier} 的规则；</li>
 *   <li><b>不泄露内部细节</b>：响应里没有提示词、MCP 报文、端点、会话标识、异常信息或密钥。</li>
 * </ul>
 */
@WebMvcTest(controllers = AssetDiagnosisController.class, properties = "flowdesk.ai.enabled=true")
@Import(AssetDiagnosisControllerWebTests.StubUseCaseConfiguration.class)
class AssetDiagnosisControllerWebTests {

    private static final String DIAGNOSIS_PATH = "/api/v1/ai/asset-diagnosis";

    private static final String ASSET_ID = "AST-900001";

    private static final String REQUEST_ID = "8f4c1a2b-3333-4444-5555-666677778888";

    private static final String GROUNDED_ANSWER = "资产处于在用状态 [A1]，监控显示负载偏高 [M1]。";

    private static final String PARTIAL_ANSWER = "资产处于在用状态 [A1]，但本次未能取得监控数据。";

    private static final AssetQueryResult ASSET_FOUND = AssetQueryResult.found(
            new AssetView(ASSET_ID, "SERVER", "IN_SERVICE", SourceOrigin.DEMO));

    private static final AssetQueryResult ASSET_NOT_FOUND = AssetQueryResult.notFound(ASSET_ID, SourceOrigin.DEMO);

    private static final MonitoringSnapshotQueryResult MONITORING_FOUND = MonitoringSnapshotQueryResult.found(
            new MonitoringSnapshotView(ASSET_ID, Instant.parse("2026-01-01T00:00:00Z"), HealthState.DEGRADED,
                    92, 68, 1, SourceOrigin.DEMO));

    private static final MonitoringSnapshotQueryResult MONITORING_NOT_FOUND =
            MonitoringSnapshotQueryResult.notFound(ASSET_ID, SourceOrigin.DEMO);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StubAssetDiagnosisUseCase assetDiagnosisUseCase;

    @BeforeEach
    void reset() {
        this.assetDiagnosisUseCase.reset();
    }

    // ---------- 完整结果 ----------

    @Test
    void bothSidesFoundReturnsTheFullAuditableResult() throws Exception {
        this.assetDiagnosisUseCase.willReturn(result(GROUNDED_ANSWER, true, List.of("A1", "M1"), ASSET_FOUND,
                MONITORING_FOUND));

        MvcResult result = this.mockMvc.perform(post(DIAGNOSIS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":\"" + ASSET_ID + "\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID))
                .andExpect(jsonPath("$.answer").value(GROUNDED_ANSWER))
                .andExpect(jsonPath("$.grounded").value(true))
                .andExpect(jsonPath("$.usedEvidenceIds.length()").value(2))
                .andExpect(jsonPath("$.usedEvidenceIds[0]").value("A1"))
                .andExpect(jsonPath("$.usedEvidenceIds[1]").value("M1"))
                .andExpect(jsonPath("$.asset.outcome").value("FOUND"))
                .andExpect(jsonPath("$.asset.assetId").value(ASSET_ID))
                .andExpect(jsonPath("$.asset.assetType").value("SERVER"))
                .andExpect(jsonPath("$.asset.status").value("IN_SERVICE"))
                .andExpect(jsonPath("$.asset.source").value("DEMO"))
                .andExpect(jsonPath("$.asset.failure").doesNotExist())
                .andExpect(jsonPath("$.monitoring.outcome").value("FOUND"))
                .andExpect(jsonPath("$.monitoring.assetId").value(ASSET_ID))
                .andExpect(jsonPath("$.monitoring.observedAt").value("2026-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.monitoring.health").value("DEGRADED"))
                .andExpect(jsonPath("$.monitoring.cpuUtilizationPercent").value(92))
                .andExpect(jsonPath("$.monitoring.memoryUtilizationPercent").value(68))
                .andExpect(jsonPath("$.monitoring.activeAlertCount").value(1))
                .andExpect(jsonPath("$.monitoring.source").value("DEMO"))
                .andExpect(jsonPath("$.monitoring.failure").doesNotExist())
                .andReturn();

        assertThat(body(result))
                .as("命中路径也不得出现未命中/失败才有的占位或空值")
                .doesNotContain("null")
                .doesNotContain("UNKNOWN");
        assertThat(this.assetDiagnosisUseCase.commands()).containsExactly(new AssetDiagnosisCommand(ASSET_ID));
    }

    @Test
    void usedEvidenceIdsKeepTheOrderTheUseCaseProduced() throws Exception {
        this.assetDiagnosisUseCase.willReturn(result("风险 [M1]，资产 [A1]。", true, List.of("M1", "A1"), ASSET_FOUND,
                MONITORING_FOUND));

        this.mockMvc.perform(post(DIAGNOSIS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":\"" + ASSET_ID + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedEvidenceIds[0]").value("M1"))
                .andExpect(jsonPath("$.usedEvidenceIds[1]").value("A1"));
    }

    @Test
    void theResponseIsNotAffectedByLaterMutationsOfTheUseCaseList() throws Exception {
        List<String> mutable = new ArrayList<>(List.of("A1", "M1"));
        this.assetDiagnosisUseCase.willReturn(result(GROUNDED_ANSWER, true, mutable, ASSET_FOUND, MONITORING_FOUND));

        this.mockMvc.perform(post(DIAGNOSIS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":\"" + ASSET_ID + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedEvidenceIds.length()").value(2));
    }

    // ---------- 部分与降级结果：全部 200 ----------

    @Test
    void anAssetHitWithATimedOutMonitoringCallIsStillA200WithTheFailureVisible() throws Exception {
        this.assetDiagnosisUseCase.willReturn(result(PARTIAL_ANSWER, true, List.of("A1"), ASSET_FOUND,
                MonitoringSnapshotQueryResult.failed(QueryFailure.TIMEOUT)));

        MvcResult result = this.mockMvc.perform(post(DIAGNOSIS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":\"" + ASSET_ID + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounded").value(true))
                .andExpect(jsonPath("$.usedEvidenceIds[0]").value("A1"))
                .andExpect(jsonPath("$.asset.outcome").value("FOUND"))
                .andExpect(jsonPath("$.monitoring.outcome").value("FAILED"))
                .andExpect(jsonPath("$.monitoring.failure").value("TIMEOUT"))
                .andExpect(jsonPath("$.monitoring.assetId").doesNotExist())
                .andExpect(jsonPath("$.monitoring.source").doesNotExist())
                .andExpect(jsonPath("$.monitoring.cpuUtilizationPercent").doesNotExist())
                .andExpect(jsonPath("$.monitoring.activeAlertCount").doesNotExist())
                .andReturn();

        assertThat(body(result))
                .as("200 不等于两条依赖都成功：失败必须仍然看得见，且不得被写成未找到")
                .contains("\"outcome\":\"FAILED\"")
                .contains("\"failure\":\"TIMEOUT\"")
                .doesNotContain("NOT_FOUND")
                .doesNotContain("null");
    }

    @Test
    void aMonitoringHitWithAnAssetMissKeepsTheRealNotFoundStateAndItsSource() throws Exception {
        this.assetDiagnosisUseCase.willReturn(result("监控负载偏高 [M1]。", true, List.of("M1"), ASSET_NOT_FOUND,
                MONITORING_FOUND));

        MvcResult result = this.mockMvc.perform(post(DIAGNOSIS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":\"" + ASSET_ID + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asset.outcome").value("NOT_FOUND"))
                .andExpect(jsonPath("$.asset.assetId").value(ASSET_ID))
                .andExpect(jsonPath("$.asset.source").value("DEMO"))
                .andExpect(jsonPath("$.asset.assetType").doesNotExist())
                .andExpect(jsonPath("$.asset.status").doesNotExist())
                .andExpect(jsonPath("$.asset.failure").doesNotExist())
                .andExpect(jsonPath("$.monitoring.outcome").value("FOUND"))
                .andReturn();

        assertThat(body(result)).as("未命中的一侧不得伪造资产详情").doesNotContain("null");
    }

    @Test
    void bothSidesNotFoundReturns200WithTheFixedAnswerAndNoModelWork() throws Exception {
        this.assetDiagnosisUseCase.willReturn(result(StubAssetDiagnosisUseCase.NO_RECORD_ANSWER, false, List.of(),
                ASSET_NOT_FOUND, MONITORING_NOT_FOUND));

        MvcResult result = this.mockMvc.perform(post(DIAGNOSIS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":\"" + ASSET_ID + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounded").value(false))
                .andExpect(jsonPath("$.answer").value("未查询到该资产或可用的监控快照。"))
                .andExpect(jsonPath("$.usedEvidenceIds").isArray())
                .andExpect(jsonPath("$.usedEvidenceIds.length()").value(0))
                .andExpect(jsonPath("$.asset.outcome").value("NOT_FOUND"))
                .andExpect(jsonPath("$.asset.source").value("DEMO"))
                .andExpect(jsonPath("$.monitoring.outcome").value("NOT_FOUND"))
                .andExpect(jsonPath("$.monitoring.source").value("DEMO"))
                .andReturn();

        assertThat(body(result)).doesNotContain("null");
    }

    @Test
    void bothSidesDisabledReturns200WithADegradedAnswerAndBothFailuresVisible() throws Exception {
        this.assetDiagnosisUseCase.willReturn(result(StubAssetDiagnosisUseCase.INSUFFICIENT_EVIDENCE_ANSWER, false,
                List.of(), AssetQueryResult.failed(QueryFailure.DISABLED),
                MonitoringSnapshotQueryResult.failed(QueryFailure.DISABLED)));

        MvcResult result = this.mockMvc.perform(post(DIAGNOSIS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":\"" + ASSET_ID + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounded").value(false))
                .andExpect(jsonPath("$.answer").value("当前无法获得足够的资产与监控证据，暂时不能生成诊断结论。"))
                .andExpect(jsonPath("$.usedEvidenceIds.length()").value(0))
                .andExpect(jsonPath("$.asset.outcome").value("FAILED"))
                .andExpect(jsonPath("$.asset.failure").value("DISABLED"))
                .andExpect(jsonPath("$.asset.assetId").doesNotExist())
                .andExpect(jsonPath("$.asset.source").doesNotExist())
                .andExpect(jsonPath("$.monitoring.outcome").value("FAILED"))
                .andExpect(jsonPath("$.monitoring.failure").value("DISABLED"))
                .andExpect(jsonPath("$.monitoring.assetId").doesNotExist())
                .andExpect(jsonPath("$.monitoring.source").doesNotExist())
                .andReturn();

        assertThat(body(result))
                .as("两个依赖都没启用，编排仍然正常返回：状态码只是 200，数据状态由两侧 outcome/failure 表达")
                .doesNotContain("NOT_FOUND")
                .doesNotContain("null");
    }

    // ---------- 输入：一律交给用例判定 ----------

    @Test
    void anEmptyBodyAnEmptyObjectAndEveryIllegalAssetIdFollowTheUseCaseContract() throws Exception {
        record Case(String payload, String expectedAssetId) {
        }
        List<Case> cases = List.of(
                new Case(null, null),
                new Case("{}", null),
                new Case("{\"assetId\":null}", null),
                new Case("{\"assetId\":\"\"}", ""),
                new Case("{\"assetId\":\"   \"}", "   "),
                new Case("{\"assetId\":\"AST-1\"}", "AST-1"),
                new Case("{\"assetId\":\"ast-900001\"}", "ast-900001"),
                new Case("{\"assetId\":\" AST-900001\"}", " AST-900001"),
                new Case("{\"assetId\":\"AST-9000011\"}", "AST-9000011"));

        for (Case testCase : cases) {
            this.assetDiagnosisUseCase.reset();
            String payload = testCase.payload();

            MvcResult result = this.mockMvc.perform(post(DIAGNOSIS_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload == null ? new byte[0] : payload.getBytes(StandardCharsets.UTF_8)))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).as("payload=[%s]", payload).isEqualTo(400);
            assertThat(body(result)).as("payload=[%s]", payload)
                    .contains("\"code\":\"" + FlowDeskProblems.CODE_INVALID_REQUEST + "\"")
                    .contains("\"detail\":\"" + StubAssetDiagnosisUseCase.INVALID_INPUT_MESSAGE + "\"")
                    .contains("urn:flowdesk:problem:invalid-request");

            assertThat(this.assetDiagnosisUseCase.commands())
                    .as("控制器必须把请求交给用例一次，自己不做校验：payload=[%s]", payload)
                    .hasSize(1);
            assertThat(this.assetDiagnosisUseCase.commands().get(0).assetId())
                    .as("原值必须原样传递（不 trim、不修正）：payload=[%s]", payload)
                    .isEqualTo(testCase.expectedAssetId());
        }
    }

    @Test
    void aMalformedJsonBodyKeepsTheGlobalJsonContract() throws Exception {
        for (String malformed : List.of("{\"assetId\":", "   ", "not-json")) {
            this.mockMvc.perform(post(DIAGNOSIS_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(malformed))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(FlowDeskProblems.CODE_INVALID_REQUEST))
                    .andExpect(jsonPath("$.type").value("urn:flowdesk:problem:invalid-request"))
                    .andExpect(jsonPath("$.detail").value("请求体不是合法 JSON"));
        }

        assertThat(this.assetDiagnosisUseCase.commands())
                .as("坏 JSON 不得到达用例")
                .isEmpty();
    }

    // ---------- 上游失败：502 ----------

    @Test
    void aProviderFailureReturns502WithTheRequestIdAndWithoutTheCause() throws Exception {
        this.assetDiagnosisUseCase.willFail(new AiProviderException("req-from-service",
                new IllegalStateException("SENTINEL-CAUSE-MUST-NOT-LEAK")));

        MvcResult result = this.mockMvc.perform(post(DIAGNOSIS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":\"" + ASSET_ID + "\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AI_PROVIDER_ERROR"))
                .andExpect(jsonPath("$.type").value("urn:flowdesk:problem:ai-provider-error"))
                .andExpect(jsonPath("$.detail").value("上游 AI 服务暂时不可用，请稍后重试"))
                .andExpect(jsonPath("$.requestId").value("req-from-service"))
                .andReturn();

        assertThat(body(result))
                .as("失败响应不得回显 cause、异常类名或堆栈")
                .doesNotContain("SENTINEL-CAUSE-MUST-NOT-LEAK")
                .doesNotContain("IllegalStateException")
                .doesNotContain("Exception")
                .doesNotContain("stackTrace")
                .doesNotContain("java.");
    }

    // ---------- 不泄露 ----------

    @Test
    void theResponseCarriesNoPromptNoMcpPayloadNoEndpointAndNoSecret() throws Exception {
        this.assetDiagnosisUseCase.willReturn(result("资产在用 [A1]，监控偏高 [M1]。", true, List.of("A1", "M1"),
                ASSET_FOUND, MONITORING_FOUND));

        MvcResult result = this.mockMvc.perform(post(DIAGNOSIS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":\"" + ASSET_ID + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(body(result))
                .as("响应里不得出现提示词、边界标记、MCP 报文、端点、会话标识、配置或密钥")
                .doesNotContain("<<<FLOWDESK_DATA")
                .doesNotContain("allowedEvidenceIds")
                .doesNotContain("availability")
                .doesNotContain("system")
                .doesNotContain("jsonrpc")
                .doesNotContain("Mcp-Session-Id")
                .doesNotContain("structuredContent")
                .doesNotContain("http://")
                .doesNotContain("https://")
                .doesNotContain("127.0.0.1")
                .doesNotContain("8091")
                .doesNotContain("8092")
                .doesNotContain("Authorization")
                .doesNotContain("Bearer")
                .doesNotContain("DEEPSEEK")
                .doesNotContain("apiKey")
                .doesNotContain("api-key")
                .doesNotContain("ToolCallback")
                .doesNotContain("Exception")
                .doesNotContain("java.")
                .doesNotContain("com.flowdesk");
    }

    // ---------- 辅助 ----------

    private static AssetDiagnosisResult result(String answer, boolean grounded, List<String> usedEvidenceIds,
            AssetQueryResult asset, MonitoringSnapshotQueryResult monitoring) {

        return new AssetDiagnosisResult(REQUEST_ID, answer, grounded, usedEvidenceIds, asset, monitoring);
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * 用替身替换 Web 层的直接协作者（应用层用例接口）。
     *
     * <p>替身<b>按真实用例的规则</b>拒绝非法编号并抛 {@link AiRequestException}，
     * 因此「非法输入得到 400」这条契约仍然由应用层的输入规则决定，
     * 而不是由控制器里另写的一份校验决定。</p>
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class StubUseCaseConfiguration {

        @Bean
        StubAssetDiagnosisUseCase assetDiagnosisUseCase() {
            return new StubAssetDiagnosisUseCase();
        }
    }

    /**
     * 资产诊断用例替身：记录收到的命令，可按用例设置返回值或失败。
     */
    static final class StubAssetDiagnosisUseCase implements AssetDiagnosisUseCase {

        /** 输入不合法时的固定文案 —— 与 {@code AssetDiagnosisService.INVALID_INPUT_MESSAGE} 一致（该常量在 agent 层是包可见的）。 */
        static final String INVALID_INPUT_MESSAGE = "assetId 必须形如 AST-000001（AST- 加 6 位数字）";

        /** 两侧都没命中时的固定回答（与编排层一致）。 */
        static final String NO_RECORD_ANSWER = "未查询到该资产或可用的监控快照。";

        /** 没有可用证据时的固定降级回答（与编排层一致）。 */
        static final String INSUFFICIENT_EVIDENCE_ANSWER = "当前无法获得足够的资产与监控证据，暂时不能生成诊断结论。";

        private final List<AssetDiagnosisCommand> commands = new ArrayList<>();

        private AssetDiagnosisResult result = new AssetDiagnosisResult(REQUEST_ID, "占位 [A1]。", true, List.of("A1"),
                ASSET_FOUND, MONITORING_NOT_FOUND);

        private RuntimeException failure;

        @Override
        public AssetDiagnosisResult diagnose(AssetDiagnosisCommand command) {
            this.commands.add(command);
            if (command == null || !AssetIdentifier.isValid(command.assetId())) {
                throw new AiRequestException(INVALID_INPUT_MESSAGE);
            }
            if (this.failure != null) {
                throw this.failure;
            }
            return this.result;
        }

        void willReturn(AssetDiagnosisResult result) {
            this.result = result;
        }

        void willFail(RuntimeException failure) {
            this.failure = failure;
        }

        List<AssetDiagnosisCommand> commands() {
            return List.copyOf(this.commands);
        }

        void reset() {
            this.commands.clear();
            this.failure = null;
            this.result = new AssetDiagnosisResult(REQUEST_ID, "占位 [A1]。", true, List.of("A1"), ASSET_FOUND,
                    MONITORING_NOT_FOUND);
        }
    }
}
