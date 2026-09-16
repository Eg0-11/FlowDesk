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
        assertThatThrownBy(() -> new KnowledgeRetrievalView("dashscope", "text-embedding-v4", 1024, 5, 0.30, null))
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

    // ---------- 辅助 ----------

    private static KnowledgeRetrievalView view(List<KnowledgeCitationView> citations) {
        return new KnowledgeRetrievalView("dashscope", "text-embedding-v4", 1024, 5, 0.30, citations);
    }

    private static KnowledgeCitationView citation(String citationId) {
        return new KnowledgeCitationView(citationId, 1, DOCUMENT_ID, 4L, "VPN 故障处理手册", 0,
                "0".repeat(64), "正文", 0.9);
    }
}
