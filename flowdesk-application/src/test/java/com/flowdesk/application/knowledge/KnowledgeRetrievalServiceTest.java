package com.flowdesk.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.flowdesk.application.knowledge.port.out.KnowledgeVectorMatch;
import com.flowdesk.application.knowledge.query.RetrieveKnowledgeQuery;
import com.flowdesk.application.knowledge.service.KnowledgeRetrievalService;
import com.flowdesk.application.knowledge.view.KnowledgeCitationView;
import com.flowdesk.application.knowledge.view.KnowledgeRetrievalView;
import com.flowdesk.domain.knowledge.EmbeddingDescriptor;
import com.flowdesk.domain.knowledge.KnowledgeDocumentId;
import com.flowdesk.domain.knowledge.Sha256Digest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 检索用例服务测试（RAG 4/6）。
 *
 * <p>覆盖三类契约：<b>输入边界</b>（NFC、strip、code point、控制字符、topK/minScore 边界）、
 * <b>调用时序与开关</b>（非法输入零端口调用、关闭状态零模型零数据库、合法调用先模型后数据库）、
 * <b>结果契约</b>（引用编号与 rank、topK、阈值、顺序、重复、非法分数一律拒绝）。</p>
 */
class KnowledgeRetrievalServiceTest {

    private static final EmbeddingDescriptor DESCRIPTOR =
            EmbeddingDescriptor.of("dashscope", "text-embedding-v4");

    private static final UUID FIRST_DOCUMENT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID SECOND_DOCUMENT = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final int MAX_QUERY_CODE_POINTS = 2000;

    private static final int DEFAULT_TOP_K = 5;

    private static final int MAX_TOP_K = 20;

    private static final double DEFAULT_MIN_SCORE = 0.30;

    private RecordingRetrievalPorts ports;

    private RecordingRetrievalPorts.RecordingQueryEmbeddingPort queryPort;

    private RecordingRetrievalPorts.RecordingVectorSearchPort searchPort;

    private KnowledgeRetrievalService service;

    @BeforeEach
    void setUp() {
        this.ports = new RecordingRetrievalPorts();
        this.queryPort = this.ports.new RecordingQueryEmbeddingPort();
        this.searchPort = this.ports.new RecordingVectorSearchPort();
        this.queryPort.willReturn(RecordingRetrievalPorts.vector(0.5f));
        this.service = service(true);
    }

    private KnowledgeRetrievalService service(boolean enabled) {
        return new KnowledgeRetrievalService(this.queryPort, this.searchPort, enabled, DESCRIPTOR,
                MAX_QUERY_CODE_POINTS, DEFAULT_TOP_K, MAX_TOP_K, DEFAULT_MIN_SCORE);
    }

    // ---------- 输入边界 ----------

    @Test
    void rejectsMissingOrBlankQueriesWithoutTouchingAnyPort() {
        for (String query : new String[] { null, "", "   ", "\t\n", "\u3000" }) {
            assertInvalidQuery(new RetrieveKnowledgeQuery(query, null, null));
        }
        assertInvalidQuery(null);
    }

    @Test
    void normalizesWithNfcAndStripBeforeCallingThePort() {
        // "ＶＰＮ"（全角）在 NFC 下保持不变，但首尾空白与内部 NBSP 的 strip 行为要固定下来
        KnowledgeRetrievalView view = this.service.retrieve(
                new RetrieveKnowledgeQuery("  VPN 无法连接  ", null, null));

        assertThat(view.citations()).isEmpty();
        assertThat(this.queryPort.queries()).containsExactly("VPN 无法连接");
    }

    @Test
    void normalizesToNfc() {
        // "e" + U+0301（组合重音）在 NFC 下是单个 U+00E9
        String decomposed = "re\u0301sume\u0301";

        this.service.retrieve(new RetrieveKnowledgeQuery(decomposed, null, null));

        assertThat(this.queryPort.queries()).containsExactly("r\u00e9sum\u00e9");
    }

    @Test
    void rejectsQueriesLongerThanTheConfiguredCodePointLimit() {
        String allowed = "a".repeat(MAX_QUERY_CODE_POINTS);
        this.service.retrieve(new RetrieveKnowledgeQuery(allowed, null, null));
        assertThat(this.queryPort.calls()).isEqualTo(1);

        assertInvalidQuery(new RetrieveKnowledgeQuery("a".repeat(MAX_QUERY_CODE_POINTS + 1), null, null));
    }

