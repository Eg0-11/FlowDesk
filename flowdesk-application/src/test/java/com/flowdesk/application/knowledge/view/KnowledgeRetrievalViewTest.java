package com.flowdesk.application.knowledge.view;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 检索结果视图的证据快照契约（FD-0012-R1）。
 *
 * <p>问答链路会把「本次证据」当作审计依据：{@code usedCitationIds} 必须是它的子集。
 * 如果这个视图只是持有调用方传来的那个可变列表，那么「证据」在构造之后仍可能被改动 ——
 * 审计信息就建立在一份会变的材料上。因此不可变约束被放在视图的紧凑构造器里，
 * 检索与问答两条链路都自动获得同一份快照。</p>
 */
class KnowledgeRetrievalViewTest {

    private static final UUID DOCUMENT_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void takesAnImmutableSnapshotOfTheEvidence() {
        List<KnowledgeCitationView> mutable = new ArrayList<>();
        mutable.add(citation("K1"));

        KnowledgeRetrievalView view = view(mutable);

        mutable.add(citation("K2"));
        mutable.clear();

        assertThat(view.citations())
                .as("外部持有原列表也无法改变视图里的证据")
                .hasSize(1)
                .first()
                .extracting(KnowledgeCitationView::citationId)
                .isEqualTo("K1");
    }

    @Test
    void theEvidenceListItselfIsUnmodifiable() {
        KnowledgeRetrievalView view = view(new ArrayList<>(List.of(citation("K1"))));

        assertThatThrownBy(() -> view.citations().add(citation("K2")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> view.citations().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsANullEvidenceList() {
        assertThatThrownBy(() -> KnowledgeRetrievalView.vectorOrdered("dashscope", "text-embedding-v4",
                1024, 5, 0.30, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsANullElementInsideTheEvidence() {
        assertThatThrownBy(() -> view(Arrays.asList(citation("K1"), null)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void keepsAnEmptyEvidenceListAsAnEmptyImmutableList() {
        KnowledgeRetrievalView view = view(new ArrayList<>());

        assertThat(view.citations()).isEmpty();
        assertThatThrownBy(() -> view.citations().add(citation("K1")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ---------- 排序模式与重排分必须自洽（RAG 6/6）----------

    @Test
    void rejectsARerankModeWithoutAModelOrWithoutRerankScores() {
        assertThatThrownBy(() -> new KnowledgeRetrievalView("dashscope", "text-embedding-v4", 1024, 5, 0.30,
                KnowledgeRankingMode.RERANK, null, List.of(citation("K1"))))
                .as("声称经过重排却没给出重排模型")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeRetrievalView("dashscope", "text-embedding-v4", 1024, 5, 0.30,
                KnowledgeRankingMode.RERANK, "qwen3-rerank", List.of(citation("K1"))))
                .as("声称经过重排却没有重排分")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeRetrievalView("dashscope", "text-embedding-v4", 1024, 5, 0.30,
                KnowledgeRankingMode.VECTOR_SIMILARITY, "qwen3-rerank", List.of(citation("K1"))))
                .as("没有使用重排却给出重排模型")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsRerankScoresWhenTheModeIsVectorSimilarity() {
        KnowledgeCitationView withScore = new KnowledgeCitationView("K1", 1, DOCUMENT_ID, 4L, "标题", 0,
                "0".repeat(64), "正文", 0.9, 0.8);

        assertThatThrownBy(() -> KnowledgeRetrievalView.vectorOrdered("dashscope", "text-embedding-v4",
                1024, 5, 0.30, List.of(withScore)))
                .as("未使用重排时引用不得带重排分")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsANullRankingMode() {
        assertThatThrownBy(() -> new KnowledgeRetrievalView("dashscope", "text-embedding-v4", 1024, 5, 0.30,
                null, null, List.of(citation("K1"))))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void acceptsARerankViewWhereEveryCitationCarriesItsScore() {
        KnowledgeCitationView reranked = new KnowledgeCitationView("K1", 1, DOCUMENT_ID, 4L, "标题", 0,
                "0".repeat(64), "正文", 0.9, 0.8);

        KnowledgeRetrievalView view = new KnowledgeRetrievalView("dashscope", "text-embedding-v4", 1024, 5,
                0.30, KnowledgeRankingMode.RERANK, "qwen3-rerank", List.of(reranked));

        assertThat(view.rankingMode()).isEqualTo(KnowledgeRankingMode.RERANK);
        assertThat(view.rerankModel()).isEqualTo("qwen3-rerank");
        assertThat(view.citations()).extracting(KnowledgeCitationView::rerankScore).containsExactly(0.8);
        assertThat(view.citations()).extracting(KnowledgeCitationView::score)
                .as("向量分不被重排分覆盖").containsExactly(0.9);
    }

    // ---------- 辅助 ----------

    private static KnowledgeRetrievalView view(List<KnowledgeCitationView> citations) {
        return KnowledgeRetrievalView.vectorOrdered("dashscope", "text-embedding-v4", 1024, 5, 0.30, citations);
    }

    private static KnowledgeCitationView citation(String citationId) {
        return KnowledgeCitationView.vectorOnly(citationId, 1, DOCUMENT_ID, 4L, "VPN 故障处理手册", 0,
                "0".repeat(64), "正文", 0.9);
    }
}
