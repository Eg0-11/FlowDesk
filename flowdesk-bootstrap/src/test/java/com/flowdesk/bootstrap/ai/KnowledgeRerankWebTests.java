package com.flowdesk.bootstrap.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.application.knowledge.port.out.KnowledgeQueryEmbeddingPort;
import com.flowdesk.application.knowledge.port.out.KnowledgeRerankPort;
import com.flowdesk.application.knowledge.port.out.KnowledgeRerankResult;
import com.flowdesk.application.knowledge.port.out.KnowledgeVectorMatch;
import com.flowdesk.application.knowledge.port.out.KnowledgeVectorSearchPort;
import com.flowdesk.domain.knowledge.EmbeddingDescriptor;
import com.flowdesk.domain.knowledge.KnowledgeDocumentId;
import com.flowdesk.domain.knowledge.KnowledgeQueryEmbedding;
import com.flowdesk.domain.knowledge.Sha256Digest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
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
 * 启用重排时的端到端契约测试（RAG 6/6）。
 *
 * <p>本类同时覆盖<b>两个</b> HTTP 入口，因为本阶段最重要的一条要求是：
 * 检索接口与问答接口必须使用<b>同一份最终排序的证据</b>。它们共用同一个检索用例，
 * 因此「同一个问题、同一批候选」在两个入口上必须给出同样的顺序、同样的编号、
 * 同样的重排分与同样的排序模式。</p>
 *
 * <p>模型端仍是本地合成端点（不是 DeepSeek）；重排端口是手写替身。
 * 真实协议由 infrastructure 的适配器测试用本机合成 HTTP 端点覆盖。</p>
 */
@SpringBootTest(properties = {
        "spring.profiles.active=deepseek",
        "DEEPSEEK_API_KEY=test-fake-key-not-a-real-secret",
        "spring.datasource.url=jdbc:h2:mem:flowdesk_knowledge_rerank_web_it"
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "flowdesk.knowledge.storage.root=target/knowledge-rerank-web-it",
        "flowdesk.knowledge.embedding.enabled=true",
        "flowdesk.knowledge.retrieval.default-top-k=5",
        "flowdesk.knowledge.retrieval.max-top-k=20",
        "flowdesk.knowledge.retrieval.default-min-score=0.30",
        "spring.ai.dashscope.api-key=test-fake-key-not-a-real-secret",
        // 重排开启：Endpoint 必须是显式配置的合法地址（这里指向本机未监听端口，
        // 真正的调用被下面的 StubRerankPort 覆盖，所以不会发出任何网络请求）
        "flowdesk.knowledge.rerank.enabled=true",
        "flowdesk.knowledge.rerank.model=qwen3-rerank",
        "flowdesk.knowledge.rerank.endpoint=http://127.0.0.1:1/compatible-api/v1/reranks",
        "spring.main.allow-bean-definition-overriding=true"
})
@AutoConfigureMockMvc
class KnowledgeRerankWebTests {

    private static final String SEARCH_PATH = "/api/v1/knowledge/search";

    private static final String ANSWER_PATH = "/api/v1/ai/knowledge-answer";

    private static final String QUESTION = "VPN 无法连接应该如何处理？";

    private static final UUID DOCUMENT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID DOCUMENT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID DOCUMENT_C = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final String DIGEST = "0123456789abcdef".repeat(4);

    private static SyntheticOpenAiEndpoint endpoint;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StubQueryEmbeddingPort queryPort;

    @Autowired
    private StubVectorSearchPort searchPort;

    @Autowired
    private StubRerankPort rerankPort;

    @DynamicPropertySource
    static void pointAtSyntheticEndpoint(DynamicPropertyRegistry registry) throws IOException {
        if (endpoint == null) {
            endpoint = SyntheticOpenAiEndpoint.start(SyntheticOpenAiEndpoint.Behaviour.STATIC_ANSWER);
        }
        registry.add("spring.ai.openai.base-url", endpoint::baseUrl);
    }

    @AfterAll
    static void stopEndpoint() {
        if (endpoint != null) {
            endpoint.stop();
        }
    }

    @BeforeEach
    void reset() {
        this.queryPort.reset();
        this.searchPort.reset();
        this.rerankPort.reset();
        endpoint.clearRequests();
        endpoint.willAnswer("结论依据重排后的第一条 [K1]。");

        // 向量顺序 A、B、C；重排分让顺序变成 C、A、B
        this.searchPort.willReturn(List.of(
                match(DOCUMENT_A, 0, 0.90, "chunk-a"),
                match(DOCUMENT_B, 1, 0.80, "chunk-b"),
                match(DOCUMENT_C, 2, 0.70, "chunk-c")));
        this.rerankPort.willReturnScores(0.40, 0.10, 0.95);
    }