    @Test
    void countsCodePointsNotUtf16Units() {
        // 每个 emoji 占 2 个 char 但只有 1 个 code point：2000 个 emoji 必须通过
        String emojis = "\uD83D\uDE00".repeat(MAX_QUERY_CODE_POINTS);

        this.service.retrieve(new RetrieveKnowledgeQuery(emojis, null, null));

        assertThat(this.queryPort.calls()).isEqualTo(1);
        assertInvalidQuery(new RetrieveKnowledgeQuery("\uD83D\uDE00".repeat(MAX_QUERY_CODE_POINTS + 1),
                null, null));
    }

    @Test
    void rejectsIsoControlCharacters() {
        assertInvalidQuery(new RetrieveKnowledgeQuery("VPN\u0000无法连接", null, null));
        assertInvalidQuery(new RetrieveKnowledgeQuery("VPN\u0007无法连接", null, null));
        assertInvalidQuery(new RetrieveKnowledgeQuery("VPN\u001B无法连接", null, null));
        // 首尾的换行会被 strip 掉，因此仍然合法
        this.service.retrieve(new RetrieveKnowledgeQuery("\nVPN 无法连接\n", null, null));
        assertThat(this.queryPort.calls()).isEqualTo(1);
    }

    @Test
    void rejectsTopKOutsideTheConfiguredRange() {
        for (Integer topK : new Integer[] { 0, -1, MAX_TOP_K + 1, 1000 }) {
            assertInvalidQuery(new RetrieveKnowledgeQuery("VPN", topK, null));
        }
        // 边界必须包含
        for (Integer topK : new Integer[] { 1, MAX_TOP_K }) {
            this.service.retrieve(new RetrieveKnowledgeQuery("VPN", topK, null));
        }
        assertThat(this.searchPort.topKs()).containsExactly(1, MAX_TOP_K);
    }

    @Test
    void rejectsMinScoreOutsideTheUnitRangeOrNotFinite() {
        for (Double minScore : new Double[] { -0.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY }) {
            assertInvalidQuery(new RetrieveKnowledgeQuery("VPN", null, minScore));
        }
        // 边界必须包含 0 与 1
        this.service.retrieve(new RetrieveKnowledgeQuery("VPN", null, 0.0));
        this.service.retrieve(new RetrieveKnowledgeQuery("VPN", null, 1.0));
        assertThat(this.searchPort.minScores()).containsExactly(0.0, 1.0);
    }

    @Test
    void appliesTheConfiguredDefaultsWhenTheRequestOmitsThem() {
        this.service.retrieve(new RetrieveKnowledgeQuery("VPN", null, null));

        assertThat(this.searchPort.topKs()).containsExactly(DEFAULT_TOP_K);
        assertThat(this.searchPort.minScores()).containsExactly(DEFAULT_MIN_SCORE);
    }

    @Test
    void anInvalidRequestNeverTouchesAnyPort() {
        assertInvalidQuery(new RetrieveKnowledgeQuery("VPN", 21, null));
        assertInvalidQuery(new RetrieveKnowledgeQuery("VPN", null, 2.0));

        assertThat(this.ports.callOrder()).isEmpty();
    }

    // ---------- 开关与调用时序 ----------

    @Test
    void disabledEmbeddingDoesNotCallTheModelOrTheDatabase() {
        KnowledgeRetrievalService disabled = service(false);

        assertApplicationError(() -> disabled.retrieve(new RetrieveKnowledgeQuery("VPN", null, null)),
                KnowledgeApplicationErrorCode.KNOWLEDGE_EMBEDDING_DISABLED);

        assertThat(this.ports.callOrder()).as("关闭状态必须先失败，而不是先查向量表").isEmpty();
    }

    @Test
    void aSuccessfulRetrievalCallsTheModelBeforeTheDatabase() {
        this.searchPort.willReturn(List.of(RecordingRetrievalPorts.match(FIRST_DOCUMENT, 2, 0.87)));

        this.service.retrieve(new RetrieveKnowledgeQuery("VPN", 3, 0.5));

        assertThat(this.ports.callOrder()).containsExactly("query", "search");
        assertThat(this.queryPort.descriptors()).containsExactly(DESCRIPTOR);
        assertThat(this.searchPort.topKs()).containsExactly(3);
        assertThat(this.searchPort.minScores()).containsExactly(0.5);
    }

