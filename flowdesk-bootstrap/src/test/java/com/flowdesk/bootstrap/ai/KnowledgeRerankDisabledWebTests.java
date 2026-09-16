package com.flowdesk.bootstrap.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * 关闭重排时的行为契约测试（RAG 6/6）。
 *
 * <p>本阶段最容易被破坏的东西是「默认状态不受影响」。因此这里显式验证：
 * 重排端口<b>存在但一次都没有被调用</b>；响应里的排序模式是
 * {@code VECTOR_SIMILARITY}；并且 {@code rerankModel} / {@code rerankScore}
 * <b>根本不出现在 JSON 里</b>（NON_NULL），所以关闭状态下的响应体与 FD-0012 完全一致。</p>
 *
 * <p>下标是可计数的替身而不是占位实现：占位实现在被调用时会抛异常，那样只能证明
 * 「没有崩」，证明不了「没有被调用」。</p>
 */
@SpringBootTest(properties = {
        "spring.profiles.active=deepseek",
        "DEEPSEEK_API_KEY=test-fake-key-not-a-real-secret",
        "spring.datasource.url=jdbc:h2:mem:flowdesk_knowledge_rerank_disabled_web_it"
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "flowdesk.knowledge.storage.root=target/knowledge-rerank-disabled-web-it",
        "flowdesk.knowledge.embedding.enabled=true",
        "flowdesk.knowledge.retrieval.default-top-k=5",
        "flowdesk.knowledge.retrieval.max-top-k=20",
        "flowdesk.knowledge.retrieval.default-min-score=0.30",
        "spring.ai.dashscope.api-key=test-fake-key-not-a-real-secret",
        // 刻意什么都不配：默认关闭，不要求 Endpoint、不要求重排 Key
        "spring.main.allow-bean-definition-overriding=true"
})
@AutoConfigureMockMvc
class KnowledgeRerankDisabledWebTests {

    private static final String SEARCH_PATH = "/api/v1/knowledge/search";

    private static final String ANSWER_PATH = "/api/v1/ai/knowledge-answer";

    private static final String QUESTION = "VPN 无法连接应该如何处理？";

    private static final UUID DOCUMENT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID DOCUMENT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final String DIGEST = "0123456789abcdef".repeat(4);

    private static SyntheticOpenAiEndpoint endpoint;

    @Autowired
    private MockMvc mockMvc;

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
        this.rerankPort.reset();
        endpoint.clearRequests();
        endpoint.willAnswer("结论 [K1]。");
    }

    @Test
    void theSearchResponseLooksExactlyLikeBeforeRerankExisted() throws Exception {
        MvcResult result = this.mockMvc.perform(post(SEARCH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"" + QUESTION + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rankingMode").value("VECTOR_SIMILARITY"))
                .andExpect(jsonPath("$.rerankModel").doesNotExist())
                .andExpect(jsonPath("$.citations[0].citationId").value("K1"))
                .andExpect(jsonPath("$.citations[0].rerankScore").doesNotExist())
                .andExpect(jsonPath("$.citations[0].score").value(0.90))
                .andReturn();

        assertThat(body(result))
                .as("关闭重排时响应里不出现任何重排字段")
                .doesNotContain("rerankModel")
                .doesNotContain("rerankScore")
                .doesNotContain("RERANK");
        assertThat(this.rerankPort.calls()).as("关闭时重排端口调用次数必须为 0").isZero();
    }

    @Test
    void theAnswerResponseLooksExactlyLikeBeforeRerankExisted() throws Exception {
        MvcResult result = this.mockMvc.perform(post(ANSWER_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"" + QUESTION + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounded").value(true))
                .andExpect(jsonPath("$.rankingMode").value("VECTOR_SIMILARITY"))
                .andExpect(jsonPath("$.rerankModel").doesNotExist())
                .andExpect(jsonPath("$.citations[0].rerankScore").doesNotExist())
                .andReturn();

        assertThat(body(result)).doesNotContain("rerankModel").doesNotContain("rerankScore");
        assertThat(this.rerankPort.calls()).isZero();
        assertThat(endpoint.requests()).as("问答链路一切照旧：只调用一次模型").hasSize(1);
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static KnowledgeVectorMatch match(UUID documentId, int chunkIndex, double score) {
        return new KnowledgeVectorMatch(KnowledgeDocumentId.of(documentId), 4L, "VPN 故障处理手册", chunkIndex,
                Sha256Digest.of(DIGEST), "chunk-" + chunkIndex, score);
    }

    /**
     * 替身端口 + 放行启动期校验（测试环境是 H2）。
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
                    match(DOCUMENT_A, 0, 0.90),
                    match(DOCUMENT_B, 1, 0.80));
        }

        @Bean
        @Primary
        StubRerankPort stubRerankPort() {
            return new StubRerankPort();
        }
    }

    /**
     * 可计数的重排替身：本类里它<b>永远不该</b>被调用。
     */
    static final class StubRerankPort implements KnowledgeRerankPort {

        private final List<String> queries = new ArrayList<>();

        @Override
        public List<KnowledgeRerankResult> rerank(String normalizedQuery, List<String> candidateContents) {
            this.queries.add(normalizedQuery);
            throw new AssertionError("关闭重排时不应调用重排端口");
        }

        int calls() {
            return this.queries.size();
        }

        void reset() {
            this.queries.clear();
        }
    }
}