    // ---------- 两个入口共用同一份最终排序 ----------

    @Test
    void theSearchEndpointReturnsTheRerankedOrderWithAuditFields() throws Exception {
        MvcResult result = this.mockMvc.perform(post(SEARCH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"" + QUESTION + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rankingMode").value("RERANK"))
                .andExpect(jsonPath("$.rerankModel").value("qwen3-rerank"))
                // K1 指向 C、K2 指向 A、K3 指向 B
                .andExpect(jsonPath("$.citations[0].citationId").value("K1"))
                .andExpect(jsonPath("$.citations[0].documentId").value(DOCUMENT_C.toString()))
                .andExpect(jsonPath("$.citations[0].rank").value(1))
                .andExpect(jsonPath("$.citations[0].rerankScore").value(0.95))
                .andExpect(jsonPath("$.citations[1].citationId").value("K2"))
                .andExpect(jsonPath("$.citations[1].documentId").value(DOCUMENT_A.toString()))
                .andExpect(jsonPath("$.citations[1].rerankScore").value(0.40))
                .andExpect(jsonPath("$.citations[2].citationId").value("K3"))
                .andExpect(jsonPath("$.citations[2].documentId").value(DOCUMENT_B.toString()))
                .andExpect(jsonPath("$.citations[2].rerankScore").value(0.10))
                // 向量分保留原值，不被重排分覆盖
                .andExpect(jsonPath("$.citations[0].score").value(0.70))
                .andExpect(jsonPath("$.citations[1].score").value(0.90))
                .andExpect(jsonPath("$.citations[2].score").value(0.80))
                .andReturn();

        assertThat(body(result))
                .as("响应不回显 query，也不含向量或内部标识")
                .doesNotContain(QUESTION)
                .doesNotContain("[0.")
                .doesNotContain("SELECT")
                .doesNotContain("Exception");
    }

    @Test
    void theAnswerEndpointUsesExactlyTheSameFinalEvidenceAsSearch() throws Exception {
        JsonNode search = bodyJson(this.mockMvc.perform(post(SEARCH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"" + QUESTION + "\"}"))
                .andExpect(status().isOk())
                .andReturn());

        JsonNode answer = bodyJson(this.mockMvc.perform(post(ANSWER_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"" + QUESTION + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounded").value(true))
                .andExpect(jsonPath("$.usedCitationIds[0]").value("K1"))
                .andExpect(jsonPath("$.rankingMode").value("RERANK"))
                .andExpect(jsonPath("$.rerankModel").value("qwen3-rerank"))
                .andReturn());

        // 顺序、编号、重排分、向量分、排序模式在两个入口上完全一致
        assertThat(citationIds(answer)).containsExactlyElementsOf(citationIds(search));
        assertThat(citationIds(answer)).containsExactly("K1", "K2", "K3");
        assertThat(documentIds(answer)).containsExactlyElementsOf(documentIds(search));
        assertThat(documentIds(answer)).containsExactly(
                DOCUMENT_C.toString(), DOCUMENT_A.toString(), DOCUMENT_B.toString());
        assertThat(fieldValues(answer, "rerankScore")).containsExactlyElementsOf(fieldValues(search, "rerankScore"));
        assertThat(fieldValues(answer, "score")).containsExactlyElementsOf(fieldValues(search, "score"));

        // 模型看到的证据顺序与 allowedCitationIds 也是这一份（同一份快照）
        String userPrompt = userMessage(endpoint.requests().get(0).body());
        assertThat(userPrompt)
                .contains("\"allowedCitationIds\":[\"K1\",\"K2\",\"K3\"]")
                .contains("\"citationId\":\"K1\"")
                .contains("\"content\":\"chunk-c\"")
                .contains("\"citationId\":\"K2\"")
                .contains("\"content\":\"chunk-a\"");
        assertThat(userPrompt.indexOf("chunk-c")).isLessThan(userPrompt.indexOf("chunk-a"));
        assertThat(userPrompt.indexOf("chunk-a")).isLessThan(userPrompt.indexOf("chunk-b"));
    }

    @Test
    void theRerankPortReceivesOnlyTheNormalizedQueryAndCandidateContents() throws Exception {
        this.mockMvc.perform(post(SEARCH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"  " + QUESTION + "  \"}"))
                .andExpect(status().isOk());

        assertThat(this.rerankPort.calls()).as("一次检索只重排一次").isEqualTo(1);
        assertThat(this.rerankPort.queries())
                .as("重排拿到的问题与查询向量端口逐字符相同")
                .containsExactly(QUESTION);
        assertThat(this.queryPort.queries()).containsExactly(QUESTION);
        assertThat(this.rerankPort.documents())
                .as("只发送候选正文，顺序即向量顺序")
                .containsExactly(List.of("chunk-a", "chunk-b", "chunk-c"));
    }

    @Test
    void thePromptAndTheAnswerKeepTheVectorScoreAndTheRerankScoreApart() throws Exception {
        this.mockMvc.perform(post(ANSWER_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"" + QUESTION + "\"}"))
                .andExpect(status().isOk());

        String prompt = userMessage(endpoint.requests().get(0).body());
        assertThat(prompt)
                .as("重排分不进提示词：模型只需要正文与允许的编号")
                .doesNotContain("rerankScore")
                .doesNotContain("0.95");
    }

    // ---------- 无命中 / 单候选：不产生上游调用 ----------

    @Test
    void noMatchesCallNeitherRerankNorTheModel() throws Exception {
        this.searchPort.willReturn(List.of());

        this.mockMvc.perform(post(ANSWER_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"" + QUESTION + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounded").value(false))
                .andExpect(jsonPath("$.rankingMode").value("VECTOR_SIMILARITY"))
                .andExpect(jsonPath("$.citations.length()").value(0));

        assertThat(this.rerankPort.calls()).as("无命中时不调用重排").isZero();
        assertThat(endpoint.requests()).as("无命中时不调用模型").isEmpty();
    }

    @Test
    void aSingleCandidateKeepsTheVectorOrderWithoutCallingRerank() throws Exception {
        this.searchPort.willReturn(List.of(match(DOCUMENT_A, 0, 0.90, "chunk-a")));

        this.mockMvc.perform(post(SEARCH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"" + QUESTION + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rankingMode").value("VECTOR_SIMILARITY"))
                .andExpect(jsonPath("$.rerankModel").doesNotExist())
                .andExpect(jsonPath("$.citations[0].rerankScore").doesNotExist())
                .andExpect(jsonPath("$.citations[0].score").value(0.90));

        assertThat(this.rerankPort.calls()).as("只有一个候选时不做无意义的付费调用").isZero();
    }

    // ---------- 失败：稳定错误码、不降级、不泄漏 ----------

    @Test
    void aRerankProviderFailureReturns502WithoutFallingBackToVectorOrder() throws Exception {
        this.rerankPort.failWith(new KnowledgeApplicationException(
                KnowledgeApplicationErrorCode.RERANK_PROVIDER_ERROR,
                "重排服务调用失败 SENTINEL-RERANK-UPSTREAM"));

        MvcResult result = this.mockMvc.perform(post(SEARCH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"" + QUESTION + "\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RERANK_PROVIDER_ERROR"))
                .andExpect(jsonPath("$.type").value("urn:flowdesk:problem:rerank-provider-error"))
                .andExpect(jsonPath("$.title").value("重排服务不可用"))
                .andExpect(jsonPath("$.detail").value("重排服务暂时不可用，请稍后重试"))
                .andExpect(jsonPath("$.instance").value(SEARCH_PATH))
                .andReturn();

        assertThat(body(result))
                .as("失败响应里不得出现 query、候选正文或上游原文；也不得返回任何引用")
                .doesNotContain(QUESTION)
                .doesNotContain("chunk-a")
                .doesNotContain("SENTINEL-RERANK-UPSTREAM")
                .doesNotContain("citations")
                .doesNotContain("Exception");
    }

    @Test
    void theAnswerEndpointAlsoReportsTheRerankProviderFailure() throws Exception {
        this.rerankPort.failWith(new KnowledgeApplicationException(
                KnowledgeApplicationErrorCode.RERANK_PROVIDER_ERROR, "重排服务调用失败"));

        this.mockMvc.perform(post(ANSWER_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"" + QUESTION + "\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("RERANK_PROVIDER_ERROR"))
                .andExpect(jsonPath("$.detail").value("重排服务暂时不可用，请稍后重试"));

        assertThat(endpoint.requests()).as("重排失败时不得继续调用模型").isEmpty();
    }

    @Test
    void anInvalidRerankResponseReturns500AndNeverFallsBack() throws Exception {
        // 重复下标：一个候选被打了两次分，另一个没有
        this.rerankPort.willReturn(Arrays.asList(
                new KnowledgeRerankResult(0, 0.4),
                new KnowledgeRerankResult(0, 0.9),
                new KnowledgeRerankResult(1, 0.1)));

        MvcResult result = this.mockMvc.perform(post(SEARCH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"" + QUESTION + "\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andReturn();

        assertThat(body(result)).doesNotContain("citations").doesNotContain("Exception");
    }

    // ---------- 辅助 ----------

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private JsonNode bodyJson(MvcResult result) throws Exception {
        return this.objectMapper.readTree(body(result));
    }

    private static List<String> citationIds(JsonNode root) {
        return fieldValues(root, "citationId");
    }

    private static List<String> documentIds(JsonNode root) {
        return fieldValues(root, "documentId");
    }

    private static List<String> fieldValues(JsonNode root, String field) {
        List<String> values = new ArrayList<>();
        root.path("citations").forEach(citation -> values.add(citation.path(field).asText()));
        return values;
    }

    private static String userMessage(String requestBody) throws Exception {
        for (JsonNode message : new ObjectMapper().readTree(requestBody).path("messages")) {
            if ("user".equals(message.path("role").asText())) {
                return message.path("content").asText();
            }
        }
        throw new AssertionError("模型请求里没有 user 消息");
    }

    private static KnowledgeVectorMatch match(UUID documentId, int chunkIndex, double score, String content) {
        return new KnowledgeVectorMatch(KnowledgeDocumentId.of(documentId), 4L, "VPN 故障处理手册", chunkIndex,
                Sha256Digest.of(DIGEST), content, score);
    }

    /**
     * 用替身替换三个出站端口，并放行启动期校验（测试环境是 H2）。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class StubPortsConfiguration {

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
        Boolean knowledgeRerankConsistency() {
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
        StubRerankPort stubRerankPort() {
            return new StubRerankPort();
        }
    }

    /** 查询向量替身。 */
    static final class StubQueryEmbeddingPort implements KnowledgeQueryEmbeddingPort {

        private final List<String> queries = new ArrayList<>();

        @Override
        public float[] embedQuery(String query, EmbeddingDescriptor descriptor) {
            this.queries.add(query);
            float[] vector = new float[EmbeddingDescriptor.REQUIRED_DIMENSIONS];
            Arrays.fill(vector, 0.5f);
            return vector;
        }

        List<String> queries() {
            return List.copyOf(this.queries);
        }

        void reset() {
            this.queries.clear();
        }
    }

    /** 向量检索替身。 */
    static final class StubVectorSearchPort implements KnowledgeVectorSearchPort {

        private List<KnowledgeVectorMatch> matches = List.of();

        @Override
        public List<KnowledgeVectorMatch> search(KnowledgeQueryEmbedding queryEmbedding, double minScore, int topK) {
            return this.matches;
        }

        void willReturn(List<KnowledgeVectorMatch> matches) {
            this.matches = List.copyOf(matches);
        }

        void reset() {
            this.matches = List.of();
        }
    }

    /**
     * 重排替身：记录收到的 query 与候选正文，可返回预置结果或注入失败。
     */
    static final class StubRerankPort implements KnowledgeRerankPort {

        private final List<String> queries = new ArrayList<>();

        private final List<List<String>> documents = new ArrayList<>();

        private List<KnowledgeRerankResult> results = List.of();

        private RuntimeException failure;

        @Override
        public List<KnowledgeRerankResult> rerank(String normalizedQuery, List<String> candidateContents) {
            this.queries.add(normalizedQuery);
            this.documents.add(List.copyOf(candidateContents));
            if (this.failure != null) {
                throw this.failure;
            }
            return this.results;
        }

        void willReturnScores(double... scores) {
            List<KnowledgeRerankResult> next = new ArrayList<>(scores.length);
            for (int index = 0; index < scores.length; index++) {
                next.add(new KnowledgeRerankResult(index, scores[index]));
            }
            this.results = next;
        }

        void willReturn(List<KnowledgeRerankResult> results) {
            this.results = List.copyOf(results);
        }

        void failWith(RuntimeException failure) {
            this.failure = failure;
        }

        List<String> queries() {
            return List.copyOf(this.queries);
        }

        List<List<String>> documents() {
            return List.copyOf(this.documents);
        }

        int calls() {
            return this.queries.size();
        }

        void reset() {
            this.queries.clear();
            this.documents.clear();
            this.results = List.of();
            this.failure = null;
        }
    }
}