    @Test
    void aProviderFailureKeepsItsOwnErrorCodeAndDoesNotLeakTheQuery() {
        this.queryPort.failWith(new KnowledgeApplicationException(
                KnowledgeApplicationErrorCode.EMBEDDING_PROVIDER_ERROR, "查询向量服务调用失败"));

        KnowledgeApplicationException thrown = (KnowledgeApplicationException) catchThrowable(
                () -> this.service.retrieve(new RetrieveKnowledgeQuery("SENTINEL-VPN-QUERY", null, null)));

        assertThat(thrown.errorCode()).isEqualTo(KnowledgeApplicationErrorCode.EMBEDDING_PROVIDER_ERROR);
        assertThat(thrown.getMessage()).doesNotContain("SENTINEL-VPN-QUERY");
        assertThat(this.searchPort.calls()).as("模型失败后不得访问向量表").isZero();
    }

    @Test
    void aSearchFailureIsPropagatedWithoutLeakingTheQuery() {
        KnowledgeApplicationException portFailure = new KnowledgeApplicationException(
                KnowledgeApplicationErrorCode.KNOWLEDGE_RETRIEVAL_FAILURE, "向量检索失败");
        this.searchPort.failWith(portFailure);

        KnowledgeApplicationException thrown = (KnowledgeApplicationException) catchThrowable(
                () -> this.service.retrieve(new RetrieveKnowledgeQuery("SENTINEL-VPN-QUERY", null, null)));

        assertThat(thrown).as("契约内的 KNOWLEDGE_RETRIEVAL_FAILURE 必须原样上抛（同一实例），不二次包装")
                .isSameAs(portFailure);
        assertThat(thrown.errorCode()).isEqualTo(KnowledgeApplicationErrorCode.KNOWLEDGE_RETRIEVAL_FAILURE);
        assertThat(thrown.getMessage()).doesNotContain("SENTINEL-VPN-QUERY");
    }

    /**
     * 检索端口抛出<b>契约之外的任何错误码</b>都必须被收敛为 {@code KNOWLEDGE_RETRIEVAL_FAILURE}。
     *
     * <p>用 {@link EnumSource} 覆盖<b>当前与将来</b>的全部错误码（仅排除检索端口唯一允许的
     * {@code KNOWLEDGE_RETRIEVAL_FAILURE}）：新增错误码时，这条参数化测试会自动把它纳入，
     * 从而锁死「端口违约不能穿透到 HTTP 层」的行为。</p>
     *
     * @param errorCode 端口违约时抛出的错误码
     */
    @ParameterizedTest(name = "端口违约错误码 {0} 必须收敛为 KNOWLEDGE_RETRIEVAL_FAILURE")
    @EnumSource(value = KnowledgeApplicationErrorCode.class,
            names = "KNOWLEDGE_RETRIEVAL_FAILURE", mode = EnumSource.Mode.EXCLUDE)
    void anOutOfContractErrorCodeFromTheSearchPortIsCollapsedIntoARetrievalFailure(
            KnowledgeApplicationErrorCode errorCode) {

        String sentinel = "SENTINEL-PORT-DETAIL-绝不外泄";
        KnowledgeApplicationException portFailure = new KnowledgeApplicationException(errorCode, sentinel);
        this.searchPort.failWith(portFailure);

        KnowledgeApplicationException thrown = (KnowledgeApplicationException) catchThrowable(
                () -> this.service.retrieve(new RetrieveKnowledgeQuery("SENTINEL-VPN-QUERY", null, null)));

        assertThat(thrown.errorCode())
                .as("端口违约只能表现为 KNOWLEDGE_RETRIEVAL_FAILURE（HTTP 500），不能是 400/404/502/503")
                .isEqualTo(KnowledgeApplicationErrorCode.KNOWLEDGE_RETRIEVAL_FAILURE);
        assertThat(thrown).isNotSameAs(portFailure);
        assertThat(thrown.getCause()).as("原异常必须作为 cause 保留").isSameAs(portFailure);
        assertThat(thrown.getMessage())
                .as("对外消息固定且不含原异常消息、query 或其它敏感信息")
                .contains("向量检索")
                .doesNotContain(sentinel)
                .doesNotContain("SENTINEL-VPN-QUERY")
                .doesNotContain(errorCode.name());
    }

