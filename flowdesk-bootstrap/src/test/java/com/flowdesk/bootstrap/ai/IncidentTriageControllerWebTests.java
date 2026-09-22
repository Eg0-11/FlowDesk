package com.flowdesk.bootstrap.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flowdesk.application.ai.AiProviderException;
import com.flowdesk.application.ai.AiRequestException;
import com.flowdesk.application.ai.IncidentTriageCommand;
import com.flowdesk.application.ai.IncidentTriageResult;
import com.flowdesk.application.ai.IncidentTriageUseCase;
import com.flowdesk.application.ai.KnowledgeEvidence;
import com.flowdesk.application.ai.KnowledgeFailure;
import com.flowdesk.application.integration.AssetIdentifier;
import com.flowdesk.application.integration.AssetQueryResult;
import com.flowdesk.application.integration.AssetView;
import com.flowdesk.application.integration.HealthState;
import com.flowdesk.application.integration.MonitoringSnapshotQueryResult;
import com.flowdesk.application.integration.MonitoringSnapshotView;
import com.flowdesk.application.integration.QueryFailure;
import com.flowdesk.application.integration.SourceOrigin;
import com.flowdesk.application.knowledge.view.KnowledgeCitationView;
import com.flowdesk.application.knowledge.view.KnowledgeRetrievalView;
import com.flowdesk.bootstrap.web.FlowDeskProblems;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
 * 事件研判 HTTP 接口的契约测试（FD-0018-B，映射层）。
 *
 * <p>替换的是 Web 层的直接协作者 —— 应用层用例接口 {@link IncidentTriageUseCase} ——
 * <b>不</b>对 Graph、{@code ChatClient}、三个证据来源端口或编排类做任何深打桩：
 * 这一层要证明的是「控制器不做校验、不做编排、不做异常分类，只做 DTO 转换」，
 * 以及响应形状与状态码。真实 Graph 的接入由 {@code IncidentTriageHttpIntegrationTests} 覆盖。</p>
 *
 * <p>被反复验证的核心承诺有四条：</p>
 * <ul>
 *   <li><b>200 不代表三个依赖都成功</b>：完整、部分、三个来源全未命中、以及
 *       「无命中且至少一个失败」的降级结果都是 200，数据状态只能从
 *       {@code knowledge.status}/{@code asset.outcome}/{@code monitoring.outcome} 与
 *       {@code failure} 读出来；</li>
 *   <li><b>字段集合由状态决定</b>：未命中的一侧不输出伪造详情，失败的一侧不输出编号与来源，
 *       知识 {@code FAILED} 不输出 {@code retrieval}，{@code null} 字段直接省略；</li>
 *   <li><b>映射不做二次加工</b>：{@code requestId} 与 {@code grounded} 原样来自结果，
 *       引用顺序与执行路径顺序保持用例给出的顺序，{@code topK}/{@code minScore} 原样传给命令；</li>
 *   <li><b>输入校验与异常分类都不在这一层</b>：空 body、{@code {}}、非法编号与非法检索输入
 *       一律交给用例判定，400/502 由全局异常处理给出。</li>
 * </ul>
 */
@WebMvcTest(controllers = IncidentTriageController.class, properties = "flowdesk.ai.enabled=true")
@Import(IncidentTriageControllerWebTests.StubUseCaseConfiguration.class)
class IncidentTriageControllerWebTests {

    private static final String TRIAGE_PATH = "/api/v1/ai/incident-triage";

    private static final String ASSET_ID = "AST-900001";

    private static final String QUESTION = "服务器出现持续告警，应该如何排查？";

    private static final String REQUEST_ID = "9c1b7d20-1111-2222-3333-444455556666";

    private static final UUID DOCUMENT_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static final String DIGEST = "0123456789abcdef".repeat(4);

    private static final String CHUNK_CONTENT = "告警排查第一步：确认采样周期与阈值配置。";

