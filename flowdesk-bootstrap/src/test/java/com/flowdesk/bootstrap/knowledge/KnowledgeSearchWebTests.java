package com.flowdesk.bootstrap.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.application.knowledge.port.out.KnowledgeQueryEmbeddingPort;
import com.flowdesk.application.knowledge.port.out.KnowledgeVectorMatch;
import com.flowdesk.application.knowledge.port.out.KnowledgeVectorSearchPort;
import com.flowdesk.domain.knowledge.EmbeddingDescriptor;
import com.flowdesk.domain.knowledge.KnowledgeDocumentId;
import com.flowdesk.domain.knowledge.KnowledgeQueryEmbedding;
import com.flowdesk.domain.knowledge.Sha256Digest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 检索接口的 HTTP 契约测试（RAG 4/6）。
 *
 * <p>这里把<b>查询向量</b>与<b>向量检索</b>两个出站端口替换为替身，但保留真实的用例服务：
 * 因此「输入规范化与校验 → 开关判断 → 调用模型 → 领域校验 → 检索 → 结果契约校验 → 引用编号」
 * 这条链路是被真实执行过的，而 pgvector 的真实相似度由 Testcontainers 集成测试覆盖
 * （无 Docker 时跳过）。测试环境是 H2，没有 {@code <=>}，因此必须替换检索端口。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:flowdesk_knowledge_search_web_it"
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "flowdesk.knowledge.storage.root=target/knowledge-search-web-it",
        "flowdesk.knowledge.embedding.enabled=true",
        "flowdesk.knowledge.retrieval.default-top-k=5",
        "flowdesk.knowledge.retrieval.max-top-k=20",
        "flowdesk.knowledge.retrieval.default-min-score=0.30",
        // 启用向量化就必须有 DashScope Key（启动期校验），这里给一个假 Key；
        // 真正的模型调用被下面的 StubQueryEmbeddingPort 覆盖，因此不会发出任何网络请求。
        "spring.ai.dashscope.api-key=test-fake-key-not-a-real-secret",
        // 覆盖生产校验：本测试用 H2 且只测 HTTP 契约
        "spring.main.allow-bean-definition-overriding=true"
})
@AutoConfigureMockMvc
class KnowledgeSearchWebTests {

    private static final String SEARCH_PATH = "/api/v1/knowledge/search";

    private static final UUID DOCUMENT_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static final String DIGEST = "0123456789abcdef".repeat(4);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StubQueryEmbeddingPort queryPort;

    @Autowired
    private StubVectorSearchPort searchPort;

    @Autowired
    private com.flowdesk.application.knowledge.port.in.RetrieveKnowledgeUseCase retrieveUseCase;

    @BeforeEach
    void resetStubs() {
        this.queryPort.reset();
        this.searchPort.reset();
    }

    // ---------- 成功路径 ----------

    @Test
    void aSuccessfulSearchReturnsTheDocumentedJson() throws Exception {
        this.searchPort.willReturn(List.of(
                match(2, 0.873421, "VPN 故障处理手册", "第一步：检查隧道状态"),
                match(0, 0.541200, "VPN 故障处理手册", "第二步：确认账号状态")));

        MvcResult result = this.mockMvc.perform(post(SEARCH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"VPN 无法连接应该如何处理？\",\"topK\":5,\"minScore\":0.30}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.provider").value("dashscope"))
                .andExpect(jsonPath("$.model").value("text-embedding-v4"))
                .andExpect(jsonPath("$.dimensions").value(1024))
                .andExpect(jsonPath("$.topK").value(5))
                .andExpect(jsonPath("$.minScore").value(0.30))
                .andExpect(jsonPath("$.citations.length()").value(2))
                .andExpect(jsonPath("$.citations[0].citationId").value("K1"))
                .andExpect(jsonPath("$.citations[0].rank").value(1))
                .andExpect(jsonPath("$.citations[0].documentId").value(DOCUMENT_ID.toString()))
                .andExpect(jsonPath("$.citations[0].documentVersion").value(4))
                .andExpect(jsonPath("$.citations[0].documentTitle").value("VPN 故障处理手册"))
                .andExpect(jsonPath("$.citations[0].chunkIndex").value(2))
                .andExpect(jsonPath("$.citations[0].chunkSha256").value(DIGEST))
                .andExpect(jsonPath("$.citations[0].content").value("第一步：检查隧道状态"))
                .andExpect(jsonPath("$.citations[0].score").value(0.873421))
                .andExpect(jsonPath("$.citations[1].citationId").value("K2"))
                .andExpect(jsonPath("$.citations[1].rank").value(2))
                .andReturn();

        String body = body(result);
        assertThat(body)
                .as("响应不含 query、向量、SQL 或内部异常")
                .doesNotContain("VPN 无法连接应该如何处理")
                .doesNotContain("vector")
                .doesNotContain("[0.")
                .doesNotContain("SELECT")
                .doesNotContain("Exception")
                .doesNotContain("java.");
        assertThat(this.queryPort.queries()).containsExactly("VPN 无法连接应该如何处理？");
        assertThat(this.searchPort.topKs()).containsExactly(5);
        assertThat(this.searchPort.minScores()).containsExactly(0.30);
    }