    // ---------- 查询向量校验 ----------

    @Test
    void aNullQueryVectorIsReportedAsARetrievalFailure() {
        this.queryPort.willReturnNull();

        assertRetrievalFailure(() -> this.service.retrieve(new RetrieveKnowledgeQuery("VPN", null, null)));
        assertThat(this.searchPort.calls()).isZero();
    }

    @Test
    void anInvalidQueryVectorIsReportedAsARetrievalFailure() {
        for (float[] vector : new float[][] { new float[1023], new float[1024], allNaN() }) {
            this.queryPort.willReturn(vector);
            assertRetrievalFailure(() -> this.service.retrieve(new RetrieveKnowledgeQuery("VPN", null, null)));
        }
        assertThat(this.searchPort.calls()).isZero();
    }

    // ---------- 结果契约 ----------

    @Test
    void anEmptyResultIsStillASuccess() {
        this.searchPort.willReturn(List.of());

        KnowledgeRetrievalView view = this.service.retrieve(new RetrieveKnowledgeQuery("VPN", null, null));

        assertThat(view.citations()).isEmpty();
        assertThat(view.provider()).isEqualTo("dashscope");
        assertThat(view.model()).isEqualTo("text-embedding-v4");
        assertThat(view.dimensions()).isEqualTo(1024);
        assertThat(view.topK()).isEqualTo(DEFAULT_TOP_K);
        assertThat(view.minScore()).isEqualTo(DEFAULT_MIN_SCORE);
    }

    @Test
    void citationsAreNumberedInTheOrderReturned() {
        this.searchPort.willReturn(List.of(
                RecordingRetrievalPorts.match(FIRST_DOCUMENT, 2, 0.87),
                RecordingRetrievalPorts.match(SECOND_DOCUMENT, 0, 0.62)));

        KnowledgeRetrievalView view = this.service.retrieve(new RetrieveKnowledgeQuery("VPN", null, null));

        assertThat(view.citations()).hasSize(2);
        assertThat(view.citations().get(0).citationId()).isEqualTo("K1");
        assertThat(view.citations().get(0).rank()).isEqualTo(1);
        assertThat(view.citations().get(0).documentTitle()).isEqualTo("VPN 故障处理手册");
        assertThat(view.citations().get(0).chunkIndex()).isEqualTo(2);
        assertThat(view.citations().get(0).score()).isEqualTo(0.87);
        assertThat(view.citations().get(0).chunkSha256()).hasSize(64);
        assertThat(view.citations().get(1).citationId()).isEqualTo("K2");
        assertThat(view.citations().get(1).rank()).isEqualTo(2);
    }

    @Test
    void unicodeTitlesAndContentsRoundTripThroughTheView() {
        this.searchPort.willReturn(List.of(RecordingRetrievalPorts.match(FIRST_DOCUMENT, 1, 0.9,
                "VPN 故障处理手册", "第一步：检查隧道状态 \uD83D\uDE00")));

        KnowledgeRetrievalView view = this.service.retrieve(new RetrieveKnowledgeQuery("VPN", null, null));

        assertThat(view.citations().get(0).documentTitle()).isEqualTo("VPN 故障处理手册");
        assertThat(view.citations().get(0).content()).isEqualTo("第一步：检查隧道状态 \uD83D\uDE00");
    }

    @Test
    void rejectsMoreResultsThanTopK() {
        this.searchPort.willReturn(List.of(
                RecordingRetrievalPorts.match(FIRST_DOCUMENT, 0, 0.9),
                RecordingRetrievalPorts.match(SECOND_DOCUMENT, 0, 0.8)));

        assertRetrievalFailure(() -> this.service.retrieve(new RetrieveKnowledgeQuery("VPN", 1, null)));
    }

    @Test
    void rejectsScoresBelowTheEffectiveThreshold() {
        this.searchPort.willReturn(List.of(RecordingRetrievalPorts.match(FIRST_DOCUMENT, 0, 0.29)));

        assertRetrievalFailure(() -> this.service.retrieve(new RetrieveKnowledgeQuery("VPN", null, 0.30)));
    }

    @Test
    void rejectsNonFiniteOrOutOfRangeScores() {
        for (double score : new double[] { Double.NaN, Double.POSITIVE_INFINITY, -0.01, 1.01 }) {
            this.searchPort.willReturn(List.of(RecordingRetrievalPorts.match(FIRST_DOCUMENT, 0, score)));
            assertRetrievalFailure(() -> this.service.retrieve(new RetrieveKnowledgeQuery("VPN", null, 0.0)));
        }
    }

