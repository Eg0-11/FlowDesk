package com.flowdesk.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.flowdesk.application.knowledge.port.out.KnowledgeRerankResult;
import com.flowdesk.application.knowledge.query.RetrieveKnowledgeQuery;
import com.flowdesk.application.knowledge.service.KnowledgeRetrievalService;
import com.flowdesk.application.knowledge.view.KnowledgeCitationView;
import com.flowdesk.application.knowledge.view.KnowledgeRankingMode;
import com.flowdesk.application.knowledge.view.KnowledgeRetrievalView;
import com.flowdesk.domain.knowledge.EmbeddingDescriptor;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 检索用例的可选重排测试（RAG 6/6）。
 *
 * <p>这一层是重排的<b>唯一</b>实现位置：检索接口与问答接口都调用同一个用例服务，
 * 因此「两个接口拿到同一份最终排序」不是靠约定，而是结构上只有一条路径。</p>
 *
 * <p>覆盖四类契约：</p>
 * <ol>
 *   <li><b>何时调用</b>：关闭时不调用；候选 0/1 条时不调用（避免无意义的付费调用）；</li>
 *   <li><b>怎么排</b>：按重排分降序、同分保持原向量排名、按最终顺序重新编号，
 *       且 {@code score} 仍是向量分不被覆盖；</li>
 *   <li><b>发什么</b>：只发送规范化后的 query 与候选正文（不含标识、摘要、向量）；</li>
 *   <li><b>失败怎么办</b>：下标/分数违约一律失败且<b>不</b>退回向量排序；上游失败是 502，
 *       端口违约是 500。</li>
 * </ol>
 */
class KnowledgeRetrievalRerankTest {

    private static final EmbeddingDescriptor DESCRIPTOR =
            EmbeddingDescriptor.of("dashscope", "text-embedding-v4");

    private static final UUID DOCUMENT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID DOCUMENT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID DOCUMENT_C = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final String RERANK_MODEL = "qwen3-rerank";

    private RecordingRetrievalPorts ports;

    private RecordingRetrievalPorts.RecordingQueryEmbeddingPort queryPort;

    private RecordingRetrievalPorts.RecordingVectorSearchPort searchPort;

    private RecordingRetrievalPorts.RecordingRerankPort rerankPort;

    private KnowledgeRetrievalService service;

    @BeforeEach
    void setUp() {
        this.ports = new RecordingRetrievalPorts();
        this.queryPort = this.ports.new RecordingQueryEmbeddingPort();
        this.searchPort = this.ports.new RecordingVectorSearchPort();
        this.rerankPort = this.ports.new RecordingRerankPort();
        this.queryPort.willReturn(RecordingRetrievalPorts.vector(0.5f));
        // 默认两条候选：多数用例关心的是「重排被调用之后会发生什么」，
        // 需要别的候选集合（0 条、1 条、3 条或违约响应）的用例自行覆盖
        this.searchPort.willReturn(List.of(
                match(DOCUMENT_A, 0, 0.9, "chunk-a"),
                match(DOCUMENT_B, 1, 0.8, "chunk-b")));
        this.service = service(true);
    }

    private KnowledgeRetrievalService service(boolean rerankEnabled) {
        return new KnowledgeRetrievalService(this.queryPort, this.searchPort, this.rerankPort, true,
                rerankEnabled, rerankEnabled ? RERANK_MODEL : null, DESCRIPTOR, 2000, 5, 20, 0.30);
    }

    // ---------- 正常重排 ----------

