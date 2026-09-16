package com.flowdesk.application.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowdesk.application.knowledge.query.RetrieveKnowledgeQuery;
import com.flowdesk.application.knowledge.view.KnowledgeCitationView;
import com.flowdesk.application.knowledge.view.KnowledgeRetrievalView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 问答结果与命令的类型契约测试（RAG 5/6）。
 *
 * <p>结果类型里有一条关键不变量：「答案引用的编号必须是本次检索证据的子集」。
 * 把它放在构造期强制，而不是靠调用方自觉，意味着<b>「引用了一条本轮没给出的证据」
 * 这种结果在类型层面就无法构造出来</b>。</p>
 */
class KnowledgeAnswerResultTest {

    private static final UUID DOCUMENT_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    // ---------- 正常构造 ----------

    @Test
    void exposesTheRetrievedAndTheUsedCitationCountsSeparately() {
        KnowledgeAnswerResult result = result(retrieval("K1", "K2", "K3"), List.of("K2"));

        assertThat(result.requestId()).isEqualTo("req-1");
        assertThat(result.answer()).isEqualTo("答案 [K2]");
        assertThat(result.grounded()).isTrue();
        assertThat(result.usedCitationIds()).containsExactly("K2");
        assertThat(result.retrievedCitationCount()).isEqualTo(3);
        assertThat(result.usedCitationCount()).isEqualTo(1);
        assertThat(result.retrieval().citations()).hasSize(3);
    }

    @Test
    void acceptsTheNoEvidenceShape() {
        KnowledgeAnswerResult result = new KnowledgeAnswerResult("req-2", "当前知识库中没有足够证据回答该问题。",
                false, List.of(), retrieval());

        assertThat(result.grounded()).isFalse();
        assertThat(result.retrievedCitationCount()).isZero();
        assertThat(result.usedCitationCount()).isZero();
    }

    // ---------- 不变量 ----------

    @Test
    void defensivelyCopiesTheUsedCitationIds() {
        List<String> mutable = new ArrayList<>(List.of("K1"));
        KnowledgeAnswerResult result = result(retrieval("K1", "K2"), mutable);

        mutable.add("K2");
        mutable.clear();

        assertThat(result.usedCitationIds())
                .as("外部持有原列表也无法改变结果")
                .containsExactly("K1");
        assertThatThrownBy(() -> result.usedCitationIds().add("K2"))
                .as("结果里的列表本身不可变")
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsCitationIdsThatWereNotPartOfThisRetrieval() {
        assertThatThrownBy(() -> result(retrieval("K1", "K2"), List.of("K3")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("子集");
        assertThatThrownBy(() -> result(retrieval("K1"), List.of("K1", "K9")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> result(retrieval(), List.of("K1")))
                .as("没有任何证据时不可能引用任何编号")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullRequiredFields() {
        assertThatThrownBy(() -> new KnowledgeAnswerResult(null, "答案", true, List.of(), retrieval()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new KnowledgeAnswerResult("req", null, true, List.of(), retrieval()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new KnowledgeAnswerResult("req", "答案", true, null, retrieval()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new KnowledgeAnswerResult("req", "答案", true, List.of(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsANullElementInsideTheUsedCitationIds() {
        List<String> withNull = Arrays.asList("K1", null);

        assertThatThrownBy(() -> result(retrieval("K1"), withNull))
                .isInstanceOf(NullPointerException.class);
    }

    // ---------- 证据快照（FD-0012-R1）----------

    @Test
    void keepsAuditingAgainstAnImmutableEvidenceSnapshot() {
        // 证据来自一个外部可变列表：构造结果之后改动它，不得影响审计信息
        List<KnowledgeCitationView> mutableEvidence = new ArrayList<>();
        mutableEvidence.add(citation("K1"));

        KnowledgeAnswerResult result = result(retrieval(mutableEvidence), List.of("K1"));
        mutableEvidence.clear();
        mutableEvidence.add(citation("K9"));

        assertThat(result.retrieval().citations())
                .as("证据是构造期的快照，不随外部列表变化")
                .hasSize(1)
                .first()
                .extracting(KnowledgeCitationView::citationId)
                .isEqualTo("K1");
        assertThat(result.usedCitationIds())
                .as("usedCitationIds 仍是快照证据的子集")
                .containsExactly("K1");
        assertThatThrownBy(() -> result.retrieval().citations().add(citation("K2")))
                .as("返回的集合不可修改")
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.usedCitationIds().add("K2"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(result.retrievedCitationCount()).isEqualTo(1);
    }

    @Test
    void theSubsetInvariantIsCheckedAgainstTheEvidenceSnapshotAtConstructionTime() {
        // 子集校验依据的是构造那一刻的证据；之后把外部列表改成别的编号也不会让结果变「合法」
        List<KnowledgeCitationView> mutableEvidence = new ArrayList<>();
        mutableEvidence.add(citation("K1"));

        assertThatThrownBy(() -> result(retrieval(mutableEvidence), List.of("K9")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> result(retrieval(mutableEvidence), List.of("K2")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- 命令 ----------

    @Test
    void theCommandCarriesTheRawInputsToTheRetrievalQueryWithoutValidatingThem() {
        KnowledgeAnswerCommand command = new KnowledgeAnswerCommand("  VPN 无法连接  ", 7, 0.5);

        RetrieveKnowledgeQuery query = command.toRetrievalQuery();

        assertThat(query.query())
                .as("命令只透传原始值（不 strip / 不 NFC）：规范化由检索与问答共用的 KnowledgeQueryNormalizer 实现")
                .isEqualTo("  VPN 无法连接  ");
        assertThat(query.topK()).isEqualTo(7);
        assertThat(query.minScore()).isEqualTo(0.5);
    }

    @Test
    void theCommandKeepsNullsAsInheritTheServerDefaults() {
        RetrieveKnowledgeQuery query = new KnowledgeAnswerCommand(null, null, null).toRetrievalQuery();

        assertThat(query.query()).isNull();
        assertThat(query.topK()).isNull();
        assertThat(query.minScore()).isNull();
    }

    // ---------- 辅助 ----------

    private static KnowledgeAnswerResult result(KnowledgeRetrievalView retrieval, List<String> used) {
        return new KnowledgeAnswerResult("req-1", "答案 [K2]", true, used, retrieval);
    }

    private static KnowledgeRetrievalView retrieval(String... citationIds) {
        List<KnowledgeCitationView> citations = new ArrayList<>();
        int rank = 1;
        for (String citationId : citationIds) {
            citations.add(citation(citationId, rank++));
        }
        return new KnowledgeRetrievalView("dashscope", "text-embedding-v4", 1024, 5, 0.30, List.copyOf(citations));
    }

    /**
     * @param citations 由调用方持有的证据列表（可能是可变 {@code ArrayList}）
     * @return 证据视图
     */
    private static KnowledgeRetrievalView retrieval(List<KnowledgeCitationView> citations) {
        return new KnowledgeRetrievalView("dashscope", "text-embedding-v4", 1024, 5, 0.30, citations);
    }

    private static KnowledgeCitationView citation(String citationId) {
        return citation(citationId, 1);
    }

    private static KnowledgeCitationView citation(String citationId, int rank) {
        return new KnowledgeCitationView(citationId, rank, DOCUMENT_ID, 4L, "标题", rank, "0".repeat(64), "正文", 0.9);
    }
}