    @Test
    void rejectsUnsortedResultsInsteadOfSilentlySortingThem() {
        // 端口返回 0.5 之后又返回 0.9：顺序违反契约，必须失败而不是被重排
        this.searchPort.willReturn(List.of(
                RecordingRetrievalPorts.match(FIRST_DOCUMENT, 0, 0.5),
                RecordingRetrievalPorts.match(SECOND_DOCUMENT, 0, 0.9)));

        assertRetrievalFailure(() -> this.service.retrieve(new RetrieveKnowledgeQuery("VPN", null, 0.0)));
    }

    @Test
    void rejectsTiesThatAreNotOrderedByDocumentThenChunk() {
        // 同分时必须按 documentId 升序，再按 chunkIndex 升序
        this.searchPort.willReturn(List.of(
                RecordingRetrievalPorts.match(SECOND_DOCUMENT, 0, 0.7),
                RecordingRetrievalPorts.match(FIRST_DOCUMENT, 0, 0.7)));
        assertRetrievalFailure(() -> this.service.retrieve(new RetrieveKnowledgeQuery("VPN", null, 0.0)));

        this.searchPort.willReturn(List.of(
                RecordingRetrievalPorts.match(FIRST_DOCUMENT, 3, 0.7),
                RecordingRetrievalPorts.match(FIRST_DOCUMENT, 1, 0.7)));
        assertRetrievalFailure(() -> this.service.retrieve(new RetrieveKnowledgeQuery("VPN", null, 0.0)));

        // 正确的 tie-break 顺序必须通过
        this.searchPort.willReturn(List.of(
                RecordingRetrievalPorts.match(FIRST_DOCUMENT, 1, 0.7),
                RecordingRetrievalPorts.match(FIRST_DOCUMENT, 3, 0.7),
                RecordingRetrievalPorts.match(SECOND_DOCUMENT, 0, 0.7)));
        assertThat(this.service.retrieve(new RetrieveKnowledgeQuery("VPN", null, 0.0)).citations())
                .hasSize(3);
    }

    @Test
    void rejectsDuplicateDocumentAndChunkPairs() {
        this.searchPort.willReturn(List.of(
                RecordingRetrievalPorts.match(FIRST_DOCUMENT, 2, 0.9),
                RecordingRetrievalPorts.match(FIRST_DOCUMENT, 2, 0.8)));

        assertRetrievalFailure(() -> this.service.retrieve(new RetrieveKnowledgeQuery("VPN", null, 0.0)));
    }

    @Test
    void rejectsNullElementsAndMissingFields() {
        List<KnowledgeVectorMatch> withNull = new ArrayList<>();
        withNull.add(null);
        this.searchPort.willReturn(withNull);
        assertRetrievalFailure(() -> this.service.retrieve(new RetrieveKnowledgeQuery("VPN", null, 0.0)));

        this.searchPort.willReturn(List.of(new KnowledgeVectorMatch(null, 1L, "标题", 0,
                Sha256Digest.of("0123456789abcdef".repeat(4)), "正文", 0.9)));
        assertRetrievalFailure(() -> this.service.retrieve(new RetrieveKnowledgeQuery("VPN", null, 0.0)));

        this.searchPort.willReturn(List.of(new KnowledgeVectorMatch(KnowledgeDocumentId.of(FIRST_DOCUMENT), 1L,
                "  ", 0, Sha256Digest.of("0123456789abcdef".repeat(4)), "正文", 0.9)));
        assertRetrievalFailure(() -> this.service.retrieve(new RetrieveKnowledgeQuery("VPN", null, 0.0)));

        this.searchPort.willReturn(List.of(new KnowledgeVectorMatch(KnowledgeDocumentId.of(FIRST_DOCUMENT), 1L,
                "标题", 0, Sha256Digest.of("0123456789abcdef".repeat(4)), " ", 0.9)));
        assertRetrievalFailure(() -> this.service.retrieve(new RetrieveKnowledgeQuery("VPN", null, 0.0)));

        this.searchPort.willReturn(List.of(new KnowledgeVectorMatch(KnowledgeDocumentId.of(FIRST_DOCUMENT), 1L,
                "标题", -1, Sha256Digest.of("0123456789abcdef".repeat(4)), "正文", 0.9)));
        assertRetrievalFailure(() -> this.service.retrieve(new RetrieveKnowledgeQuery("VPN", null, 0.0)));

        this.searchPort.willReturn(List.of(new KnowledgeVectorMatch(KnowledgeDocumentId.of(FIRST_DOCUMENT), -1L,
                "标题", 0, Sha256Digest.of("0123456789abcdef".repeat(4)), "正文", 0.9)));
        assertRetrievalFailure(() -> this.service.retrieve(new RetrieveKnowledgeQuery("VPN", null, 0.0)));
    }