    @Test
    void reordersCandidatesByRerankScoreAndRenumbersCitations() {
        // 向量顺序 A、B、C（分数降序），重排分让顺序变成 C、A、B
        this.searchPort.willReturn(List.of(
                match(DOCUMENT_A, 0, 0.9, "chunk-a"),
                match(DOCUMENT_B, 1, 0.8, "chunk-b"),
                match(DOCUMENT_C, 2, 0.7, "chunk-c")));
        this.rerankPort.willReturnScores(0.4, 0.1, 0.95);

        KnowledgeRetrievalView view = this.service.retrieve(new RetrieveKnowledgeQuery("VPN", null, null));

        assertThat(view.rankingMode()).isEqualTo(KnowledgeRankingMode.RERANK);
        assertThat(view.rerankModel()).isEqualTo(RERANK_MODEL);

        // K1 指向 C、K2 指向 A、K3 指向 B
        assertThat(view.citations()).extracting(KnowledgeCitationView::citationId)
                .containsExactly("K1", "K2", "K3");
        assertThat(view.citations()).extracting(citation -> citation.documentId())
                .containsExactly(DOCUMENT_C, DOCUMENT_A, DOCUMENT_B);
        assertThat(view.citations()).extracting(KnowledgeCitationView::rank)
                .containsExactly(1, 2, 3);
        assertThat(view.citations()).extracting(KnowledgeCitationView::content)
                .containsExactly("chunk-c", "chunk-a", "chunk-b");

        // 重排分随引用一起返回
        assertThat(view.citations()).extracting(KnowledgeCitationView::rerankScore)
                .containsExactly(0.95, 0.4, 0.1);

        // 向量分没有被重排分覆盖：K1（C）仍然是它自己的向量分 0.7
        assertThat(view.citations()).extracting(KnowledgeCitationView::score)
                .containsExactly(0.7, 0.9, 0.8);
    }

    @Test
    void keepsTheOriginalVectorOrderForEqualRerankScores() {
        this.searchPort.willReturn(List.of(
                match(DOCUMENT_A, 0, 0.9, "chunk-a"),
                match(DOCUMENT_B, 1, 0.8, "chunk-b"),
                match(DOCUMENT_C, 2, 0.7, "chunk-c")));
        // 三条分数完全相同：必须保持原向量排名，保证确定性
        this.rerankPort.willReturnScores(0.5, 0.5, 0.5);

        KnowledgeRetrievalView view = this.service.retrieve(new RetrieveKnowledgeQuery("VPN", null, null));

        assertThat(view.rankingMode()).isEqualTo(KnowledgeRankingMode.RERANK);
        assertThat(view.citations()).extracting(citation -> citation.documentId())
                .containsExactly(DOCUMENT_A, DOCUMENT_B, DOCUMENT_C);
        assertThat(view.citations()).extracting(KnowledgeCitationView::rerankScore)
                .containsExactly(0.5, 0.5, 0.5);
    }

    @Test
    void bindsScoresByOriginalIndexNotByResponseOrder() {
        this.searchPort.willReturn(List.of(
                match(DOCUMENT_A, 0, 0.9, "chunk-a"),
                match(DOCUMENT_B, 1, 0.8, "chunk-b")));
        // 上游按分数降序返回（下标 1 在前）：绑定必须按 index，而不是按到达顺序
        this.rerankPort.willReturn(List.of(
                new KnowledgeRerankResult(1, 0.9),
                new KnowledgeRerankResult(0, 0.2)));

        KnowledgeRetrievalView view = this.service.retrieve(new RetrieveKnowledgeQuery("VPN", null, null));

        assertThat(view.citations()).extracting(citation -> citation.documentId())
                .containsExactly(DOCUMENT_B, DOCUMENT_A);
        assertThat(view.citations()).extracting(KnowledgeCitationView::rerankScore)
                .containsExactly(0.9, 0.2);
        assertThat(view.citations()).extracting(KnowledgeCitationView::score)
                .containsExactly(0.8, 0.9);
    }

    // ---------- 何时不调用 ----------

    @Test
    void doesNotCallRerankWhenThereAreNoMatches() {
        this.searchPort.willReturn(List.of());

        KnowledgeRetrievalView view = this.service.retrieve(new RetrieveKnowledgeQuery("VPN", null, null));

        assertThat(this.rerankPort.calls()).as("无命中时不得调用重排（也不得调用模型）").isZero();
        assertThat(this.ports.callOrder()).containsExactly("query", "search");
        assertThat(view.rankingMode()).isEqualTo(KnowledgeRankingMode.VECTOR_SIMILARITY);
        assertThat(view.rerankModel()).isNull();
        assertThat(view.citations()).isEmpty();
    }

