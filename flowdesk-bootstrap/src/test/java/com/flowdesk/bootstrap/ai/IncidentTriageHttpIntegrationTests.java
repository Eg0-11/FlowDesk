package com.flowdesk.bootstrap.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flowdesk.application.integration.AssetQueryResult;
import com.flowdesk.application.integration.AssetView;
import com.flowdesk.application.integration.MonitoringSnapshotQueryResult;
import com.flowdesk.application.integration.MonitoringSnapshotView;
import com.flowdesk.application.integration.HealthState;
import com.flowdesk.application.integration.SourceOrigin;
import com.flowdesk.application.integration.port.out.AssetQueryPort;
import com.flowdesk.application.integration.port.out.MonitoringSnapshotQueryPort;
import com.flowdesk.application.knowledge.port.out.KnowledgeQueryEmbeddingPort;
import com.flowdesk.application.knowledge.port.out.KnowledgeVectorMatch;
import com.flowdesk.application.knowledge.port.out.KnowledgeVectorSearchPort;
import com.flowdesk.bootstrap.web.FlowDeskProblems;
import com.flowdesk.domain.knowledge.EmbeddingDescriptor;
import com.flowdesk.domain.knowledge.KnowledgeDocumentId;
import com.flowdesk.domain.knowledge.KnowledgeQueryEmbedding;
import com.flowdesk.domain.knowledge.Sha256Digest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 事件研判 HTTP 接口的真实接入测试（FD-0018-B）。
 *
 * <p>与 {@code IncidentTriageControllerWebTests} 的分工：那一份用用例替身证明「HTTP 映射与状态码」，
 * 这一份走<b>真实链路</b> —— 真实 Spring 上下文、真实 HTTP（MockMvc）、真实的
 * {@code IncidentTriageService} 与装配期编译好的 {@code CompiledGraph}、<b>真实的检索用例</b>
 * （{@code KnowledgeRetrievalService}）以及真实 {@code ChatClient}（指向本地合成的 OpenAI 端点）。</p>
 *
 * <p>被替换的只有<b>出站端口</b>：查询向量、向量检索、资产查询、监控快照查询四个替身，
 * 它们都记录调用次数，因此「某条分支零调用」是被计数证明的，而不是靠一个"永远不调用"的替身声称的。
 * 反向对照由前两条用例提供：同一条链路上这四个替身确实各自被调用过，模型端也确实收到过请求。</p>
 *
 * <p><b>不是真实外部依赖</b>：模型端是本机合成的 OpenAI 兼容端点（<b>不是</b> DeepSeek），
 * 知识向量与切片来自替身（<b>不是</b> DashScope 与 pgvector），资产/监控也不经过两个真实 MCP 服务。
 * 因此本测试不改变 {@code LIVE_SMOKE}/{@code MCP_LIVE}/{@code POSTGRES_LIVE}/{@code DASHSCOPE_LIVE}
 * 的 {@code NOT_RUN} 状态。</p>
 */
@SpringBootTest(properties = {
        "spring.profiles.active=deepseek",
        "DEEPSEEK_API_KEY=test-fake-key-not-a-real-secret",
        "spring.datasource.url=jdbc:h2:mem:flowdesk_incident_triage_http;MODE=PostgreSQL;"
                + "DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "flowdesk.knowledge.storage.root=target/incident-triage-http-it",
        // 打开向量化，让检索用例真的走到「规范化 → 校验 → 查询向量 → 向量检索」这条链路；
        // 真实模型与真实向量库被下面的替身端口挡住，因此不会发出任何外部请求。
        "flowdesk.knowledge.embedding.enabled=true",
        "spring.ai.dashscope.api-key=test-fake-key-not-a-real-secret",
        // 覆盖生产的「环境一致性」校验：本测试用 H2，只验证 HTTP 与编排链路
        "spring.main.allow-bean-definition-overriding=true"
})
@AutoConfigureMockMvc
class IncidentTriageHttpIntegrationTests {

