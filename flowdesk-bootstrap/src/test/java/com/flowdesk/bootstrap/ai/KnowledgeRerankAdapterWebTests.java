package com.flowdesk.bootstrap.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flowdesk.application.knowledge.port.out.KnowledgeQueryEmbeddingPort;
import com.flowdesk.application.knowledge.port.out.KnowledgeVectorMatch;
import com.flowdesk.application.knowledge.port.out.KnowledgeVectorSearchPort;
import com.flowdesk.domain.knowledge.EmbeddingDescriptor;
import com.flowdesk.domain.knowledge.KnowledgeDocumentId;
import com.flowdesk.domain.knowledge.Sha256Digest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * 真实重排适配器的端到端失败语义测试（FD-0013-R1）。
 *
 * <p>与本轮其它测试不同，这里<b>不替换重排端口</b>：装配的是真实的
 * {@code DashScopeKnowledgeRerankAdapter}，它对着本机回环的
 * {@link SyntheticRerankEndpoint} 说话。因此下面这些断言覆盖的是完整链路：
 * 「供应商返回非法下标 → 适配器拒绝 → 用例失败 → HTTP 500 → 不返回任何引用 →
 * <b>也没有调用 DeepSeek</b>」。</p>
 *
 * <p>回环明文 HTTP 是 {@code RerankEndpointPolicy} 唯一放行的非 HTTPS 场景；
 * 本类的正常路径同时也是那条边界的正向验证。</p>
 */
@SpringBootTest(properties = {
        "spring.profiles.active=deepseek",
        "DEEPSEEK_API_KEY=test-fake-key-not-a-real-secret",
        "spring.datasource.url=jdbc:h2:mem:flowdesk_rerank_adapter_web_it"
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "flowdesk.knowledge.storage.root=target/knowledge-rerank-adapter-web-it",
        "flowdesk.knowledge.embedding.enabled=true",
        "flowdesk.knowledge.retrieval.default-top-k=5",
        "flowdesk.knowledge.retrieval.max-top-k=20",
        "flowdesk.knowledge.retrieval.default-min-score=0.30",
        "spring.ai.dashscope.api-key=test-fake-key-not-a-real-secret",
        "flowdesk.knowledge.rerank.enabled=true",
        "flowdesk.knowledge.rerank.model=qwen3-rerank",
        "spring.main.allow-bean-definition-overriding=true"
})
@AutoConfigureMockMvc
class KnowledgeRerankAdapterWebTests {

    private static final String SEARCH_PATH = "/api/v1/knowledge/search";

    private static final String ANSWER_PATH = "/api/v1/ai/knowledge-answer";

    private static final String QUESTION = "VPN 无法连接应该如何处理？";

    private static final UUID DOCUMENT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID DOCUMENT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID DOCUMENT_C = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final String DIGEST = "0123456789abcdef".repeat(4);

    private static SyntheticOpenAiEndpoint chatEndpoint;

    private static SyntheticRerankEndpoint rerankEndpoint;

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void pointAtSyntheticEndpoints(DynamicPropertyRegistry registry) throws IOException {
        if (chatEndpoint == null) {
            chatEndpoint = SyntheticOpenAiEndpoint.start(SyntheticOpenAiEndpoint.Behaviour.STATIC_ANSWER);
        }
        if (rerankEndpoint == null) {
            rerankEndpoint = SyntheticRerankEndpoint.start();
        }
        registry.add("spring.ai.openai.base-url", chatEndpoint::baseUrl);
        // 本机回环明文 HTTP：策略允许的唯一非 HTTPS 场景
        registry.add("flowdesk.knowledge.rerank.endpoint", () -> rerankEndpoint.uri().toString());
    }

    @AfterAll
    static void stopEndpoints() {
        if (chatEndpoint != null) {
            chatEndpoint.stop();
        }
        if (rerankEndpoint != null) {
            rerankEndpoint.stop();
        }
    }

    @BeforeEach
    void reset() {
        chatEndpoint.clearRequests();
        chatEndpoint.willAnswer("结论依据重排后的第一条 [K1]。");
        rerankEndpoint.clearRequests();
        // 默认：合法但乱序的整数下标（下标 2 分数最高 → K1 指向 C）
        rerankEndpoint.willReturn("{\"object\":\"list\",\"results\":["
                + "{\"index\":2,\"relevance_score\":0.95},"
                + "{\"index\":0,\"relevance_score\":0.40},"
                + "{\"index\":1,\"relevance_score\":0.10}],\"model\":\"qwen3-rerank\"}");
    }

    // ---------- 正向：真实适配器 + 真实链路 ----------