    @Test
    void aNullResultListFromThePortIsReportedAsARetrievalFailure() {
        this.searchPort.willReturnNull();

        assertRetrievalFailure(() -> this.service.retrieve(new RetrieveKnowledgeQuery("VPN", null, null)));
    }

    // ---------- 构造期配置校验 ----------

    @Test
    void rejectsInconsistentRetrievalConfigurationAtConstruction() {
        assertThatThrownBy(() -> new KnowledgeRetrievalService(this.queryPort, this.searchPort, true, DESCRIPTOR,
                0, DEFAULT_TOP_K, MAX_TOP_K, DEFAULT_MIN_SCORE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxQueryCodePoints");
        assertThatThrownBy(() -> new KnowledgeRetrievalService(this.queryPort, this.searchPort, true, DESCRIPTOR,
                MAX_QUERY_CODE_POINTS + 1, DEFAULT_TOP_K, MAX_TOP_K, DEFAULT_MIN_SCORE))
                .as("query 上限超过公开契约的 2000 必须被拒绝")
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxQueryCodePoints");
        assertThatThrownBy(() -> new KnowledgeRetrievalService(this.queryPort, this.searchPort, true, DESCRIPTOR,
                MAX_QUERY_CODE_POINTS, 0, MAX_TOP_K, DEFAULT_MIN_SCORE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("defaultTopK");
        assertThatThrownBy(() -> new KnowledgeRetrievalService(this.queryPort, this.searchPort, true, DESCRIPTOR,
                MAX_QUERY_CODE_POINTS, 6, 5, DEFAULT_MIN_SCORE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("defaultTopK");
        assertThatThrownBy(() -> new KnowledgeRetrievalService(this.queryPort, this.searchPort, true, DESCRIPTOR,
                MAX_QUERY_CODE_POINTS, DEFAULT_TOP_K, MAX_TOP_K + 1, DEFAULT_MIN_SCORE))
                .as("topK 上限超过公开契约的 20 必须被拒绝")
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxTopK");
        assertThatThrownBy(() -> new KnowledgeRetrievalService(this.queryPort, this.searchPort, true, DESCRIPTOR,
                MAX_QUERY_CODE_POINTS, DEFAULT_TOP_K, MAX_TOP_K, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("defaultMinScore");
    }

    @Test
    void theConstructorRejectsLimitsBeyondThePublicContractEvenWhenSpringIsBypassed() {
        // FD-0011-R1：硬上限写在用例构造器里，而不只依赖 Spring 配置类的 validate()
        assertThatThrownBy(() -> new KnowledgeRetrievalService(this.queryPort, this.searchPort, true, DESCRIPTOR,
                2001, 5, 20, 0.30))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxQueryCodePoints")
                .hasMessageContaining("2000");
        assertThatThrownBy(() -> new KnowledgeRetrievalService(this.queryPort, this.searchPort, true, DESCRIPTOR,
                2000, 5, 21, 0.30))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxTopK")
                .hasMessageContaining("20");
    }

    @Test
    void aTightenedConfigurationStillRejectsBeyondTheTightenedLimits() {
        // 配置收紧之后，请求必须受收紧后的限制约束（而不是回到公开上限）
        KnowledgeRetrievalService tightened = new KnowledgeRetrievalService(this.queryPort, this.searchPort, true,
                DESCRIPTOR, 10, 2, 3, 0.5);

        // 未提供 topK / minScore：用收紧后的默认值 2 / 0.5
        tightened.retrieve(new RetrieveKnowledgeQuery("a".repeat(10), null, null));
        assertThat(this.queryPort.queries()).containsExactly("a".repeat(10));
        assertThat(this.searchPort.topKs()).containsExactly(2);
        assertThat(this.searchPort.minScores()).containsExactly(0.5);

        // 收紧后的上限 3 是合法边界
        tightened.retrieve(new RetrieveKnowledgeQuery("abc", 3, null));
        assertThat(this.searchPort.topKs()).containsExactly(2, 3);

        // 超过收紧后的上限 / query 上限一律 400
        assertApplicationError(() -> tightened.retrieve(new RetrieveKnowledgeQuery("a".repeat(11), 3, 0.5)),
                KnowledgeApplicationErrorCode.INVALID_RETRIEVAL_QUERY);
        assertApplicationError(() -> tightened.retrieve(new RetrieveKnowledgeQuery("abc", 4, 0.5)),
                KnowledgeApplicationErrorCode.INVALID_RETRIEVAL_QUERY);

        // 显式传入的 minScore 只要在 0..1 内就合法（阈值是请求参数，不是相对默认值的约束）
        tightened.retrieve(new RetrieveKnowledgeQuery("abc", 1, 0.1));
        assertThat(this.searchPort.minScores()).as("未提供的两次用默认值 0.5，显式传入的用 0.1")
                .containsExactly(0.5, 0.5, 0.1);
    }

    @Test
    void thePublicContractBoundariesAreExactlyInclusive() {
        // query 恰好 2000 个 code point、topK=20 都是合法边界（公开契约只允许到 20）
        this.service.retrieve(new RetrieveKnowledgeQuery("a".repeat(2000), 20, null));
        assertThat(this.searchPort.topKs()).containsExactly(20);

        assertApplicationError(() -> this.service.retrieve(new RetrieveKnowledgeQuery("a".repeat(2001), 20, null)),
                KnowledgeApplicationErrorCode.INVALID_RETRIEVAL_QUERY);
        assertApplicationError(() -> this.service.retrieve(new RetrieveKnowledgeQuery("a".repeat(2000), 21, null)),
                KnowledgeApplicationErrorCode.INVALID_RETRIEVAL_QUERY);
    }

    @Test
    void aDomainExceptionLeakingFromTheSearchPortBecomesARetrievalFailure() {
        // FD-0011-R1：行映射阶段的领域异常若泄漏到应用层，必须收敛为 500 而不是 400
        this.searchPort.failWith(new com.flowdesk.domain.knowledge.KnowledgeDomainException(
                com.flowdesk.domain.knowledge.KnowledgeErrorCode.INVALID_VECTOR, "摘要不合法"));

        KnowledgeApplicationException thrown = (KnowledgeApplicationException) catchThrowable(
                () -> this.service.retrieve(new RetrieveKnowledgeQuery("VPN", null, null)));

        assertThat(thrown.errorCode()).as("不能落进 400 的领域异常映射")
                .isEqualTo(KnowledgeApplicationErrorCode.KNOWLEDGE_RETRIEVAL_FAILURE);
        assertThat(thrown.getCause()).isInstanceOf(
                com.flowdesk.domain.knowledge.KnowledgeDomainException.class);
    }

    @Test
    void anUnexpectedRuntimeExceptionFromTheSearchPortBecomesARetrievalFailure() {
        this.searchPort.failWith(new IllegalStateException("结果集已关闭"));

        assertApplicationError(() -> this.service.retrieve(new RetrieveKnowledgeQuery("VPN", null, null)),
                KnowledgeApplicationErrorCode.KNOWLEDGE_RETRIEVAL_FAILURE);
    }

    // ---------- 辅助 ----------

    private void assertInvalidQuery(RetrieveKnowledgeQuery query) {
        assertApplicationError(() -> this.service.retrieve(query),
                KnowledgeApplicationErrorCode.INVALID_RETRIEVAL_QUERY);
    }

    private void assertRetrievalFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertApplicationError(callable, KnowledgeApplicationErrorCode.KNOWLEDGE_RETRIEVAL_FAILURE);
    }

    private static void assertApplicationError(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable,
            KnowledgeApplicationErrorCode expected) {

        assertThatThrownBy(callable)
                .isInstanceOf(KnowledgeApplicationException.class)
                .extracting(thrown -> ((KnowledgeApplicationException) thrown).errorCode())
                .isEqualTo(expected);
    }

    private static float[] allNaN() {
        float[] vector = new float[1024];
        java.util.Arrays.fill(vector, Float.NaN);
        return vector;
    }
}