    private static final String TRIAGE_PATH = "/api/v1/ai/incident-triage";

    private static final String ASSET_ID = "AST-900001";

    private static final String QUESTION = "SENTINEL-QUESTION-服务器出现持续告警，应该如何排查？";

    private static final UUID DOCUMENT_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static final String DIGEST = "0123456789abcdef".repeat(4);

    private static final String CHUNK_CONTENT = "SENTINEL-CHUNK-告警排查第一步：确认采样周期与阈值配置。";

    private static final List<String> FULL_PATH = List.of("validate_asset", "retrieve_knowledge", "query_asset",
            "query_monitoring", "verify_contracts", "evidence_gate", "generate_answer", "validate_citations",
            "finish");

    private static final List<String> FALLBACK_PATH = List.of("validate_asset", "retrieve_knowledge", "query_asset",
            "query_monitoring", "verify_contracts", "evidence_gate", "fallback_answer", "finish");

    private static SyntheticOpenAiEndpoint endpoint;

    @DynamicPropertySource
    static void pointAtSyntheticEndpoint(DynamicPropertyRegistry registry) throws IOException {
        if (endpoint == null) {
            endpoint = SyntheticOpenAiEndpoint.startWithAnswer("占位回答 [K1]。");
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
    private MockMvc mockMvc;

    @Autowired
    private StubQueryEmbeddingPort queryEmbeddingPort;

    @Autowired
    private StubVectorSearchPort vectorSearchPort;

    @Autowired
    private CountingAssetPort assetQueryPort;

    @Autowired
    private CountingMonitoringPort monitoringSnapshotQueryPort;

    @BeforeEach
    void resetStubs() {
        this.queryEmbeddingPort.reset();
        this.vectorSearchPort.reset();
        this.assetQueryPort.reset();
        this.monitoringSnapshotQueryPort.reset();
        endpoint.clearRequests();
    }

    // ---------- 1. 三个来源都没有证据：真实降级路径、模型零调用 ----------

    @Test
    void noEvidenceAtAllWalksTheRealFallbackPathAndNeverCallsTheModel() throws Exception {
        this.vectorSearchPort.willReturn(List.of());
        this.assetQueryPort.willReturn(AssetQueryResult.notFound(ASSET_ID, SourceOrigin.DEMO));
        this.monitoringSnapshotQueryPort.willReturn(
                MonitoringSnapshotQueryResult.notFound(ASSET_ID, SourceOrigin.DEMO));

        MvcResult result = this.mockMvc.perform(post(TRIAGE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":\"" + ASSET_ID + "\",\"question\":\"" + QUESTION + "\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.grounded").value(false))
                .andExpect(jsonPath("$.answer").value("未找到可用于事件研判的资产、监控或知识证据。"))
                .andExpect(jsonPath("$.usedEvidenceIds.length()").value(0))
                .andExpect(jsonPath("$.executionPath.length()").value(FALLBACK_PATH.size()))
                .andExpect(jsonPath("$.executionPath[5]").value("evidence_gate"))
                .andExpect(jsonPath("$.executionPath[6]").value("fallback_answer"))
                .andExpect(jsonPath("$.executionPath[7]").value("finish"))
                .andExpect(jsonPath("$.knowledge.status").value("NOT_FOUND"))
                .andExpect(jsonPath("$.knowledge.retrieval.citations.length()").value(0))
                .andExpect(jsonPath("$.knowledge.retrieval.rankingMode").value("VECTOR_SIMILARITY"))
                .andExpect(jsonPath("$.asset.outcome").value("NOT_FOUND"))
                .andExpect(jsonPath("$.monitoring.outcome").value("NOT_FOUND"))
                .andReturn();

        assertThat(endpoint.requests())
                .as("无证据路径必须完全不调用模型")
                .isEmpty();
        assertThat(body(result)).doesNotContain("null");

        // 正向对照：这条降级路径确实经过了四个出站端口，因此「零调用」断言不是空转
        assertThat(this.queryEmbeddingPort.calls()).as("检索走过了查询向量端口").isEqualTo(1);
        assertThat(this.vectorSearchPort.calls()).as("检索走过了向量检索端口").isEqualTo(1);
        assertThat(this.assetQueryPort.calls()).as("资产端口被调用一次").isEqualTo(1);
        assertThat(this.monitoringSnapshotQueryPort.calls()).as("监控端口被调用一次").isEqualTo(1);
    }

    // ---------- 2. 至少一个来源命中：模型一次、引用正确 ----------

    @Test
    void aKnowledgeHitCallsTheModelOnceAndReturnsTheValidatedCitation() throws Exception {
        this.vectorSearchPort.willReturn(List.of(match(2, 0.87, "告警处理手册", CHUNK_CONTENT)));
        this.assetQueryPort.willReturn(AssetQueryResult.notFound(ASSET_ID, SourceOrigin.DEMO));
        this.monitoringSnapshotQueryPort.willReturn(
                MonitoringSnapshotQueryResult.notFound(ASSET_ID, SourceOrigin.DEMO));
        endpoint.willAnswer("现象与建议：按手册先确认采样周期 [K1]。");

        MvcResult result = this.mockMvc.perform(post(TRIAGE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":\"" + ASSET_ID + "\",\"question\":\"" + QUESTION + "\","
                                + "\"topK\":5,\"minScore\":0.3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounded").value(true))
                .andExpect(jsonPath("$.answer").value("现象与建议：按手册先确认采样周期 [K1]。"))
                .andExpect(jsonPath("$.usedEvidenceIds.length()").value(1))
                .andExpect(jsonPath("$.usedEvidenceIds[0]").value("K1"))
                .andExpect(jsonPath("$.executionPath.length()").value(FULL_PATH.size()))
                .andExpect(jsonPath("$.executionPath[6]").value("generate_answer"))
                .andExpect(jsonPath("$.executionPath[7]").value("validate_citations"))
                .andExpect(jsonPath("$.knowledge.status").value("FOUND"))
                .andExpect(jsonPath("$.knowledge.retrieval.provider").value("dashscope"))
                .andExpect(jsonPath("$.knowledge.retrieval.model").value("text-embedding-v4"))
                .andExpect(jsonPath("$.knowledge.retrieval.dimensions").value(1024))
                .andExpect(jsonPath("$.knowledge.retrieval.topK").value(5))
                .andExpect(jsonPath("$.knowledge.retrieval.minScore").value(0.3))
                .andExpect(jsonPath("$.knowledge.retrieval.citations.length()").value(1))
                .andExpect(jsonPath("$.knowledge.retrieval.citations[0].citationId").value("K1"))
                .andExpect(jsonPath("$.knowledge.retrieval.citations[0].documentId").value(DOCUMENT_ID.toString()))
                .andExpect(jsonPath("$.knowledge.retrieval.citations[0].chunkSha256").value(DIGEST))
                .andExpect(jsonPath("$.knowledge.retrieval.citations[0].content").value(CHUNK_CONTENT))
                .andExpect(jsonPath("$.asset.outcome").value("NOT_FOUND"))
                .andExpect(jsonPath("$.monitoring.outcome").value("NOT_FOUND"))
                .andReturn();

        // 正向对照：模型端真的收到过请求，且只有一次
        assertThat(endpoint.requests()).as("命中路径只调用模型一次").hasSize(1);
        assertThat(body(result)).doesNotContain("null");
    }

    // ---------- 2b. 资产与监控命中：复用资产诊断已验收的字段契约 ----------

    @Test
    void assetAndMonitoringHitsReuseTheAcceptedAssetDiagnosisFieldContract() throws Exception {
        this.vectorSearchPort.willReturn(List.of());
        this.assetQueryPort.willReturnFound();
        this.monitoringSnapshotQueryPort.willReturnFound();
        endpoint.willAnswer("监控负载偏高 [M1]，资产在保 [A1]。");

        MvcResult result = this.mockMvc.perform(post(TRIAGE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":\"" + ASSET_ID + "\",\"question\":\"" + QUESTION + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounded").value(true))
                .andExpect(jsonPath("$.usedEvidenceIds.length()").value(2))
                .andExpect(jsonPath("$.usedEvidenceIds[0]").value("M1"))
                .andExpect(jsonPath("$.usedEvidenceIds[1]").value("A1"))
                .andExpect(jsonPath("$.knowledge.status").value("NOT_FOUND"))
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
                .andReturn();

        assertThat(endpoint.requests()).hasSize(1);
        assertThat(body(result))
                .as("两侧命中的数值必须是真实值，不能出现「未命中才有的 0 占位」或多余空值")
                .contains("\"knowledge\":{\"status\":\"NOT_FOUND\"")
                .doesNotContain("null");
    }

    // ---------- 3. 模型给出非法引用：502 + requestId，不泄漏答案 ----------
    @Test
    void anUnknownCitationFromTheModelReturns502WithTheRequestIdAndWithoutTheAnswer() throws Exception {
        this.vectorSearchPort.willReturn(List.of(match(0, 0.9, "告警处理手册", CHUNK_CONTENT)));
        this.assetQueryPort.willReturn(AssetQueryResult.notFound(ASSET_ID, SourceOrigin.DEMO));
        this.monitoringSnapshotQueryPort.willReturn(
                MonitoringSnapshotQueryResult.notFound(ASSET_ID, SourceOrigin.DEMO));
        endpoint.willAnswer("SENTINEL-MODEL-ANSWER 本次结论引用了不存在的编号 [K9]。");

        MvcResult result = this.mockMvc.perform(post(TRIAGE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":\"" + ASSET_ID + "\",\"question\":\"" + QUESTION + "\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(FlowDeskProblems.CODE_AI_PROVIDER_ERROR))
                .andExpect(jsonPath("$.type").value("urn:flowdesk:problem:ai-provider-error"))
                .andExpect(jsonPath("$.detail").value("上游 AI 服务暂时不可用，请稍后重试"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andReturn();

        assertThat(endpoint.requests())
                .as("模型确实被调用过一次：失败发生在引用校验，而不是在生成之前")
                .hasSize(1);
        assertThat(body(result))
                .as("502 不得回显模型答案或引用编号，也不得泄漏内部材料")
                .doesNotContain("SENTINEL-MODEL-ANSWER")
                .doesNotContain("[K9]")
                .doesNotContain(CHUNK_CONTENT)
                .doesNotContain("Exception")
                .doesNotContain("java.")
                .doesNotContain("com.flowdesk");
    }

    // ---------- 4. 非法 assetId：400，三个来源与模型零调用 ----------

    @Test
    void anIllegalAssetIdReturns400BeforeAnySourceOrModelIsTouched() throws Exception {
        for (String assetId : List.of("AST-1", "ast-900001", " AST-900001", "AST-9000011", "")) {
            this.mockMvc.perform(post(TRIAGE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"assetId\":\"" + assetId + "\",\"question\":\"" + QUESTION + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(FlowDeskProblems.CODE_INVALID_REQUEST))
                    .andExpect(jsonPath("$.type").value("urn:flowdesk:problem:invalid-request"))
                    .andExpect(jsonPath("$.detail")
                            .value("assetId 必须形如 AST-000001（AST- 加 6 位数字）"));
        }

        assertThat(this.queryEmbeddingPort.calls()).as("非法 assetId：查询向量零调用").isZero();
        assertThat(this.vectorSearchPort.calls()).as("非法 assetId：向量检索零调用").isZero();
        assertThat(this.assetQueryPort.calls()).as("非法 assetId：资产端口零调用").isZero();
        assertThat(this.monitoringSnapshotQueryPort.calls()).as("非法 assetId：监控端口零调用").isZero();
        assertThat(endpoint.requests()).as("非法 assetId：模型零调用").isEmpty();
    }

    // ---------- 5. 真实检索用例判定非法检索输入：400，全链路零调用 ----------

    @Test
    void theRealRetrievalServiceRejectsIllegalQuestionAndBoundsBeforeAnythingIsCalled() throws Exception {
        record Case(String payload, String expectedDetail) {
        }
        List<Case> cases = List.of(
                new Case("{\"assetId\":\"" + ASSET_ID + "\",\"question\":\"\"}", "query 不能为空"),
                new Case("{\"assetId\":\"" + ASSET_ID + "\",\"question\":\"   \"}", "query 不能为空"),
                new Case("{\"assetId\":\"" + ASSET_ID + "\",\"question\":null}", "query 不能为空"),
                new Case("{\"assetId\":\"" + ASSET_ID + "\",\"question\":\""
                        + QUESTION + "\",\"topK\":0}", "topK 必须在 1 到 20 之间"),
                new Case("{\"assetId\":\"" + ASSET_ID + "\",\"question\":\""
                        + QUESTION + "\",\"topK\":21}", "topK 必须在 1 到 20 之间"),
                new Case("{\"assetId\":\"" + ASSET_ID + "\",\"question\":\""
                        + QUESTION + "\",\"minScore\":1.5}", "minScore 必须是 0.0 到 1.0 之间的有限数值"));

        for (Case testCase : cases) {
            resetStubs();

            MvcResult result = this.mockMvc.perform(post(TRIAGE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(testCase.payload()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(FlowDeskProblems.CODE_INVALID_REQUEST))
                    .andReturn();

            assertThat(body(result))
                    .as("payload=%s：400 文案由真实检索用例给出，原样透传", testCase.payload())
                    .contains("\"detail\":\"" + testCase.expectedDetail() + "\"");
        }

        assertThat(this.queryEmbeddingPort.calls()).as("非法检索输入：Embedding 零调用").isZero();
        assertThat(this.vectorSearchPort.calls()).as("非法检索输入：数据库/向量检索零调用").isZero();
        assertThat(this.assetQueryPort.calls()).as("非法检索输入：资产端口（MCP）零调用").isZero();
        assertThat(this.monitoringSnapshotQueryPort.calls()).as("非法检索输入：监控端口（MCP）零调用").isZero();
        assertThat(endpoint.requests()).as("非法检索输入：Chat 模型零调用").isEmpty();
    }

    // ---------- 辅助 ----------

    private static KnowledgeVectorMatch match(int chunkIndex, double score, String title, String content) {
        return new KnowledgeVectorMatch(KnowledgeDocumentId.of(DOCUMENT_ID), 4L, title, chunkIndex,
                Sha256Digest.of(DIGEST), content, score);
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * 出站端口替身 + 放行「启用向量化」的启动期环境校验（测试环境是 H2）。
     *
     * <p>与 {@code KnowledgeSearchWebTests} 同一套做法：保留真实检索用例，只替换它下面的端口 ——
     * 不复制检索链路自己的测试，那一份契约已经由既有测试锁定。</p>
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class StubOutboundPortsConfiguration {

        @Bean
        @Primary
        Boolean knowledgeEmbeddingConsistency() {
            return Boolean.TRUE;
        }

        @Bean
        @Primary
        Boolean knowledgeRetrievalConsistency() {
            return Boolean.TRUE;
        }

        @Bean
        @Primary
        org.springframework.ai.embedding.EmbeddingModel stubEmbeddingModel() {
            return new org.springframework.ai.embedding.EmbeddingModel() {

                @Override
                public org.springframework.ai.embedding.EmbeddingResponse call(
                        org.springframework.ai.embedding.EmbeddingRequest request) {
                    return new org.springframework.ai.embedding.EmbeddingResponse(java.util.List.of());
                }

                @Override
                public float[] embed(org.springframework.ai.document.Document document) {
                    return new float[EmbeddingDescriptor.REQUIRED_DIMENSIONS];
                }
            };
        }

        @Bean
        @Primary
        StubQueryEmbeddingPort stubQueryEmbeddingPort() {
            return new StubQueryEmbeddingPort();
        }

        @Bean
        @Primary
        StubVectorSearchPort stubVectorSearchPort() {
            return new StubVectorSearchPort();
        }

        @Bean
        @Primary
        CountingAssetPort countingAssetPort() {
            return new CountingAssetPort();
        }

        @Bean
        @Primary
        CountingMonitoringPort countingMonitoringPort() {
            return new CountingMonitoringPort();
        }
    }

    /** 查询向量替身：记录调用次数，返回维度合法、恒定非零的向量。 */
    static final class StubQueryEmbeddingPort implements KnowledgeQueryEmbeddingPort {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public float[] embedQuery(String query, EmbeddingDescriptor descriptor) {
            this.calls.incrementAndGet();
            float[] vector = new float[EmbeddingDescriptor.REQUIRED_DIMENSIONS];
            Arrays.fill(vector, 0.5f);
            return vector;
        }

        int calls() {
            return this.calls.get();
        }

        void reset() {
            this.calls.set(0);
        }
    }

    /** 向量检索替身：记录调用次数，结果可按用例设置（默认为「一条都没命中」）。 */
    static final class StubVectorSearchPort implements KnowledgeVectorSearchPort {

        private final AtomicInteger calls = new AtomicInteger();

        private List<KnowledgeVectorMatch> matches = List.of();

        @Override
        public List<KnowledgeVectorMatch> search(KnowledgeQueryEmbedding queryEmbedding, double minScore, int topK) {
            this.calls.incrementAndGet();
            return this.matches;
        }

        void willReturn(List<KnowledgeVectorMatch> matches) {
            this.matches = List.copyOf(matches);
        }

        int calls() {
            return this.calls.get();
        }

        void reset() {
            this.calls.set(0);
            this.matches = List.of();
        }
    }

    /** 资产查询端口替身：记录调用次数（本测试不经过真实 MCP 服务）。 */
    static final class CountingAssetPort implements AssetQueryPort {

        private final AtomicInteger calls = new AtomicInteger();

        private AssetQueryResult result = AssetQueryResult.notFound("AST-000000", SourceOrigin.DEMO);

        @Override
        public AssetQueryResult findAsset(String assetId) {
            this.calls.incrementAndGet();
            return this.result;
        }

        void willReturn(AssetQueryResult result) {
            this.result = result;
        }

        void willReturnFound() {
            this.result = AssetQueryResult.found(new AssetView(ASSET_ID, "SERVER", "IN_SERVICE",
                    SourceOrigin.DEMO));
        }

        int calls() {
            return this.calls.get();
        }

        void reset() {
            this.calls.set(0);
            this.result = AssetQueryResult.notFound("AST-000000", SourceOrigin.DEMO);
        }
    }

    /** 监控快照查询端口替身：记录调用次数（本测试不经过真实 MCP 服务）。 */
    static final class CountingMonitoringPort implements MonitoringSnapshotQueryPort {

        private final AtomicInteger calls = new AtomicInteger();

        private MonitoringSnapshotQueryResult result =
                MonitoringSnapshotQueryResult.notFound("AST-000000", SourceOrigin.DEMO);

        @Override
        public MonitoringSnapshotQueryResult findLatestSnapshot(String assetId) {
            this.calls.incrementAndGet();
            return this.result;
        }

        void willReturn(MonitoringSnapshotQueryResult result) {
            this.result = result;
        }

        void willReturnFound() {
            this.result = MonitoringSnapshotQueryResult.found(new MonitoringSnapshotView(ASSET_ID,
                    Instant.parse("2026-01-01T00:00:00Z"), HealthState.DEGRADED, 92, 68, 1, SourceOrigin.DEMO));
        }

        int calls() {
            return this.calls.get();
        }

        void reset() {
            this.calls.set(0);
            this.result = MonitoringSnapshotQueryResult.notFound("AST-000000", SourceOrigin.DEMO);
        }
    }
}