    @Test
    void doesNotCallRerankWhenThereIsOnlyOneCandidate() {
        this.searchPort.willReturn(List.of(match(DOCUMENT_A, 0, 0.9, "chunk-a")));

        KnowledgeRetrievalView view = this.service.retrieve(new RetrieveKnowledgeQuery("VPN", null, null));

        assertThat(this.rerankPort.calls())
                .as("只有一个候选时重排不可能改变顺序，不产生付费调用")
                .isZero();
        assertThat(view.rankingMode()).as("如实标明本次顺序由向量相似度决定")
                .isEqualTo(KnowledgeRankingMode.VECTOR_SIMILARITY);
        assertThat(view.rerankModel()).isNull();
        assertThat(view.citations()).extracting(KnowledgeCitationView::rerankScore).containsOnlyNulls();
        assertThat(view.citations()).extracting(KnowledgeCitationView::citationId).containsExactly("K1");
    }

    @Test
    void whenRerankIsDisabledTheVectorOrderIsReturnedUnchanged() {
        this.service = service(false);
        this.searchPort.willReturn(List.of(
                match(DOCUMENT_A, 0, 0.9, "chunk-a"),
                match(DOCUMENT_B, 1, 0.8, "chunk-b")));

        KnowledgeRetrievalView view = this.service.retrieve(new RetrieveKnowledgeQuery("VPN", null, null));

        assertThat(this.rerankPort.calls()).isZero();
        assertThat(view.rankingMode()).isEqualTo(KnowledgeRankingMode.VECTOR_SIMILARITY);
        assertThat(view.citations()).extracting(citation -> citation.documentId())
                .containsExactly(DOCUMENT_A, DOCUMENT_B);
    }

    // ---------- 发送内容 ----------

    @Test
    void sendsOnlyTheNormalizedQueryAndCandidateContents() {
        this.searchPort.willReturn(List.of(
                match(DOCUMENT_A, 0, 0.9, "chunk-a"),
                match(DOCUMENT_B, 1, 0.8, "chunk-b")));
        this.rerankPort.willReturnScores(0.7, 0.3);

        this.service.retrieve(new RetrieveKnowledgeQuery("  VPN 无法连接  ", null, null));

        assertThat(this.rerankPort.queries())
                .as("重排拿到的问题与查询向量端口逐字符相同")
                .containsExactly("VPN 无法连接");
        assertThat(this.queryPort.queries()).containsExactly("VPN 无法连接");
        assertThat(this.rerankPort.documents())
                .as("只发送候选正文，顺序即向量顺序")
                .containsExactly(List.of("chunk-a", "chunk-b"));
        assertThat(this.ports.callOrder()).as("固定时序：模型 → 数据库 → 重排")
                .containsExactly("query", "search", "rerank");
    }

    // ---------- 结果契约：下标与分数 ----------

    @Test
    void rejectsResultsThatDoNotCoverEveryCandidate() {
        // 候选 2 条，只返回 1 条
        assertRerankContractFailure(List.of(new KnowledgeRerankResult(0, 0.5)));

        // 候选 2 条，返回 3 条
        assertRerankContractFailure(List.of(
                new KnowledgeRerankResult(0, 0.5),
                new KnowledgeRerankResult(1, 0.4),
                new KnowledgeRerankResult(1, 0.3)));
    }

    @Test
    void rejectsIllegalIndices() {
        assertRerankContractFailure(List.of(
                new KnowledgeRerankResult(null, 0.5),
                new KnowledgeRerankResult(1, 0.4)));
        assertRerankContractFailure(List.of(
                new KnowledgeRerankResult(-1, 0.5),
                new KnowledgeRerankResult(1, 0.4)));
        assertRerankContractFailure(List.of(
                new KnowledgeRerankResult(0, 0.5),
                new KnowledgeRerankResult(2, 0.4)));
        // 重复下标：一个候选被打了两次分，另一个没有
        assertRerankContractFailure(List.of(
                new KnowledgeRerankResult(0, 0.5),
                new KnowledgeRerankResult(0, 0.4)));
    }

    @Test
    void rejectsIllegalScores() {
        assertRerankContractFailure(List.of(
                new KnowledgeRerankResult(0, null),
                new KnowledgeRerankResult(1, 0.4)));
        assertRerankContractFailure(List.of(
                new KnowledgeRerankResult(0, Double.NaN),
                new KnowledgeRerankResult(1, 0.4)));
        assertRerankContractFailure(List.of(
                new KnowledgeRerankResult(0, Double.POSITIVE_INFINITY),
                new KnowledgeRerankResult(1, 0.4)));
        assertRerankContractFailure(List.of(
                new KnowledgeRerankResult(0, -0.01),
                new KnowledgeRerankResult(1, 0.4)));
        assertRerankContractFailure(List.of(
                new KnowledgeRerankResult(0, 1.01),
                new KnowledgeRerankResult(1, 0.4)));
    }

