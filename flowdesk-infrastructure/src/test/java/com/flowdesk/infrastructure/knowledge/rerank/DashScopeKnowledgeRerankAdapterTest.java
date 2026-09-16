package com.flowdesk.infrastructure.knowledge.rerank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.application.knowledge.port.out.KnowledgeRerankResult;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * DashScope 文本重排适配器测试（RAG 6/6）。
 *
 * <p>模型端由 {@link SyntheticRerankEndpoint} 承担（本机回环，不是 DashScope）。
 * 因此这里既能断言<b>真实发出的报文</b>（方法、Authorization、Content-Type、请求体字段），
 * 也能确定性地构造 429 / 5xx / 超时 / 畸形响应，验证失败分类与「不重试、不降级」。</p>
 */
class DashScopeKnowledgeRerankAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String API_KEY = "test-fake-key-not-a-real-secret";

    private static final String QUESTION = "VPN 无法连接应该如何处理？";

    private static final List<String> DOCUMENTS = List.of("SENTINEL-CHUNK-A", "SENTINEL-CHUNK-B");

    private static SyntheticRerankEndpoint endpoint;

    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

    @BeforeEach
    void setUp() throws IOException {
        if (endpoint == null) {
            endpoint = SyntheticRerankEndpoint.start();
        }
        endpoint.clearRequests();
        endpoint.willDelay(0);
        endpoint.willReturn(200, "{\"results\":[]}");
        this.logAppender.start();
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(this.logAppender);
    }

    @AfterEach
    void detachAppender() {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(this.logAppender);
        this.logAppender.stop();
    }

    @AfterAll
    static void stopEndpoint() {
        if (endpoint != null) {
            endpoint.stop();
        }
    }

    // ---------- 请求报文 ----------

    @Test
    void sendsTheFlatQwen3RerankProtocolWithBearerAuthorization() throws Exception {
        endpoint.willReturn(200, """
                {"object":"list","results":[{"index":1,"relevance_score":0.9},
                {"index":0,"relevance_score":0.2}],"model":"qwen3-rerank"}""");

        List<KnowledgeRerankResult> results = adapter(Duration.ofSeconds(2)).rerank(QUESTION, DOCUMENTS);

        assertThat(results).containsExactly(
                new KnowledgeRerankResult(1, 0.9),
                new KnowledgeRerankResult(0, 0.2));

        assertThat(endpoint.calls()).as("一次调用只发一个请求（不做内部重试）").isEqualTo(1);
        SyntheticRerankEndpoint.CapturedRequest request = endpoint.requests().get(0);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.authorization()).isEqualTo("Bearer " + API_KEY);
        assertThat(request.contentType()).contains("application/json");

        JsonNode body = MAPPER.readTree(request.body());
        assertThat(body.path("model").asText()).isEqualTo("qwen3-rerank");
        assertThat(body.path("query").asText()).isEqualTo(QUESTION);
        assertThat(body.path("documents")).hasSize(2);
        assertThat(body.path("documents").get(0).asText()).isEqualTo("SENTINEL-CHUNK-A");
        assertThat(body.path("documents").get(1).asText()).isEqualTo("SENTINEL-CHUNK-B");
        assertThat(body.size()).as("只发送 model / query / documents 三个字段").isEqualTo(3);
        assertThat(body.has("top_n")).as("不设 top_n：本阶段要给全部候选打分").isFalse();
        assertThat(body.has("instruct")).isFalse();
        assertThat(body.has("return_documents")).isFalse();
        assertThat(body.has("input")).as("qwen3-rerank 用扁平协议，不发 input/parameters").isFalse();
        assertThat(body.has("parameters")).isFalse();
    }

    @Test
    void neverSendsInternalIdentifiersOrVectorsToTheRerankProvider() throws Exception {
        adapter(Duration.ofSeconds(2)).rerank(QUESTION, DOCUMENTS);

        String body = endpoint.requests().get(0).body();
        assertThat(body)
                .as("重排只需要「问题 + 候选正文」")
                .doesNotContain("documentId")
                .doesNotContain("documentVersion")
                .doesNotContain("chunkSha256")
                .doesNotContain("embedding")
                .doesNotContain("jdbc:")
                .doesNotContain("SELECT");
    }

    // ---------- 响应解析 ----------

    @Test
    void passesMissingIndexOrScoreThroughAsNullForTheApplicationContractToReject() {
        endpoint.willReturn(200, "{\"results\":[{\"relevance_score\":0.5},{\"index\":1}]}");

        List<KnowledgeRerankResult> results = adapter(Duration.ofSeconds(2)).rerank(QUESTION, DOCUMENTS);

        assertThat(results).containsExactly(
                new KnowledgeRerankResult(null, 0.5),
                new KnowledgeRerankResult(1, null));
    }

    @Test
    void aNonNumericIndexOrScoreIsAnInvalidResponse() {
        endpoint.willReturn(200, "{\"results\":[{\"index\":\"one\",\"relevance_score\":0.5}]}");
        assertInvalidResponse();

        endpoint.willReturn(200, "{\"results\":[{\"index\":0,\"relevance_score\":\"high\"}]}");
        assertInvalidResponse();
    }

    // ---------- 下标必须是整数（FD-0013-R1）----------

    @Test
    void aFractionalOrExponentialIndexIsRejectedInsteadOfBeingTruncated() {
        // 0.9 / 1.8 / 1e0 都是浮点节点：intValue() 会把它们悄悄截断成 0 或 1，
        // 于是「分数属于第 N 个候选」变成一个看起来合法、实际错位的绑定
        for (String index : new String[] { "0.9", "1.8", "1e0", "2.0", "-0.5" }) {
            endpoint.willReturn(200, "{\"results\":[{\"index\":" + index + ",\"relevance_score\":0.9}]}");

            Throwable thrown = catchThrowable(() -> adapter(Duration.ofSeconds(2)).rerank(QUESTION, DOCUMENTS));

            assertThat(thrown).as("index=%s", index).isInstanceOf(KnowledgeApplicationException.class);
            assertThat(((KnowledgeApplicationException) thrown).errorCode())
                    .as("index=%s 不得被截断", index)
                    .isEqualTo(KnowledgeApplicationErrorCode.KNOWLEDGE_RETRIEVAL_FAILURE);
        }
    }

    @Test
    void anIndexOutsideTheIntRangeIsRejectedInsteadOfOverflowing() {
        // 2^32 与 int 边界之外的值：intValue() 会回绕成 0 或负数，同样是错位绑定
        for (String index : new String[] { "4294967296", "-4294967296", "2147483648", "-2147483649",
                "99999999999999999999999" }) {
            endpoint.willReturn(200, "{\"results\":[{\"index\":" + index + ",\"relevance_score\":0.9}]}");

            Throwable thrown = catchThrowable(() -> adapter(Duration.ofSeconds(2)).rerank(QUESTION, DOCUMENTS));

            assertThat(thrown).as("index=%s", index).isInstanceOf(KnowledgeApplicationException.class);
            assertThat(((KnowledgeApplicationException) thrown).errorCode())
                    .as("index=%s 不得溢出", index)
                    .isEqualTo(KnowledgeApplicationErrorCode.KNOWLEDGE_RETRIEVAL_FAILURE);
        }
    }

    @Test
    void aBooleanOrStructuredIndexIsRejected() {
        for (String index : new String[] { "true", "false", "[0]", "{\"value\":0}" }) {
            endpoint.willReturn(200, "{\"results\":[{\"index\":" + index + ",\"relevance_score\":0.9}]}");

            Throwable thrown = catchThrowable(() -> adapter(Duration.ofSeconds(2)).rerank(QUESTION, DOCUMENTS));

            assertThat(thrown).as("index=%s", index).isInstanceOf(KnowledgeApplicationException.class);
            assertThat(((KnowledgeApplicationException) thrown).errorCode())
                    .isEqualTo(KnowledgeApplicationErrorCode.KNOWLEDGE_RETRIEVAL_FAILURE);
        }
    }

    @Test
    void theWholeRequestFailsAtTheFirstBadIndexWithoutSendingAnythingElse() {
        // 第一条合法、第二条是小数：整次请求失败，且只发了一次请求（不重试、不降级）
        endpoint.willReturn(200, "{\"results\":[{\"index\":0,\"relevance_score\":0.9},"
                + "{\"index\":1.5,\"relevance_score\":0.1}]}");

        Throwable thrown = catchThrowable(() -> adapter(Duration.ofSeconds(2)).rerank(QUESTION, DOCUMENTS));

        assertThat(thrown).isInstanceOf(KnowledgeApplicationException.class);
        assertThat(((KnowledgeApplicationException) thrown).errorCode())
                .isEqualTo(KnowledgeApplicationErrorCode.KNOWLEDGE_RETRIEVAL_FAILURE);
        assertThat(endpoint.calls()).isEqualTo(1);
    }

    @Test
    void integerIndicesInAnyOrderAreStillBoundByTheirOriginalPosition() throws Exception {
        // 合法乱序（int 范围内）必须照旧归位：这条能力不能被上面的收紧破坏
        endpoint.willReturn(200, "{\"results\":[{\"index\":1,\"relevance_score\":0.9},"
                + "{\"index\":0,\"relevance_score\":0.2}]}");

        List<KnowledgeRerankResult> results = adapter(Duration.ofSeconds(2)).rerank(QUESTION, DOCUMENTS);

        assertThat(results).containsExactly(
                new KnowledgeRerankResult(1, 0.9),
                new KnowledgeRerankResult(0, 0.2));
    }

    @Test
    void aResponseWithoutATopLevelResultsArrayIsAnInvalidResponse() {
        // 嵌套 output.results 属于 gte-rerank-v2 的协议：不猜测、不兼容，直接判为无法解释
        endpoint.willReturn(200, "{\"output\":{\"results\":[{\"index\":0,\"relevance_score\":0.9}]}}");
        assertInvalidResponse();

        endpoint.willReturn(200, "{\"object\":\"list\"}");
        assertInvalidResponse();

        endpoint.willReturn(200, "{\"results\":[{\"index\":0},42]}");
        assertInvalidResponse();
    }

    @Test
    void aBodyThatIsNotJsonIsAnInvalidResponse() {
        endpoint.willReturn(200, "not-json-sentinel");
        assertInvalidResponse();

        endpoint.willReturn(200, "");
        assertInvalidResponse();
    }

    // ---------- 上游失败 ----------

    @Test
    void rateLimitAndServerErrorsBecomeProviderErrors() {
        for (int status : new int[] { 429, 500, 502, 503 }) {
            endpoint.willReturn(status, "{\"code\":\"Throttling\",\"message\":\"SENTINEL-UPSTREAM-429\"}");

            Throwable thrown = catchThrowable(() -> adapter(Duration.ofSeconds(2)).rerank(QUESTION, DOCUMENTS));

            assertThat(thrown).as("status=%s", status).isInstanceOf(KnowledgeApplicationException.class);
            assertThat(((KnowledgeApplicationException) thrown).errorCode())
                    .as("status=%s", status)
                    .isEqualTo(KnowledgeApplicationErrorCode.RERANK_PROVIDER_ERROR);
        }
        assertThat(endpoint.calls()).as("四次失败各发一次请求，不做内部重试").isEqualTo(4);
    }

    @Test
    void anAuthenticationFailureIsAlsoAProviderError() {
        endpoint.willReturn(401, "{\"code\":\"InvalidApiKey\",\"message\":\"Invalid API-key provided.\"}");

        Throwable thrown = catchThrowable(() -> adapter(Duration.ofSeconds(2)).rerank(QUESTION, DOCUMENTS));

        assertThat(thrown).isInstanceOf(KnowledgeApplicationException.class);
        assertThat(((KnowledgeApplicationException) thrown).errorCode())
                .isEqualTo(KnowledgeApplicationErrorCode.RERANK_PROVIDER_ERROR);
    }

    @Test
    void aReadTimeoutBecomesAProviderErrorWithoutRetrying() {
        endpoint.willDelay(1_500);

        Throwable thrown = catchThrowable(
                () -> adapter(Duration.ofMillis(300)).rerank(QUESTION, DOCUMENTS));

        assertThat(thrown).isInstanceOf(KnowledgeApplicationException.class);
        assertThat(((KnowledgeApplicationException) thrown).errorCode())
                .isEqualTo(KnowledgeApplicationErrorCode.RERANK_PROVIDER_ERROR);
        assertThat(endpoint.calls()).as("超时后不得重试").isEqualTo(1);
    }

    // ---------- 不泄漏 ----------

    @Test
    void neitherSuccessNorFailureLogsCarryTheQuestionTheDocumentsOrTheKey() {
        endpoint.willReturn(200, "{\"results\":[{\"index\":0,\"relevance_score\":0.9}]}");
        adapter(Duration.ofSeconds(2)).rerank(QUESTION, DOCUMENTS);

        endpoint.willReturn(429, "{\"message\":\"SENTINEL-UPSTREAM-429\"}");
        catchThrowable(() -> adapter(Duration.ofSeconds(2)).rerank(QUESTION, DOCUMENTS));

        endpoint.willReturn(200, "not-json-SENTINEL-BODY");
        catchThrowable(() -> adapter(Duration.ofSeconds(2)).rerank(QUESTION, DOCUMENTS));

        List<String> messages = this.logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(java.util.Objects::nonNull)
                .toList();

        assertThat(messages).as("这些调用确实产生了日志（否则本测试毫无意义）").isNotEmpty();
        assertThat(messages).noneMatch(message -> message.contains(QUESTION)
                || message.contains("SENTINEL-CHUNK")
                || message.contains("SENTINEL-UPSTREAM")
                || message.contains("SENTINEL-BODY")
                || message.contains(API_KEY)
                || message.contains("127.0.0.1"));
    }

    // ---------- 传输安全：Bearer Key 不得走明文 HTTP（FD-0013-R1）----------

    @Test
    void theConstructorRejectsAPlainHttpEndpointOutsideLoopback() {
        for (String endpoint : new String[] {
                "http://rerank.example.com/compatible-api/v1/reranks",
                "http://10.0.0.1:8080/reranks",
                "http://192.168.1.10/reranks",
                "http://[2001:db8::1]/reranks",
                // FD-0013-R2：前缀像回环、实际不是完整 IPv4 字面量的写法
                "http://127.example.com/reranks",
                "http://127.0.0.1.attacker.example/reranks",
                "http://127.999.999.999/reranks",
                "http://127.5/reranks",
                "http://0127.0.0.1/reranks",
                "http://2130706433/reranks",
                "http://127..0.1/reranks",
                "http://[::ffff:127.0.0.1]/reranks" }) {

            Throwable thrown = catchThrowable(() -> new DashScopeKnowledgeRerankAdapter(
                    URI.create(endpoint), KnowledgeRerankProperties.SUPPORTED_MODEL, API_KEY,
                    Duration.ofSeconds(2), Duration.ofSeconds(2)));

            assertThat(thrown).as("endpoint=%s", endpoint)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("HTTPS");
            assertThat(thrown.getMessage())
                    .as("错误信息不得回显 Endpoint 或 Key")
                    .doesNotContain(endpoint)
                    .doesNotContain(API_KEY);
        }
    }

    @Test
    void theConstructorAcceptsHttpsAndLoopbackHttpEndpoints() {
        for (String endpoint : new String[] {
                "https://rerank.example.com/compatible-api/v1/reranks",
                "https://127.0.0.1/reranks",
                "http://127.0.0.1:8080/reranks",
                "http://127.5.5.5/reranks",
                "http://127.255.255.255/reranks",
                "http://localhost:8080/reranks",
                "http://LOCALHOST:8080/reranks",
                "http://[::1]:8080/reranks",
                "http://[0:0:0:0:0:0:0:1]:8080/reranks" }) {

            assertThatCode(() -> new DashScopeKnowledgeRerankAdapter(URI.create(endpoint),
                    KnowledgeRerankProperties.SUPPORTED_MODEL, API_KEY, Duration.ofSeconds(2),
                    Duration.ofSeconds(2)))
                    .as("endpoint=%s（HTTPS 或本机回环字面量）必须被接受", endpoint)
                    .doesNotThrowAnyException();
        }
    }

    // ---------- 入参防护 ----------

    @Test
    void rejectsMissingOrEmptyInputs() {
        DashScopeKnowledgeRerankAdapter adapter = adapter(Duration.ofSeconds(2));

        assertThatThrownBy(() -> adapter.rerank(null, DOCUMENTS)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> adapter.rerank(QUESTION, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> adapter.rerank("", DOCUMENTS)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.rerank(QUESTION, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(endpoint.calls()).as("入参不合法时不得发起任何请求").isZero();
    }

    // ---------- 辅助 ----------

    private static DashScopeKnowledgeRerankAdapter adapter(Duration readTimeout) {
        return new DashScopeKnowledgeRerankAdapter(endpoint.uri(), KnowledgeRerankProperties.SUPPORTED_MODEL,
                API_KEY, Duration.ofSeconds(2), readTimeout);
    }

    private void assertInvalidResponse() {
        Throwable thrown = catchThrowable(() -> adapter(Duration.ofSeconds(2)).rerank(QUESTION, DOCUMENTS));

        assertThat(thrown).isInstanceOf(KnowledgeApplicationException.class);
        assertThat(((KnowledgeApplicationException) thrown).errorCode())
                .as("无法解释的响应属于内部检索失败（500），不是上游不可用")
                .isEqualTo(KnowledgeApplicationErrorCode.KNOWLEDGE_RETRIEVAL_FAILURE);
    }
}
