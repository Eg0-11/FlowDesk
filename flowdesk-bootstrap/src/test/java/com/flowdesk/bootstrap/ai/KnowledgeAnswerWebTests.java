package com.flowdesk.bootstrap.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.application.knowledge.port.out.KnowledgeQueryEmbeddingPort;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
 * 知识库问答接口的 HTTP 契约测试（RAG 5/6）。
 *
 * <p>整条链路都是<b>真实</b>的：真实的检索用例、真实的提示词构造、真实引用校验、
 * 真实的 Spring AI {@code ChatClient}。只有两处被替换：</p>
 * <ol>
 *   <li>查询向量与向量检索两个出站端口（测试环境是 H2，没有 {@code <=>}）；</li>
 *   <li>模型端：{@link SyntheticOpenAiEndpoint} 在本地回环端口按脚本应答，
 *       并记录<b>真实发出的请求体</b>，因此可以断言「模型究竟收到了什么」。</li>
 * </ol>
 *
 * <p>被反复验证的核心承诺是三条：</p>
 * <ul>
 *   <li>没有检索命中时<b>不调用模型</b>（降级答案为固定文案）；</li>
 *   <li>检索失败时<b>不调用模型</b>，并按检索自己的错误契约返回；</li>
 *   <li>模型答案里的引用必须落在本次证据内，否则整次作答失败（502），
 *       <b>不做</b>静默修正或重试。</li>
 * </ul>
 */
@SpringBootTest(properties = {
        // AI 编排与 DeepSeek 对话客户端需要这个 profile；Key 是假值，模型端被合成端点接管
        "spring.profiles.active=deepseek",
        "DEEPSEEK_API_KEY=test-fake-key-not-a-real-secret",
        "spring.datasource.url=jdbc:h2:mem:flowdesk_knowledge_answer_web_it"
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "flowdesk.knowledge.storage.root=target/knowledge-answer-web-it",
        "flowdesk.knowledge.embedding.enabled=true",
        "flowdesk.knowledge.retrieval.default-top-k=5",
        "flowdesk.knowledge.retrieval.max-top-k=20",
        "flowdesk.knowledge.retrieval.default-min-score=0.30",
        // 启用向量化就必须有 DashScope Key（启动期校验）；真正的模型调用被下面的端口替身覆盖
        "spring.ai.dashscope.api-key=test-fake-key-not-a-real-secret",
        // 覆盖生产校验：本测试用 H2 且只测 HTTP 契约
        "spring.main.allow-bean-definition-overriding=true"
})
@AutoConfigureMockMvc
class KnowledgeAnswerWebTests {

    private static final String ANSWER_PATH = "/api/v1/ai/knowledge-answer";

    private static final UUID DOCUMENT_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static final String DIGEST = "0123456789abcdef".repeat(4);

    /** 切片正文里的哨兵：用来确认它确实进入了模型请求，又确实没有进入响应与日志。 */
    private static final String CHUNK_BODY = "SENTINEL-CHUNK-BODY-STEP-ONE";

    private static final String QUESTION = "VPN 无法连接应该如何处理？";

    private static SyntheticOpenAiEndpoint endpoint;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StubQueryEmbeddingPort queryPort;