    @Test
    void theRealAdapterBindsIntegerIndicesEndToEnd() throws Exception {
        this.mockMvc.perform(post(SEARCH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"" + QUESTION + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rankingMode").value("RERANK"))
                .andExpect(jsonPath("$.rerankModel").value("qwen3-rerank"))
                .andExpect(jsonPath("$.citations[0].documentId").value(DOCUMENT_C.toString()))
                .andExpect(jsonPath("$.citations[0].rerankScore").value(0.95))
                .andExpect(jsonPath("$.citations[1].documentId").value(DOCUMENT_A.toString()))
                .andExpect(jsonPath("$.citations[2].documentId").value(DOCUMENT_B.toString()));

        assertThat(rerankEndpoint.calls()).as("一次检索只调用一次重排").isEqualTo(1);
        assertThat(rerankEndpoint.authorizationHeaders())
                .as("凭证按 Bearer 发送（这里是假 Key）")
                .containsExactly("Bearer test-fake-key-not-a-real-secret");
    }

    // ---------- 反向：非法下标（小数 / 指数 / 越界）整次失败 ----------

    @Test
    void aFractionalOrExponentialIndexFailsTheWholeRequestWithoutCitationsOrDeepSeek() throws Exception {
        for (String illegalIndex : List.of("0.9", "1.8", "1e0")) {
            chatEndpoint.clearRequests();
            rerankEndpoint.willReturn("{\"results\":[{\"index\":" + illegalIndex + ",\"relevance_score\":0.95},"
                    + "{\"index\":1,\"relevance_score\":0.40},"
                    + "{\"index\":2,\"relevance_score\":0.10}]}");

            MvcResult result = this.mockMvc.perform(post(ANSWER_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"query\":\"" + QUESTION + "\"}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                    // 逐字段断言：失败响应里没有答案、没有引用（不能用整串子串判断，
                    // instance 里就含 "knowledge-answer"）
                    .andExpect(jsonPath("$.answer").doesNotExist())
                    .andExpect(jsonPath("$.citations").doesNotExist())
                    .andExpect(jsonPath("$.usedCitationIds").doesNotExist())
                    .andReturn();

            assertThat(body(result)).as("index=%s", illegalIndex)
                    .doesNotContain(QUESTION)
                    .doesNotContain("Exception");
            assertThat(chatEndpoint.requests())
                    .as("index=%s：重排失败时绝不能继续调用 DeepSeek", illegalIndex)
                    .isEmpty();
        }

        // 检索接口同样整次失败，不返回任何引用
        this.mockMvc.perform(post(SEARCH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"" + QUESTION + "\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.citations").doesNotExist());
    }

    @Test
    void anIndexOutsideTheIntRangeFailsTheWholeRequestWithoutCitationsOrDeepSeek() throws Exception {
        for (String illegalIndex : List.of("4294967296", "-4294967296", "2147483648")) {
            chatEndpoint.clearRequests();
            rerankEndpoint.willReturn("{\"results\":[{\"index\":" + illegalIndex + ",\"relevance_score\":0.95},"
                    + "{\"index\":1,\"relevance_score\":0.40},"
                    + "{\"index\":2,\"relevance_score\":0.10}]}");

            MvcResult result = this.mockMvc.perform(post(ANSWER_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"query\":\"" + QUESTION + "\"}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                    .andExpect(jsonPath("$.answer").doesNotExist())
                    .andExpect(jsonPath("$.citations").doesNotExist())
                    .andReturn();

            assertThat(body(result)).as("index=%s", illegalIndex).doesNotContain(QUESTION);
            assertThat(chatEndpoint.requests())
                    .as("index=%s：不得溢出成合法下标后继续作答", illegalIndex)
                    .isEmpty();
        }
    }

    @Test
    void aMissingIndexFailsThroughTheApplicationContractWithoutCallingDeepSeek() throws Exception {
        // 缺字段仍按既有应用层契约拒绝（适配器把它表示为 null，由用例判违约）
        chatEndpoint.clearRequests();
        rerankEndpoint.willReturn("{\"results\":[{\"relevance_score\":0.95},"
                + "{\"index\":1,\"relevance_score\":0.40},"
                + "{\"index\":2,\"relevance_score\":0.10}]}");

        this.mockMvc.perform(post(ANSWER_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"" + QUESTION + "\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.citations").doesNotExist())
                .andExpect(jsonPath("$.answer").doesNotExist());

        assertThat(chatEndpoint.requests()).isEmpty();
    }

    // ---------- 辅助 ----------

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static KnowledgeVectorMatch match(UUID documentId, int chunkIndex, double score, String content) {
        return new KnowledgeVectorMatch(KnowledgeDocumentId.of(documentId), 4L, "VPN 故障处理手册", chunkIndex,
                Sha256Digest.of(DIGEST), content, score);
    }

    /**
     * 只替换向量相关的出站端口：<b>重排端口保持真实适配器</b>。
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
        KnowledgeQueryEmbeddingPort stubQueryEmbeddingPort() {
            return (query, descriptor) -> {
                float[] vector = new float[EmbeddingDescriptor.REQUIRED_DIMENSIONS];
                Arrays.fill(vector, 0.5f);
                return vector;
            };
        }

        @Bean
        @Primary
        KnowledgeVectorSearchPort stubVectorSearchPort() {
            return (queryEmbedding, minScore, topK) -> List.of(
                    match(DOCUMENT_A, 0, 0.90, "chunk-a"),
                    match(DOCUMENT_B, 1, 0.80, "chunk-b"),
                    match(DOCUMENT_C, 2, 0.70, "chunk-c"));
        }
    }
}