    /** 完整路径的固定结果：知识（K1）+ 资产（A1）+ 监控（M1）。 */
    private static final List<String> FULL_PATH = List.of("validate_asset", "retrieve_knowledge", "query_asset",
            "query_monitoring", "verify_contracts", "evidence_gate", "generate_answer", "validate_citations",
            "finish");

    /** 无证据路径的固定结果。 */
    private static final List<String> FALLBACK_PATH = List.of("validate_asset", "retrieve_knowledge", "query_asset",
            "query_monitoring", "verify_contracts", "evidence_gate", "fallback_answer", "finish");

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
    private StubIncidentTriageUseCase incidentTriageUseCase;

    @BeforeEach
    void reset() {
        this.incidentTriageUseCase.reset();
    }

    // ---------- 完整结果 ----------

    @Test
    void threeFoundSourcesReturnTheFullAuditableResultInTheUseCaseOrder() throws Exception {
        this.incidentTriageUseCase.willReturn(new IncidentTriageResult(REQUEST_ID,
                "采样周期配置偏短 [M1]，资产在保 [A1]，建议按手册调整 [K1]。", true,
                // 刻意不是「规范顺序」：HTTP 层必须原样保留用例给出的引用顺序
                List.of("M1", "A1", "K1"), FULL_PATH, knowledgeFound(),
                ASSET_FOUND, MONITORING_FOUND));

        MvcResult result = this.mockMvc.perform(post(TRIAGE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":\"" + ASSET_ID + "\",\"question\":\"" + QUESTION + "\","
                                + "\"topK\":5,\"minScore\":0.3}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID))
                .andExpect(jsonPath("$.grounded").value(true))
                .andExpect(jsonPath("$.usedEvidenceIds.length()").value(3))
                .andExpect(jsonPath("$.usedEvidenceIds[0]").value("M1"))
                .andExpect(jsonPath("$.usedEvidenceIds[1]").value("A1"))
                .andExpect(jsonPath("$.usedEvidenceIds[2]").value("K1"))
                .andExpect(jsonPath("$.executionPath.length()").value(FULL_PATH.size()))
                .andExpect(jsonPath("$.executionPath[0]").value("validate_asset"))
                .andExpect(jsonPath("$.executionPath[6]").value("generate_answer"))
                .andExpect(jsonPath("$.executionPath[7]").value("validate_citations"))
                .andExpect(jsonPath("$.executionPath[8]").value("finish"))
                .andExpect(jsonPath("$.knowledge.status").value("FOUND"))
                .andExpect(jsonPath("$.knowledge.failure").doesNotExist())
                .andExpect(jsonPath("$.knowledge.retrieval.provider").value("dashscope"))
                .andExpect(jsonPath("$.knowledge.retrieval.model").value("text-embedding-v4"))
                .andExpect(jsonPath("$.knowledge.retrieval.dimensions").value(1024))
                .andExpect(jsonPath("$.knowledge.retrieval.topK").value(5))
                .andExpect(jsonPath("$.knowledge.retrieval.minScore").value(0.3))
                .andExpect(jsonPath("$.knowledge.retrieval.rankingMode").value("VECTOR_SIMILARITY"))
                .andExpect(jsonPath("$.knowledge.retrieval.rerankModel").doesNotExist())
                .andExpect(jsonPath("$.knowledge.retrieval.citations.length()").value(1))
                .andExpect(jsonPath("$.knowledge.retrieval.citations[0].citationId").value("K1"))
                .andExpect(jsonPath("$.knowledge.retrieval.citations[0].rank").value(1))
                .andExpect(jsonPath("$.knowledge.retrieval.citations[0].documentId").value(DOCUMENT_ID.toString()))
                .andExpect(jsonPath("$.knowledge.retrieval.citations[0].documentVersion").value(4))
                .andExpect(jsonPath("$.knowledge.retrieval.citations[0].documentTitle").value("告警处理手册"))
                .andExpect(jsonPath("$.knowledge.retrieval.citations[0].chunkIndex").value(2))
                .andExpect(jsonPath("$.knowledge.retrieval.citations[0].chunkSha256").value(DIGEST))
                .andExpect(jsonPath("$.knowledge.retrieval.citations[0].content").value(CHUNK_CONTENT))
                .andExpect(jsonPath("$.knowledge.retrieval.citations[0].score").value(0.87))
                .andExpect(jsonPath("$.knowledge.retrieval.citations[0].rerankScore").doesNotExist())
                .andExpect(jsonPath("$.asset.outcome").value("FOUND"))
                .andExpect(jsonPath("$.asset.assetId").value(ASSET_ID))
                .andExpect(jsonPath("$.asset.assetType").value("SERVER"))
                .andExpect(jsonPath("$.asset.status").value("IN_SERVICE"))
                .andExpect(jsonPath("$.asset.source").value("DEMO"))
                .andExpect(jsonPath("$.asset.failure").doesNotExist())
                .andExpect(jsonPath("$.monitoring.outcome").value("FOUND"))
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
                .doesNotContain("UNKNOWN")
                .doesNotContain("NOT_FOUND");
        assertThat(this.incidentTriageUseCase.commands())
                .as("可解析的请求只调用用例一次")
                .containsExactly(new IncidentTriageCommand(ASSET_ID, QUESTION, 5, 0.3));
    }

    @Test
    void theResponseIsNotAffectedByLaterMutationsOfTheUseCaseLists() throws Exception {
        List<String> mutableIds = new ArrayList<>(List.of("K1"));
        List<String> mutablePath = new ArrayList<>(FULL_PATH);
        this.incidentTriageUseCase.willReturn(new IncidentTriageResult(REQUEST_ID, "结论 [K1]。", true, mutableIds,
                mutablePath, knowledgeFound(), ASSET_NOT_FOUND, MONITORING_NOT_FOUND));

        this.mockMvc.perform(post(TRIAGE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":\"" + ASSET_ID + "\",\"question\":\"" + QUESTION + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedEvidenceIds.length()").value(1))
                .andExpect(jsonPath("$.executionPath.length()").value(FULL_PATH.size()));
    }

    // ---------- 部分命中与降级结果：全部 200 ----------

    @Test
    void aKnowledgeFailureWithAssetAndMonitoringHitsKeepsTheFailureVisible() throws Exception {
        this.incidentTriageUseCase.willReturn(new IncidentTriageResult(REQUEST_ID,
                "监控负载偏高 [M1]，资产在保 [A1]。", true, List.of("M1", "A1"), FULL_PATH,
                KnowledgeEvidence.failed(KnowledgeFailure.EMBEDDING_PROVIDER_UNAVAILABLE),
                ASSET_FOUND, MONITORING_FOUND));

        MvcResult result = this.mockMvc.perform(post(TRIAGE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":\"" + ASSET_ID + "\",\"question\":\"" + QUESTION + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounded").value(true))
                .andExpect(jsonPath("$.knowledge.status").value("FAILED"))
                .andExpect(jsonPath("$.knowledge.failure").value("EMBEDDING_PROVIDER_UNAVAILABLE"))
                .andExpect(jsonPath("$.knowledge.retrieval").doesNotExist())
                .andExpect(jsonPath("$.asset.outcome").value("FOUND"))
                .andExpect(jsonPath("$.monitoring.outcome").value("FOUND"))
                .andReturn();

        assertThat(body(result))
                .as("200 不等于三个依赖都成功：知识失败必须仍然看得见，且不得被写成未命中")
                .contains("\"status\":\"FAILED\"")
                .doesNotContain("NOT_FOUND")
                .doesNotContain("null");
    }

    @Test
    void anAssetFailureWithMonitoringHitKeepsTheRealFailureAndItsStableCode() throws Exception {
        this.incidentTriageUseCase.willReturn(new IncidentTriageResult(REQUEST_ID, "监控负载偏高 [M1]。", true,
                List.of("M1"), FULL_PATH, knowledgeNotFound(), AssetQueryResult.failed(QueryFailure.TIMEOUT),
                MONITORING_FOUND));

        MvcResult result = this.mockMvc.perform(post(TRIAGE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":\"" + ASSET_ID + "\",\"question\":\"" + QUESTION + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asset.outcome").value("FAILED"))
                .andExpect(jsonPath("$.asset.failure").value("TIMEOUT"))
                .andExpect(jsonPath("$.asset.assetId").doesNotExist())
                .andExpect(jsonPath("$.asset.source").doesNotExist())
                .andExpect(jsonPath("$.asset.assetType").doesNotExist())
                .andExpect(jsonPath("$.asset.status").doesNotExist())
                .andExpect(jsonPath("$.knowledge.status").value("NOT_FOUND"))
                .andExpect(jsonPath("$.knowledge.retrieval.citations.length()").value(0))
                .andExpect(jsonPath("$.monitoring.outcome").value("FOUND"))
                .andReturn();

        assertThat(body(result))
                .as("失败的一侧不输出编号与来源，也不得被改写成未找到")
                .contains("\"failure\":\"TIMEOUT\"")
                .doesNotContain("\"outcome\":\"NOT_FOUND\"")
                .doesNotContain("null");
    }

    @Test
    void everySourceNotFoundReturns200WithGroundedFalseAndNoCitations() throws Exception {
        this.incidentTriageUseCase.willReturn(new IncidentTriageResult(REQUEST_ID,
                "未找到可用于事件研判的资产、监控或知识证据。", false, List.of(), FALLBACK_PATH,
                knowledgeNotFound(), ASSET_NOT_FOUND, MONITORING_NOT_FOUND));

        MvcResult result = this.mockMvc.perform(post(TRIAGE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":\"" + ASSET_ID + "\",\"question\":\"" + QUESTION + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounded").value(false))
                .andExpect(jsonPath("$.answer").value("未找到可用于事件研判的资产、监控或知识证据。"))
                .andExpect(jsonPath("$.usedEvidenceIds").isArray())
                .andExpect(jsonPath("$.usedEvidenceIds.length()").value(0))
                .andExpect(jsonPath("$.knowledge.status").value("NOT_FOUND"))
                .andExpect(jsonPath("$.knowledge.retrieval.citations.length()").value(0))
                .andExpect(jsonPath("$.asset.outcome").value("NOT_FOUND"))
                .andExpect(jsonPath("$.asset.assetId").value(ASSET_ID))
                .andExpect(jsonPath("$.asset.source").value("DEMO"))
                .andExpect(jsonPath("$.asset.failure").doesNotExist())
                .andExpect(jsonPath("$.monitoring.outcome").value("NOT_FOUND"))
                .andExpect(jsonPath("$.monitoring.source").value("DEMO"))
                .andExpect(jsonPath("$.monitoring.cpuUtilizationPercent").doesNotExist())
                .andExpect(jsonPath("$.monitoring.activeAlertCount").doesNotExist())
                .andExpect(jsonPath("$.executionPath[6]").value("fallback_answer"))
                .andReturn();

        assertThat(body(result)).doesNotContain("null");
    }

    @Test
    void noEvidenceWithAFailureKeepsTheDegradedAnswerAndTheFailure() throws Exception {
        this.incidentTriageUseCase.willReturn(new IncidentTriageResult(REQUEST_ID,
                "当前无法获得足够证据，暂时不能完成事件研判。", false, List.of(), FALLBACK_PATH,
                KnowledgeEvidence.failed(KnowledgeFailure.DISABLED), ASSET_NOT_FOUND,
                MonitoringSnapshotQueryResult.failed(QueryFailure.UNAVAILABLE)));

        MvcResult result = this.mockMvc.perform(post(TRIAGE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":\"" + ASSET_ID + "\",\"question\":\"" + QUESTION + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounded").value(false))
                .andExpect(jsonPath("$.answer").value("当前无法获得足够证据，暂时不能完成事件研判。"))
                .andExpect(jsonPath("$.usedEvidenceIds.length()").value(0))
                .andExpect(jsonPath("$.knowledge.status").value("FAILED"))
                .andExpect(jsonPath("$.knowledge.failure").value("DISABLED"))
                .andExpect(jsonPath("$.knowledge.retrieval").doesNotExist())
                .andExpect(jsonPath("$.asset.outcome").value("NOT_FOUND"))
                .andExpect(jsonPath("$.monitoring.outcome").value("FAILED"))
                .andExpect(jsonPath("$.monitoring.failure").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.monitoring.assetId").doesNotExist())
                .andExpect(jsonPath("$.monitoring.source").doesNotExist())
                .andReturn();

        assertThat(body(result))
                .as("降级结果仍然如实返回三个来源状态，不能把失败写成未找到")
                .contains("\"outcome\":\"FAILED\"")
                .doesNotContain("null");
    }

    @Test
    void everyKnowledgeFailureValueIsMappedToItsStableName() throws Exception {
        for (KnowledgeFailure failure : KnowledgeFailure.values()) {
            this.incidentTriageUseCase.reset();
            this.incidentTriageUseCase.willReturn(new IncidentTriageResult(REQUEST_ID, "监控负载偏高 [M1]。", true,
                    List.of("M1"), FULL_PATH, KnowledgeEvidence.failed(failure), ASSET_NOT_FOUND,
                    MONITORING_FOUND));

            MvcResult result = this.mockMvc.perform(post(TRIAGE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"assetId\":\"" + ASSET_ID + "\",\"question\":\"" + QUESTION + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.knowledge.status").value("FAILED"))
                    .andExpect(jsonPath("$.knowledge.failure").value(failure.name()))
                    .andExpect(jsonPath("$.knowledge.retrieval").doesNotExist())
                    .andReturn();

            assertThat(body(result))
                    .as("failure=%s：失败分支不得伪造检索结果或空值占位", failure)
                    .doesNotContain("\"retrieval\":null")
                    .doesNotContain("\"citations\":null")
                    .doesNotContain("\"provider\":null");
        }
    }

    // ---------- 输入：一律交给用例判定 ----------

    @Test
    void topKAndMinScoreArePassedThroughUnchangedWhenOmittedOrExplicitlyNull() throws Exception {
        record Case(String payload, Integer topK, Double minScore) {
        }
        List<Case> cases = List.of(
                new Case("{\"assetId\":\"" + ASSET_ID + "\",\"question\":\"" + QUESTION + "\"}", null, null),
                new Case("{\"assetId\":\"" + ASSET_ID + "\",\"question\":\"" + QUESTION + "\","
                        + "\"topK\":null,\"minScore\":null}", null, null),
                new Case("{\"assetId\":\"" + ASSET_ID + "\",\"question\":\"" + QUESTION + "\","
                        + "\"topK\":7,\"minScore\":0.85}", 7, 0.85));

        for (Case testCase : cases) {
            this.incidentTriageUseCase.reset();
            this.incidentTriageUseCase.willReturn(new IncidentTriageResult(REQUEST_ID, "结论 [K1]。", true,
                    List.of("K1"), FULL_PATH, knowledgeFound(), ASSET_NOT_FOUND, MONITORING_NOT_FOUND));

            this.mockMvc.perform(post(TRIAGE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(testCase.payload()))
                    .andExpect(status().isOk());

            assertThat(this.incidentTriageUseCase.commands())
                    .as("payload=%s", testCase.payload())
                    .containsExactly(new IncidentTriageCommand(ASSET_ID, QUESTION, testCase.topK(),
                            testCase.minScore()));
        }
    }

    @Test
    void anEmptyBodyAnEmptyObjectAndExplicitNullsBecomeAnAllNullCommand() throws Exception {
        record Case(String payload, String assetId, String question, Integer topK, Double minScore) {
        }
        List<Case> cases = List.of(
                new Case(null, null, null, null, null),
                new Case("{}", null, null, null, null),
                new Case("{\"assetId\":null,\"question\":null,\"topK\":null,\"minScore\":null}", null, null, null,
                        null),
                // 非法值原样传递：不 trim、不规范化、不补默认值
                new Case("{\"assetId\":\" AST-900001 \",\"question\":\"   \"}", " AST-900001 ", "   ", null, null),
                new Case("{\"assetId\":\"AST-1\",\"question\":\"" + QUESTION + "\"}", "AST-1", QUESTION, null, null),
                new Case("{\"assetId\":\"" + ASSET_ID + "\",\"question\":\"" + QUESTION + "\",\"topK\":0}",
                        ASSET_ID, QUESTION, 0, null));

        for (Case testCase : cases) {
            this.incidentTriageUseCase.reset();
            String payload = testCase.payload();

            MvcResult result = this.mockMvc.perform(post(TRIAGE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload == null ? new byte[0] : payload.getBytes(StandardCharsets.UTF_8)))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).as("payload=[%s]", payload).isEqualTo(400);
            assertThat(body(result)).as("payload=[%s]", payload)
                    .contains("\"code\":\"" + FlowDeskProblems.CODE_INVALID_REQUEST + "\"")
                    .contains("urn:flowdesk:problem:invalid-request");

            assertThat(this.incidentTriageUseCase.commands())
                    .as("控制器必须把请求交给用例一次，自己不做校验：payload=[%s]", payload)
                    .containsExactly(new IncidentTriageCommand(testCase.assetId(), testCase.question(),
                            testCase.topK(), testCase.minScore()));
        }
    }

    @Test
    void anIllegalQuestionOrBoundsFollowTheUseCaseContractWithoutAnyTrim() throws Exception {
        record Case(String payload, String question, Integer topK, Double minScore) {
        }
        List<Case> cases = List.of(
                new Case("{\"assetId\":\"" + ASSET_ID + "\",\"question\":\"\"}", "", null, null),
                new Case("{\"assetId\":\"" + ASSET_ID + "\",\"question\":\"   \"}", "   ", null, null),
                new Case("{\"assetId\":\"" + ASSET_ID + "\",\"question\":\"" + QUESTION + "\",\"topK\":21}",
                        QUESTION, 21, null),
                new Case("{\"assetId\":\"" + ASSET_ID + "\",\"question\":\"" + QUESTION + "\",\"minScore\":1.5}",
                        QUESTION, null, 1.5));

        for (Case testCase : cases) {
            this.incidentTriageUseCase.reset();

            this.mockMvc.perform(post(TRIAGE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(testCase.payload()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(FlowDeskProblems.CODE_INVALID_REQUEST));

            assertThat(this.incidentTriageUseCase.commands())
                    .as("payload=%s", testCase.payload())
                    .containsExactly(new IncidentTriageCommand(ASSET_ID, testCase.question(), testCase.topK(),
                            testCase.minScore()));
        }
    }

    @Test
    void aMalformedJsonBodyKeepsTheGlobalJsonContract() throws Exception {
        for (String malformed : List.of("{\"assetId\":", "   ", "not-json")) {
            this.mockMvc.perform(post(TRIAGE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(malformed))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(FlowDeskProblems.CODE_INVALID_REQUEST))
                    .andExpect(jsonPath("$.type").value("urn:flowdesk:problem:invalid-request"))
                    .andExpect(jsonPath("$.detail").value("请求体不是合法 JSON"));
        }

        assertThat(this.incidentTriageUseCase.commands())
                .as("坏 JSON 不得到达用例")
                .isEmpty();
    }

    @Test
    void anUnsupportedContentTypeReturns415AndAnUnsatisfiableAcceptReturns406() throws Exception {
        this.mockMvc.perform(post(TRIAGE_PATH)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("{\"assetId\":\"" + ASSET_ID + "\"}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(FlowDeskProblems.CODE_UNSUPPORTED_MEDIA_TYPE))
                .andExpect(jsonPath("$.type").value("urn:flowdesk:problem:unsupported-media-type"));

        this.mockMvc.perform(post(TRIAGE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_XML)
                        .content("{\"assetId\":\"" + ASSET_ID + "\"}"))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.code").value(FlowDeskProblems.CODE_NOT_ACCEPTABLE));

        assertThat(this.incidentTriageUseCase.commands())
                .as("媒体类型不匹配时用例零调用")
                .isEmpty();
    }

    // ---------- 上游失败：502 ----------

    @Test
    void aProviderFailureReturns502WithTheRequestIdAndWithoutTheCauseOrAnswer() throws Exception {
        String sentinelAnswer = "SENTINEL-ANSWER-MUST-NOT-LEAK-该资产一切正常";
        this.incidentTriageUseCase.willFail(new AiProviderException("req-from-service",
                new IllegalStateException("SENTINEL-CAUSE-MUST-NOT-LEAK")));

        MvcResult result = this.mockMvc.perform(post(TRIAGE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":\"" + ASSET_ID + "\",\"question\":\"" + QUESTION + "\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AI_PROVIDER_ERROR"))
                .andExpect(jsonPath("$.type").value("urn:flowdesk:problem:ai-provider-error"))
                .andExpect(jsonPath("$.detail").value("上游 AI 服务暂时不可用，请稍后重试"))
                .andExpect(jsonPath("$.requestId").value("req-from-service"))
                .andReturn();

        assertThat(body(result))
                .as("失败响应不得回显 cause、异常类名、堆栈或模型答案")
                .doesNotContain("SENTINEL-CAUSE-MUST-NOT-LEAK")
                .doesNotContain(sentinelAnswer)
                .doesNotContain("IllegalStateException")
                .doesNotContain("Exception")
                .doesNotContain("stackTrace")
                .doesNotContain("java.")
                .doesNotContain("answer")
                .doesNotContain("executionPath");
    }

    // ---------- 不泄露 ----------

    @Test
    void theResponseCarriesNoQuestionNoGraphStateNoSqlAndNoSecret() throws Exception {
        this.incidentTriageUseCase.willReturn(new IncidentTriageResult(REQUEST_ID,
                "结论 [K1]。", true, List.of("K1"), FULL_PATH, knowledgeFound(), ASSET_NOT_FOUND,
                MONITORING_NOT_FOUND));

        MvcResult result = this.mockMvc.perform(post(TRIAGE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":\"" + ASSET_ID + "\",\"question\":\"" + QUESTION + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(body(result))
                .as("响应里不得出现问题原文、输入命令、Graph 内部状态、来源进度、SQL、端点或密钥")
                .doesNotContain(QUESTION)
                .doesNotContain("question")
                .doesNotContain("command")
                .doesNotContain("sourceProgress")
                .doesNotContain("inputRejection")
                .doesNotContain("IncidentTriageCall")
                .doesNotContain("OverAllState")
                .doesNotContain("CompiledGraph")
                .doesNotContain("<<<FLOWDESK_TRIAGE_DATA")
                .doesNotContain("availability")
                .doesNotContain("SELECT")
                .doesNotContain("jdbc:")
                .doesNotContain("vector(")
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
                .doesNotContain("apiKey")
                .doesNotContain("api-key")
                .doesNotContain("ToolCallback")
                .doesNotContain("Exception")
                .doesNotContain("java.")
                .doesNotContain("com.flowdesk");
    }

    // ---------- 辅助 ----------

    private static KnowledgeEvidence knowledgeFound() {
        return KnowledgeEvidence.found(KnowledgeRetrievalView.vectorOrdered("dashscope", "text-embedding-v4",
                1024, 5, 0.3, List.of(KnowledgeCitationView.vectorOnly("K1", 1, DOCUMENT_ID, 4L, "告警处理手册", 2,
                        DIGEST, CHUNK_CONTENT, 0.87))));
    }

    private static KnowledgeEvidence knowledgeNotFound() {
        return KnowledgeEvidence.notFound(KnowledgeRetrievalView.vectorOrdered("dashscope", "text-embedding-v4",
                1024, 5, 0.3, List.of()));
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * 用替身替换 Web 层的直接协作者（应用层用例接口）。
     *
     * <p>替身<b>按真实用例的规则</b>拒绝非法输入并抛 {@link AiRequestException}：
     * 编号规则直接复用 application 层的 {@code AssetIdentifier}，检索输入规则沿用检索用例的
     * 边界（非空问题、{@code topK} 在 1..20、{@code minScore} 在 0..1）。
     * 因此「非法输入得到 400」这条契约仍然由应用层的输入规则决定，
     * 而不是由控制器里另写的一份校验决定。</p>
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class StubUseCaseConfiguration {

        @Bean
        StubIncidentTriageUseCase incidentTriageUseCase() {
            return new StubIncidentTriageUseCase();
        }
    }

    /**
     * 事件研判用例替身：记录收到的命令，可按用例设置返回值或失败。
     */
    static final class StubIncidentTriageUseCase implements IncidentTriageUseCase {

        /** 非法 {@code assetId} 时的固定文案（与 {@code IncidentTriageGraph} 一致）。 */
        static final String INVALID_ASSET_ID_MESSAGE = "assetId 必须形如 AST-000001（AST- 加 6 位数字）";

        /** 非法检索输入时的固定文案（检索用例对外的契约文案）。 */
        static final String INVALID_RETRIEVAL_MESSAGE = "检索请求不合法";

        private final List<IncidentTriageCommand> commands = new ArrayList<>();

        private IncidentTriageResult result = new IncidentTriageResult(REQUEST_ID, "占位 [K1]。", true, List.of("K1"),
                FULL_PATH, knowledgeFound(), ASSET_NOT_FOUND, MONITORING_NOT_FOUND);

        private RuntimeException failure;

        @Override
        public IncidentTriageResult triage(IncidentTriageCommand command) {
            this.commands.add(command);
            if (command == null || !AssetIdentifier.isValid(command.assetId())) {
                throw new AiRequestException(INVALID_ASSET_ID_MESSAGE);
            }
            String question = command.question();
            if (question == null || question.isBlank()) {
                throw new AiRequestException(INVALID_RETRIEVAL_MESSAGE);
            }
            Integer topK = command.topK();
            if (topK != null && (topK < 1 || topK > 20)) {
                throw new AiRequestException(INVALID_RETRIEVAL_MESSAGE);
            }
            Double minScore = command.minScore();
            if (minScore != null && (!Double.isFinite(minScore) || minScore < 0.0 || minScore > 1.0)) {
                throw new AiRequestException(INVALID_RETRIEVAL_MESSAGE);
            }
            if (this.failure != null) {
                throw this.failure;
            }
            return this.result;
        }

        void willReturn(IncidentTriageResult result) {
            this.result = result;
        }

        void willFail(RuntimeException failure) {
            this.failure = failure;
        }

        List<IncidentTriageCommand> commands() {
            return List.copyOf(this.commands);
        }

        void reset() {
            this.commands.clear();
            this.failure = null;
            this.result = new IncidentTriageResult(REQUEST_ID, "占位 [K1]。", true, List.of("K1"), FULL_PATH,
                    knowledgeFound(), ASSET_NOT_FOUND, MONITORING_NOT_FOUND);
        }
    }
}