    @Autowired
    private StubVectorSearchPort searchPort;

    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

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
        endpoint.clearRequests();
        endpoint.willAnswer("依据手册：先检查隧道状态 [K1]。");
        this.logAppender.start();
        rootLogger().addAppender(this.logAppender);
    }

    @AfterEach
    void detachAppender() {
        rootLogger().detachAppender(this.logAppender);
        this.logAppender.stop();
    }

    // ---------- 成功路径 ----------

    @Test
    void aGroundedAnswerReturnsTheAnswerItsUsedCitationsAndTheFullEvidence() throws Exception {
        this.searchPort.willReturn(List.of(
                match(2, 0.873421, "VPN 故障处理手册", CHUNK_BODY),
                match(0, 0.541200, "VPN 故障处理手册", "STEP-TWO-CHECK-ACCOUNT")));
        endpoint.willAnswer("按手册先检查隧道状态 [K1]，再确认账号状态 [K2]。");

        MvcResult result = this.mockMvc.perform(post(ANSWER_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"" + QUESTION + "\",\"topK\":5,\"minScore\":0.30}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.answer").value("按手册先检查隧道状态 [K1]，再确认账号状态 [K2]。"))
                .andExpect(jsonPath("$.grounded").value(true))
                .andExpect(jsonPath("$.usedCitationIds.length()").value(2))
                .andExpect(jsonPath("$.usedCitationIds[0]").value("K1"))
                .andExpect(jsonPath("$.usedCitationIds[1]").value("K2"))
                .andExpect(jsonPath("$.embeddingProvider").value("dashscope"))
                .andExpect(jsonPath("$.embeddingModel").value("text-embedding-v4"))
                .andExpect(jsonPath("$.embeddingDimensions").value(1024))
                .andExpect(jsonPath("$.topK").value(5))
                .andExpect(jsonPath("$.minScore").value(0.30))
                .andExpect(jsonPath("$.citations.length()").value(2))
                .andExpect(jsonPath("$.citations[0].citationId").value("K1"))
                .andExpect(jsonPath("$.citations[0].rank").value(1))
                .andExpect(jsonPath("$.citations[0].documentId").value(DOCUMENT_ID.toString()))
                .andExpect(jsonPath("$.citations[0].documentTitle").value("VPN 故障处理手册"))
                .andExpect(jsonPath("$.citations[0].chunkIndex").value(2))
                .andExpect(jsonPath("$.citations[0].chunkSha256").value(DIGEST))
                .andExpect(jsonPath("$.citations[0].content").value(CHUNK_BODY))
                .andExpect(jsonPath("$.citations[0].score").value(0.873421))
                .andExpect(jsonPath("$.citations[1].citationId").value("K2"))
                .andReturn();

        assertThat(body(result))
                .as("响应不回显用户问题，也不含向量、SQL 或 Java 异常信息")
                .doesNotContain(QUESTION)
                .doesNotContain("vector")
                .doesNotContain("SELECT")
                .doesNotContain("Exception")
                .doesNotContain("java.");

        // 模型请求：只有一轮、没有工具、只包含必要字段
        List<SyntheticOpenAiEndpoint.CapturedRequest> requests = endpoint.requests();
        assertThat(requests).as("一次问答只调用一次模型").hasSize(1);
        SyntheticOpenAiEndpoint.CapturedRequest request = requests.get(0);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.hasTools()).as("问答链路不得注册任何工具").isFalse();
        assertThat(request.hasToolResult()).isFalse();
        assertThat(request.thinkingType()).isEqualTo("disabled");
        assertThat(request.containsText("<<<FLOWDESK_QUESTION_BEGIN>>>")).isTrue();
        assertThat(request.containsText("<<<FLOWDESK_EVIDENCE_BEGIN>>>")).isTrue();
        assertThat(request.containsText(CHUNK_BODY)).as("证据正文必须发给模型").isTrue();
        assertThat(request.containsText("STEP-TWO-CHECK-ACCOUNT")).as("每一条证据都要发给模型").isTrue();
        assertThat(request.containsText("citationId")).as("允许使用的编号清单必须发给模型").isTrue();
        assertThat(request.containsText("[K1]")).isTrue();
        assertThat(request.containsText("[K2]")).isTrue();
        assertThat(request.body())
                .as("文档标识、版本、切片摘要、向量与密钥都不得发给模型")
                .doesNotContain(DOCUMENT_ID.toString())
                .doesNotContain(DIGEST)
                .doesNotContain("chunkSha256")
                .doesNotContain("test-fake-key-not-a-real-secret");

        assertThat(this.queryPort.queries()).containsExactly(QUESTION);
        assertThat(this.searchPort.topKs()).containsExactly(5);
        assertThat(this.searchPort.minScores()).containsExactly(0.30);
    }

    @Test
    void reportsOnlyTheCitationsTheAnswerActuallyUses() throws Exception {
        this.searchPort.willReturn(List.of(
                match(0, 0.9, "标题一", "正文一"),
                match(1, 0.8, "标题二", "正文二"),
                match(2, 0.7, "标题三", "正文三")));
        endpoint.willAnswer("只依据第三条 [K3]。");

        this.mockMvc.perform(post(ANSWER_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"" + QUESTION + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounded").value(true))
                .andExpect(jsonPath("$.usedCitationIds.length()").value(1))
                .andExpect(jsonPath("$.usedCitationIds[0]").value("K3"))
                .andExpect(jsonPath("$.citations.length()").value(3))
                .andExpect(jsonPath("$.citations[2].citationId").value("K3"));
    }

    @Test
    void anEmptyBodyAndAnEmptyObjectFollowTheRetrievalContract() throws Exception {
        for (String payload : Arrays.asList(null, "{}")) {
            MvcResult result = this.mockMvc.perform(post(ANSWER_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload == null ? new byte[0] : payload.getBytes(StandardCharsets.UTF_8)))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).as("payload=[%s]", payload).isEqualTo(400);
            assertThat(body(result)).as("payload=[%s]", payload)
                    .contains("\"code\":\"INVALID_REQUEST\"")
                    .contains("\"detail\":\"检索请求不合法\"")
                    .contains("urn:flowdesk:problem:invalid-request");
        }

        assertThat(endpoint.requests()).as("空请求不得调用模型").isEmpty();
        assertThat(this.queryPort.calls()).isZero();
        assertThat(this.searchPort.calls()).isZero();
    }

    // ---------- 无证据：不调用模型 ----------

    @Test
    void noEvidenceReturns200WithAFixedAnswerAndNeverCallsTheModel() throws Exception {
        this.searchPort.willReturn(List.of());

        MvcResult result = this.mockMvc.perform(post(ANSWER_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"" + QUESTION + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounded").value(false))
                .andExpect(jsonPath("$.answer").value("当前知识库中没有足够证据回答该问题。"))
                .andExpect(jsonPath("$.usedCitationIds").isArray())
                .andExpect(jsonPath("$.usedCitationIds.length()").value(0))
                .andExpect(jsonPath("$.citations").isArray())
                .andExpect(jsonPath("$.citations.length()").value(0))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andReturn();

        assertThat(endpoint.requests())
                .as("检索没有命中时绝不能调用模型")
                .isEmpty();
        assertThat(this.queryPort.calls()).as("仍然真实执行了一次查询向量化").isEqualTo(1);
        assertThat(body(result)).doesNotContain(QUESTION);
    }

    // ---------- 请求形态 ----------

    @Test
    void invalidFieldsReturn400BeforeAnyModelOrVectorWork() throws Exception {
        for (String payload : List.of(
                "{\"query\":\"\"}",
                "{\"query\":\"   \"}",
                "{\"query\":\"VPN\",\"topK\":0}",
                "{\"query\":\"VPN\",\"topK\":21}",
                "{\"query\":\"VPN\",\"minScore\":-0.01}",
                "{\"query\":\"VPN\",\"minScore\":1.01}",
                "{\"query\":\"VPN\\u0000\"}")) {

            MvcResult result = this.mockMvc.perform(post(ANSWER_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).as("payload=%s", payload).isEqualTo(400);
            assertThat(result.getResponse().getContentType())
                    .as("payload=%s", payload)
                    .contains(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            assertThat(body(result)).as("payload=%s", payload)
                    .contains("\"code\":\"INVALID_REQUEST\"")
                    .contains("\"detail\":\"检索请求不合法\"");
        }

        assertThat(endpoint.requests()).as("非法请求不得调用模型").isEmpty();
        assertThat(this.queryPort.calls()).as("非法请求不得调用 Query Embedding").isZero();
        assertThat(this.searchPort.calls()).as("非法请求不得访问向量检索端口").isZero();
    }

    @Test
    void aMalformedJsonBodyKeepsTheGlobalJsonContract() throws Exception {
        for (String malformed : List.of("{\"query\":", "   ", "not-json")) {
            this.mockMvc.perform(post(ANSWER_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(malformed))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.detail").value("请求体不是合法 JSON"));
        }

        assertThat(endpoint.requests()).isEmpty();
        assertThat(this.queryPort.calls()).isZero();
    }

    @Test
    void anUnsupportedContentTypeReturns415AndAnUnsatisfiableAcceptReturns406() throws Exception {
        this.mockMvc.perform(post(ANSWER_PATH)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("{\"query\":\"VPN\"}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));

        this.mockMvc.perform(post(ANSWER_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_XML)
                        .content("{\"query\":\"VPN\"}"))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"));

        assertThat(endpoint.requests()).isEmpty();
    }

    // ---------- 引用校验失败：502 ----------

    @Test
    void anAnswerWithoutAnyCitationIsRejectedWith502() throws Exception {
        this.searchPort.willReturn(List.of(match(0, 0.9, "标题", "正文")));
        endpoint.willAnswer("SENTINEL-MODEL-ANSWER-根据经验重启服务即可。");

        MvcResult result = this.mockMvc.perform(post(ANSWER_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"" + QUESTION + "\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AI_PROVIDER_ERROR"))
                .andExpect(jsonPath("$.type").value("urn:flowdesk:problem:ai-provider-error"))
                .andExpect(jsonPath("$.detail").value("上游 AI 服务暂时不可用，请稍后重试"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andReturn();

        assertThat(body(result))
                .as("失败响应不得回显模型答案、问题或内部异常")
                .doesNotContain("SENTINEL-MODEL-ANSWER")
                .doesNotContain(QUESTION)
                .doesNotContain("GroundedAnswer")
                .doesNotContain("Exception");
        assertThat(endpoint.requests()).as("校验失败不重试，模型只被调用一次").hasSize(1);
    }

    @Test
    void anUnknownOrInvalidCitationIsRejectedWith502() throws Exception {
        this.searchPort.willReturn(List.of(match(0, 0.9, "标题", "正文")));

        for (String answer : List.of("结论是重启服务 SENTINEL-K7 [K7]。", "结论是重启服务 SENTINEL-K0 [K0]。",
                "结论是重启服务 SENTINEL-ZEROPAD [K01]。")) {

            endpoint.willAnswer(answer);

            MvcResult result = this.mockMvc.perform(post(ANSWER_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"query\":\"" + QUESTION + "\"}"))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.code").value("AI_PROVIDER_ERROR"))
                    .andExpect(jsonPath("$.requestId").isNotEmpty())
                    .andReturn();

            assertThat(body(result)).as("answer=[%s]", answer)
                    .doesNotContain("SENTINEL-")
                    .doesNotContain("[K7]")
                    .doesNotContain("[K01]");
        }
    }

    @Test
    void anEmptyModelAnswerIsRejectedWith502() throws Exception {
        this.searchPort.willReturn(List.of(match(0, 0.9, "标题", "正文")));
        endpoint.willAnswer("   ");

        this.mockMvc.perform(post(ANSWER_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"" + QUESTION + "\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("AI_PROVIDER_ERROR"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    // ---------- 检索失败：不调用模型 ----------

    @Test
    void anEmbeddingProviderFailureKeepsTheRetrievalContractAndNeverCallsTheModel() throws Exception {
        this.queryPort.failWith(new KnowledgeApplicationException(
                KnowledgeApplicationErrorCode.EMBEDDING_PROVIDER_ERROR, "查询向量服务调用失败 SENTINEL-EMBEDDING"));

        MvcResult result = this.mockMvc.perform(post(ANSWER_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"" + QUESTION + "\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("EMBEDDING_PROVIDER_ERROR"))
                .andExpect(jsonPath("$.type").value("urn:flowdesk:problem:embedding-provider-error"))
                .andReturn();

        assertThat(body(result)).doesNotContain("SENTINEL-EMBEDDING").doesNotContain(QUESTION);
        assertThat(endpoint.requests()).as("检索失败时绝不能调用模型").isEmpty();
        assertThat(this.searchPort.calls()).isZero();
    }

    @Test
    void aRetrievalFailureReturns500AndNeverCallsTheModel() throws Exception {
        this.searchPort.failWith(new KnowledgeApplicationException(
                KnowledgeApplicationErrorCode.KNOWLEDGE_RETRIEVAL_FAILURE, "向量检索失败",
                new RuntimeException("SELECT ... FROM knowledge_document_chunk_embeddings jdbc:postgresql://db/x")));

        MvcResult result = this.mockMvc.perform(post(ANSWER_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"" + QUESTION + "\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andReturn();

        assertThat(body(result))
                .doesNotContain("SELECT")
                .doesNotContain("jdbc:")
                .doesNotContain("knowledge_document")
                .doesNotContain("Exception");
        assertThat(endpoint.requests()).isEmpty();
    }

    // ---------- 日志不泄露 ----------

    @Test
    void neitherTheSuccessNorTheFailureLogCarriesTheQuestionTheEvidenceOrTheAnswer() throws Exception {
        String answerSentinel = "SENTINEL-MODEL-ANSWER-先检查隧道状态 [K1]。";
        this.searchPort.willReturn(List.of(match(0, 0.9, "标题 SENTINEL-TITLE", CHUNK_BODY)));
        endpoint.willAnswer(answerSentinel);

        this.mockMvc.perform(post(ANSWER_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"" + QUESTION + "\"}"))
                .andExpect(status().isOk());

        // 失败路径同样只记录稳定失败类别
        endpoint.willAnswer("SENTINEL-MODEL-ANSWER-没有引用。");
        this.mockMvc.perform(post(ANSWER_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"" + QUESTION + "\"}"))
                .andExpect(status().isBadGateway());

        List<String> messages = this.logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(java.util.Objects::nonNull)
                .toList();

        assertThat(messages).as("这一次问答确实产生了日志（否则本测试毫无意义）")
                .anyMatch(message -> message.contains("ai.knowledge-answer"));
        assertThat(messages)
                .as("问题、切片正文、模型答案与密钥都不得进入日志")
                .noneMatch(message -> message.contains(QUESTION)
                        || message.contains(CHUNK_BODY)
                        || message.contains("SENTINEL-MODEL-ANSWER")
                        || message.contains("SENTINEL-TITLE")
                        || message.contains("test-fake-key-not-a-real-secret"));
        assertThat(messages).as("日志只记录稳定失败类别，不记录异常 message 或堆栈")
                .anyMatch(message -> message.contains("failure=MODEL_CALL_FAILED")
                        || message.contains("failure=ANSWER_WITHOUT_CITATION"));
    }

    // ---------- 辅助 ----------

    private static Logger rootLogger() {
        return (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    }

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
     * 查询向量替身：记录 query，可注入上游失败。
     */
    static final class StubQueryEmbeddingPort implements KnowledgeQueryEmbeddingPort {

        private final List<String> queries = new ArrayList<>();

        private RuntimeException failure;

        @Override
        public float[] embedQuery(String query, EmbeddingDescriptor descriptor) {
            this.queries.add(query);
            if (this.failure != null) {
                throw this.failure;
            }
            float[] vector = new float[EmbeddingDescriptor.REQUIRED_DIMENSIONS];
            Arrays.fill(vector, 0.5f);
            return vector;
        }

        void failWith(RuntimeException failure) {
            this.failure = failure;
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
        }
    }

    /**
     * 向量检索替身：记录 topK 与阈值，可注入失败或返回预置命中。
     */
    static final class StubVectorSearchPort implements KnowledgeVectorSearchPort {

        private final List<Integer> topKs = new ArrayList<>();

        private final List<Double> minScores = new ArrayList<>();

        private List<KnowledgeVectorMatch> matches = List.of();

        private RuntimeException failure;

        @Override
        public List<KnowledgeVectorMatch> search(KnowledgeQueryEmbedding queryEmbedding, double minScore, int topK) {
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