    @Test
    void rejectsANullResultListAndNullEntries() {
        assertRerankContractFailure(null);
        assertRerankContractFailure(java.util.Arrays.asList(new KnowledgeRerankResult(0, 0.5), null));
    }

    // ---------- 失败：不静默降级 ----------

    @Test
    void anUpstreamRerankFailureIsPropagatedAndNoOrderIsReturned() {
        KnowledgeApplicationException failure = new KnowledgeApplicationException(
                KnowledgeApplicationErrorCode.RERANK_PROVIDER_ERROR, "重排服务调用失败");
        this.rerankPort.failWith(failure);

        Throwable thrown = catchThrowable(
                () -> this.service.retrieve(new RetrieveKnowledgeQuery("VPN", null, null)));

        assertThat(thrown).as("上游失败原样上抛（502），不得退回向量排序").isSameAs(failure);
    }

    @Test
    void anOutOfContractErrorCodeFromTheRerankPortBecomesAnInternalFailure() {
        this.rerankPort.failWith(new KnowledgeApplicationException(
                KnowledgeApplicationErrorCode.INVALID_RETRIEVAL_QUERY, "端口写错了错误码"));

        Throwable thrown = catchThrowable(
                () -> this.service.retrieve(new RetrieveKnowledgeQuery("VPN", null, null)));

        assertThat(thrown).isInstanceOf(KnowledgeApplicationException.class);
        assertThat(((KnowledgeApplicationException) thrown).errorCode())
                .as("端口违约必须收敛为内部失败（500），不能表现为 400")
                .isEqualTo(KnowledgeApplicationErrorCode.KNOWLEDGE_RETRIEVAL_FAILURE);
    }

    @Test
    void anUnexpectedRuntimeExceptionFromTheRerankPortBecomesAnInternalFailure() {
        this.rerankPort.failWith(new IllegalStateException("连接池已关闭 sentinel"));

        Throwable thrown = catchThrowable(
                () -> this.service.retrieve(new RetrieveKnowledgeQuery("VPN", null, null)));

        assertThat(thrown).isInstanceOf(KnowledgeApplicationException.class);
        assertThat(((KnowledgeApplicationException) thrown).errorCode())
                .isEqualTo(KnowledgeApplicationErrorCode.KNOWLEDGE_RETRIEVAL_FAILURE);
    }

    // ---------- 构造期 ----------

    @Test
    void enablingRerankWithoutAModelIsRejectedAtConstruction() {
        assertThat(catchThrowable(() -> new KnowledgeRetrievalService(this.queryPort, this.searchPort,
                this.rerankPort, true, true, "  ", DESCRIPTOR, 2000, 5, 20, 0.30)))
                .as("启用重排却没有模型标识会让顺序无从审计")
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- 辅助 ----------

    private void assertRerankContractFailure(List<KnowledgeRerankResult> results) {
        this.searchPort.willReturn(List.of(
                match(DOCUMENT_A, 0, 0.9, "chunk-a"),
                match(DOCUMENT_B, 1, 0.8, "chunk-b")));
        this.rerankPort.willReturn(results);

        Throwable thrown = catchThrowable(
                () -> this.service.retrieve(new RetrieveKnowledgeQuery("VPN", null, null)));

        assertThat(thrown).as("results=%s", results).isInstanceOf(KnowledgeApplicationException.class);
        assertThat(((KnowledgeApplicationException) thrown).errorCode())
                .as("results=%s", results)
                .isEqualTo(KnowledgeApplicationErrorCode.KNOWLEDGE_RETRIEVAL_FAILURE);
    }

    private static com.flowdesk.application.knowledge.port.out.KnowledgeVectorMatch match(UUID documentId,
            int chunkIndex, double score, String content) {

        return RecordingRetrievalPorts.match(documentId, chunkIndex, score, "VPN 故障处理手册", content);
    }
}