    @Test
    void noMatchesReturns200WithAnEmptyCitationList() throws Exception {
        this.searchPort.willReturn(List.of());

        this.mockMvc.perform(post(SEARCH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"完全不相关的问题\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.citations").isArray())
                .andExpect(jsonPath("$.citations.length()").value(0))
                .andExpect(jsonPath("$.topK").value(5))
                .andExpect(jsonPath("$.minScore").value(0.30));
    }

    @Test
    void topKAndMinScoreCanBeOverriddenWithinTheConfiguredBounds() throws Exception {
        this.searchPort.willReturn(List.of(match(0, 0.9, "标题", "正文")));

        this.mockMvc.perform(post(SEARCH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"VPN\",\"topK\":1,\"minScore\":0.0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topK").value(1))
                .andExpect(jsonPath("$.minScore").value(0.0));

        assertThat(this.searchPort.topKs()).containsExactly(1);
        assertThat(this.searchPort.minScores()).containsExactly(0.0);
    }

    // ---------- 非法请求 ----------

    @Test
    void invalidFieldsReturn400WithTheFixedDetail() throws Exception {
        for (String payload : List.of(
                "{}",
                "{\"query\":\"\"}",
                "{\"query\":\"   \"}",
                "{\"query\":\"VPN\",\"topK\":0}",
                "{\"query\":\"VPN\",\"topK\":21}",
                "{\"query\":\"VPN\",\"minScore\":-0.01}",
                "{\"query\":\"VPN\",\"minScore\":1.01}",
                "{\"query\":\"VPN\\u0000\"}")) {

            MvcResult result = this.mockMvc.perform(post(SEARCH_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andReturn();
            String body = body(result);

            assertThat(result.getResponse().getStatus()).as("payload=%s", payload).isEqualTo(400);
            assertThat(result.getResponse().getContentType())
                    .as("payload=%s", payload)
                    .contains(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            assertThat(body).as("payload=%s", payload)
                    .contains("\"code\":\"INVALID_REQUEST\"")
                    .contains("\"detail\":\"检索请求不合法\"")
                    .contains("urn:flowdesk:problem:invalid-request");
        }

        assertThat(this.queryPort.calls()).as("非法请求不得调用模型").isZero();
        assertThat(this.searchPort.calls()).as("非法请求不得访问向量表").isZero();
    }

    @Test
    void anEmptyBodyReturns400WithTheRetrievalContract() throws Exception {
        // FD-0011-R1：空请求体不再走「缺少请求体」的通用错误路径，
        // 而是与「缺 query」完全同一条契约：400 + INVALID_REQUEST + 固定 detail
        MvcResult result = this.mockMvc.perform(post(SEARCH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(""))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(result))
                .contains("\"code\":\"INVALID_REQUEST\"")
                .contains("\"detail\":\"检索请求不合法\"")
                .doesNotContain("缺少请求体");

        assertThat(this.queryPort.calls()).as("空请求体不得调用 Query Embedding").isZero();
        assertThat(this.searchPort.calls()).as("空请求体不得访问向量检索端口").isZero();
    }

    @Test
    void aMalformedJsonBodyReturns400WithTheGlobalJsonContract() throws Exception {
        // 坏 JSON 与「只有空白的 body」都保留全局契约：无法解析 → 「请求体不是合法 JSON」
        for (String malformed : List.of("{\"query\":", "   ", "not-json")) {
            MvcResult result = this.mockMvc.perform(post(SEARCH_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(malformed))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.detail").value("请求体不是合法 JSON"))
                    .andReturn();

            assertThat(body(result)).as("body=[%s]", malformed).doesNotContain("not-json");
        }

        assertThat(this.queryPort.calls()).isZero();
        assertThat(this.searchPort.calls()).isZero();
    }

    @Test
    void anUnsupportedContentTypeReturns415AndAnUnsatisfiableAcceptReturns406() throws Exception {
        this.mockMvc.perform(post(SEARCH_PATH)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("{\"query\":\"VPN\"}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));

        this.mockMvc.perform(post(SEARCH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_XML)
                        .content("{\"query\":\"VPN\"}"))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"));
    }

    // ---------- 上游与内部失败 ----------

    @Test
    void aProviderFailureReturns502WithoutLeakingTheQuery() throws Exception {
        this.queryPort.failWith(new KnowledgeApplicationException(
                KnowledgeApplicationErrorCode.EMBEDDING_PROVIDER_ERROR, "查询向量服务调用失败"));

        MvcResult result = this.mockMvc.perform(post(SEARCH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"SENTINEL-VPN-QUERY\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("EMBEDDING_PROVIDER_ERROR"))
                .andExpect(jsonPath("$.type").value("urn:flowdesk:problem:embedding-provider-error"))
                .andReturn();

        assertThat(body(result)).doesNotContain("SENTINEL-VPN-QUERY").doesNotContain("Exception");
        assertThat(this.searchPort.calls()).isZero();
    }

    @Test
    void anInvalidQueryVectorReturns500() throws Exception {
        this.queryPort.willReturn(new float[] { 1.0f, 0.0f, 0.0f });

        this.mockMvc.perform(post(SEARCH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"VPN\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"));

        assertThat(this.searchPort.calls()).isZero();
    }

    @Test
    void aSearchFailureReturns500WithoutLeakingInternals() throws Exception {
        this.searchPort.failWith(new KnowledgeApplicationException(
                KnowledgeApplicationErrorCode.KNOWLEDGE_RETRIEVAL_FAILURE, "向量检索失败", new RuntimeException(
                        "SELECT ... FROM knowledge_document_chunk_embeddings jdbc:postgresql://db/flowdesk")));

        MvcResult result = this.mockMvc.perform(post(SEARCH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"VPN\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andReturn();

        assertThat(body(result))
                .doesNotContain("SELECT")
                .doesNotContain("jdbc:")
                .doesNotContain("knowledge_document")
                .doesNotContain("Exception");
    }

    @Test
    void aBrokenResultContractReturns500InsteadOfBeingTrimmedOrSorted() throws Exception {
        // 端口返回两条而 topK=1：不得截断，必须失败
        this.searchPort.willReturn(List.of(
                match(0, 0.9, "标题", "正文"),
                match(1, 0.8, "标题", "正文")));

        this.mockMvc.perform(post(SEARCH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"VPN\",\"topK\":1}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"));
    }

    @Test
    void aDomainExceptionFromTheSearchPortReturns500Not400() throws Exception {
        // FD-0011-R1：行映射阶段的领域异常（库里的摘要不合法等）属于服务端数据问题，
        // 绝不能落进「领域异常 → 400」的全局映射，把服务端问题说成调用方输入错误
        this.searchPort.failWith(new com.flowdesk.domain.knowledge.KnowledgeDomainException(
                com.flowdesk.domain.knowledge.KnowledgeErrorCode.INVALID_VECTOR, "摘要不合法 sentinel-digest"));

        MvcResult result = this.mockMvc.perform(post(SEARCH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"VPN\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andReturn();

        assertThat(body(result))
                .doesNotContain("sentinel-digest")
                .doesNotContain("INVALID_VECTOR")
                .doesNotContain("Exception");
    }

    @Test
    void anUnexpectedRuntimeExceptionFromTheSearchPortReturns500() throws Exception {
        this.searchPort.failWith(new IllegalStateException("结果集已关闭 sentinel-driver"));

        MvcResult result = this.mockMvc.perform(post(SEARCH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"VPN\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andReturn();

        assertThat(body(result)).doesNotContain("sentinel-driver").doesNotContain("Exception");
    }

    @Test
    void anOutOfContractErrorCodeFromTheSearchPortReturns500Not400() throws Exception {
        // FD-0011-R2：检索端口抛出契约之外的错误码（这里是 INVALID_RETRIEVAL_QUERY）时，
        // 不能被当成「调用方输入不合法」——那会把内部故障说成 400
        String sentinel = "SENTINEL-PORT-DETAIL-绝不外泄";
        this.searchPort.failWith(new KnowledgeApplicationException(
                KnowledgeApplicationErrorCode.INVALID_RETRIEVAL_QUERY, sentinel));

        MvcResult result = this.mockMvc.perform(post(SEARCH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"VPN\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("端口违约不得表现为 400").isNotEqualTo(400);
        assertThat(body(result))
                .doesNotContain(sentinel)
                .doesNotContain("INVALID_RETRIEVAL_QUERY")
                .doesNotContain("Exception");

        // 应用层的分类也必须是 KNOWLEDGE_RETRIEVAL_FAILURE（HTTP 500 的全部 500 都走这一条契约）
        assertThatThrownBy(() -> this.retrieveUseCase.retrieve(
                new com.flowdesk.application.knowledge.query.RetrieveKnowledgeQuery("VPN", null, null)))
                .isInstanceOf(KnowledgeApplicationException.class)
                .extracting(thrown -> ((KnowledgeApplicationException) thrown).errorCode())
                .isEqualTo(KnowledgeApplicationErrorCode.KNOWLEDGE_RETRIEVAL_FAILURE);
    }

    // ---------- 辅助 ----------

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static KnowledgeVectorMatch match(int chunkIndex, double score, String title, String content) {
        return new KnowledgeVectorMatch(KnowledgeDocumentId.of(DOCUMENT_ID), 4L, title, chunkIndex,
                Sha256Digest.of(DIGEST), content, score);
    }

    /**
     * 用替身替换两个出站端口，并放行「启用向量化」的启动期校验（测试环境是 H2）。
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
    }

    /**
     * 查询向量替身：记录 query，可注入上游失败或畸形向量。
     */
    static final class StubQueryEmbeddingPort implements KnowledgeQueryEmbeddingPort {

        private final List<String> queries = new ArrayList<>();

        private RuntimeException failure;

        private float[] vector = defaultVector();

        @Override
        public float[] embedQuery(String query, EmbeddingDescriptor descriptor) {
            this.queries.add(query);
            if (this.failure != null) {
                throw this.failure;
            }
            return this.vector.clone();
        }

        void failWith(RuntimeException failure) {
            this.failure = failure;
        }

        void willReturn(float[] vector) {
            this.vector = vector.clone();
        }

        List<String> queries() {
            return List.copyOf(this.queries);
        }

        int calls() {
            return this.queries.size();
        }

        void reset() {
            this.queries.clear();
            this.failure = null;
            this.vector = defaultVector();
        }

        private static float[] defaultVector() {
            float[] vector = new float[EmbeddingDescriptor.REQUIRED_DIMENSIONS];
            Arrays.fill(vector, 0.5f);
            return vector;
        }
    }

    /**
     * 向量检索替身：记录 topK 与阈值，可注入失败或返回违约结果。
     */
    static final class StubVectorSearchPort implements KnowledgeVectorSearchPort {

        private final List<Integer> topKs = new ArrayList<>();

        private final List<Double> minScores = new ArrayList<>();

        private List<KnowledgeVectorMatch> matches = List.of();

        private RuntimeException failure;

        @Override
        public List<KnowledgeVectorMatch> search(KnowledgeQueryEmbedding queryEmbedding, double minScore,
                int topK) {

            this.topKs.add(topK);
            this.minScores.add(minScore);
            if (this.failure != null) {
                throw this.failure;
            }
            return this.matches;
        }

        void willReturn(List<KnowledgeVectorMatch> matches) {
            this.matches = List.copyOf(matches);
        }

        void failWith(RuntimeException failure) {
            this.failure = failure;
        }

        List<Integer> topKs() {
            return List.copyOf(this.topKs);
        }

        List<Double> minScores() {
            return List.copyOf(this.minScores);
        }

        int calls() {
            return this.topKs.size();
        }

        void reset() {
            this.topKs.clear();
            this.minScores.clear();
            this.matches = List.of();
            this.failure = null;
        }
    }
}
