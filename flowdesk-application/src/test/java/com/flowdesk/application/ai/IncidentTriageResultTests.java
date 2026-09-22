package com.flowdesk.application.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowdesk.application.integration.AssetQueryResult;
import com.flowdesk.application.integration.AssetView;
import com.flowdesk.application.integration.HealthState;
import com.flowdesk.application.integration.MonitoringSnapshotQueryResult;
import com.flowdesk.application.integration.MonitoringSnapshotView;
import com.flowdesk.application.integration.QueryFailure;
import com.flowdesk.application.integration.SourceOrigin;
import com.flowdesk.application.knowledge.view.KnowledgeCitationView;
import com.flowdesk.application.knowledge.view.KnowledgeRetrievalView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 事件研判结果与知识证据分支的不变量（FD-0018-A）。
 */
class IncidentTriageResultTests {

    private static final AssetQueryResult ASSET_FOUND = AssetQueryResult.found(
            new AssetView("AST-900001", "SERVER", "IN_SERVICE", SourceOrigin.DEMO));

    private static final AssetQueryResult ASSET_NOT_FOUND = AssetQueryResult.notFound("AST-900001",
            SourceOrigin.DEMO);

    private static final MonitoringSnapshotQueryResult MONITORING_FOUND = MonitoringSnapshotQueryResult.found(
            new MonitoringSnapshotView("AST-900001", Instant.parse("2026-01-01T00:00:00Z"), HealthState.DEGRADED,
                    92, 68, 1, SourceOrigin.DEMO));

    private static final MonitoringSnapshotQueryResult MONITORING_FAILED =
            MonitoringSnapshotQueryResult.failed(QueryFailure.UNAVAILABLE);

    private static final List<String> PATH = List.of("validate_asset", "retrieve_knowledge", "query_asset",
            "query_monitoring", "verify_contracts", "evidence_gate", "generate_answer", "validate_citations",
            "finish");

    @Test
    void aGroundedResultCarriesItsEvidencePathAndAnswer() {
        IncidentTriageResult result = new IncidentTriageResult("req-1", "现象 [K1] [A1] [M1]。", true,
                List.of("K1", "A1", "M1"), PATH, knowledge(2), ASSET_FOUND, MONITORING_FOUND);

        assertThat(result.grounded()).isTrue();
        assertThat(result.usedEvidenceIds()).containsExactly("K1", "A1", "M1");
        assertThat(result.evidenceCount()).as("知识两条 + 资产 + 监控").isEqualTo(4);
        assertThat(result.usedEvidenceCount()).isEqualTo(3);
        assertThat(result.executionPath()).containsExactlyElementsOf(PATH);
        assertThat(result.hasKnowledgeEvidence()).isTrue();
    }

    @Test
    void aFallbackResultIsNeverGroundedAndCarriesNoCitation() {
        IncidentTriageResult result = new IncidentTriageResult("req-2", "未找到可用于事件研判的资产、监控或知识证据。",
                false, List.of(), PATH, KnowledgeEvidence.notFound(retrieval(0)), ASSET_NOT_FOUND,
                MonitoringSnapshotQueryResult.failed(QueryFailure.DISABLED));

        assertThat(result.grounded()).isFalse();
        assertThat(result.usedEvidenceIds()).isEmpty();
        assertThat(result.monitoring().failure()).as("失败保持原义").isEqualTo(QueryFailure.DISABLED);
    }

    @Test
    void everyCollectionIsDefensivelyCopied() {
        List<String> used = new ArrayList<>(List.of("A1"));
        List<String> path = new ArrayList<>(PATH);
        IncidentTriageResult result = new IncidentTriageResult("req-3", "结论 [A1]。", true, used, path,
                KnowledgeEvidence.failed(KnowledgeFailure.DISABLED), ASSET_FOUND, MONITORING_FAILED);

        used.add("M1");
        path.clear();

        assertThat(result.usedEvidenceIds()).containsExactly("A1");
        assertThat(result.executionPath()).containsExactlyElementsOf(PATH);
        assertThatThrownBy(() -> result.usedEvidenceIds().add("M1"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void requestIdAnswerAndPathMustBePresent() {
        assertThatThrownBy(() -> new IncidentTriageResult(null, "x", false, List.of(), PATH, knowledge(0),
                ASSET_NOT_FOUND, MONITORING_FAILED)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IncidentTriageResult(" ", "x", false, List.of(), PATH, knowledge(0),
                ASSET_NOT_FOUND, MONITORING_FAILED)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IncidentTriageResult("req", " ", false, List.of(), PATH, knowledge(0),
                ASSET_NOT_FOUND, MONITORING_FAILED)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IncidentTriageResult("req", "x", false, List.of(), List.of(), knowledge(0),
                ASSET_NOT_FOUND, MONITORING_FAILED))
                .as("执行路径不能为空")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void citationsMustBeUniqueAndComeFromThisRun() {
        assertThatThrownBy(() -> new IncidentTriageResult("req", "x [A1]。", true, List.of("A1", "A1"), PATH,
                knowledge(0), ASSET_FOUND, MONITORING_FAILED))
                .as("引用不允许重复")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IncidentTriageResult("req", "x [A1]。", true, List.of("A1"), PATH,
                knowledge(0), ASSET_NOT_FOUND, MONITORING_FAILED))
                .as("资产未命中却引用 A1")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IncidentTriageResult("req", "x [K5]。", true, List.of("K5"), PATH,
                knowledge(2), ASSET_NOT_FOUND, MONITORING_FAILED))
                .as("引用了本次不存在的 K 编号")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IncidentTriageResult("req", "x [K1]。", true, List.of("K9"), PATH,
                knowledge(2), ASSET_NOT_FOUND, MONITORING_FAILED))
                .as("编号不在本次证据里")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void groundedMustMatchTheCitationSetExactly() {
        assertThatThrownBy(() -> new IncidentTriageResult("req", "x [K1]。", false, List.of("K1"), PATH,
                knowledge(2), ASSET_NOT_FOUND, MONITORING_FAILED))
                .as("有引用却声称 grounded=false")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IncidentTriageResult("req", "x。", true, List.of(), PATH, knowledge(2),
                ASSET_NOT_FOUND, MONITORING_FAILED))
                .as("声称 grounded=true 却没有引用")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theKnowledgeBranchHasThreeMutuallyExclusiveShapes() {
        assertThat(KnowledgeEvidence.found(retrieval(2)).status()).isEqualTo(KnowledgeEvidence.Status.FOUND);
        assertThat(KnowledgeEvidence.notFound(retrieval(0)).status())
                .isEqualTo(KnowledgeEvidence.Status.NOT_FOUND);
        assertThat(KnowledgeEvidence.failed(KnowledgeFailure.DISABLED).status())
                .isEqualTo(KnowledgeEvidence.Status.FAILED);

        assertThatThrownBy(() -> KnowledgeEvidence.found(retrieval(0)))
                .as("FOUND 不能没有切片")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KnowledgeEvidence.notFound(retrieval(2)))
                .as("NOT_FOUND 不能带着切片")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KnowledgeEvidence.failed(null))
                .as("FAILED 必须带失败分类")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeEvidence(KnowledgeEvidence.Status.FAILED, retrieval(0),
                KnowledgeFailure.DISABLED))
                .as("FAILED 不得携带检索视图")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theKnowledgeBranchReportsItsCitationIds() {
        assertThat(knowledge(2).citationIds()).containsExactly("K1", "K2");
        assertThat(KnowledgeEvidence.notFound(retrieval(0)).citationIds()).isEmpty();
        assertThat(KnowledgeEvidence.failed(KnowledgeFailure.DISABLED).citationIds()).isEmpty();
        assertThat(KnowledgeEvidence.failed(KnowledgeFailure.DISABLED).citations()).isEmpty();
    }

    @Test
    void theAvailableEvidenceSetFollowsTheBranchStates() {
        assertThat(IncidentTriageResult.availableEvidenceIds(knowledge(2), ASSET_FOUND, MONITORING_FOUND))
                .containsExactly("K1", "K2", "A1", "M1");
        assertThat(IncidentTriageResult.availableEvidenceIds(knowledge(0), ASSET_NOT_FOUND, MONITORING_FAILED))
                .isEmpty();
        assertThat(IncidentTriageResult.availableEvidenceIds(null, null, null)).isEmpty();
    }

    private static KnowledgeEvidence knowledge(int citationCount) {
        return citationCount == 0 ? KnowledgeEvidence.notFound(retrieval(0))
                : KnowledgeEvidence.found(retrieval(citationCount));
    }

    private static KnowledgeRetrievalView retrieval(int citationCount) {
        List<KnowledgeCitationView> citations = new ArrayList<>();
        for (int index = 1; index <= citationCount; index++) {
            citations.add(KnowledgeCitationView.vectorOnly("K" + index, index,
                    UUID.fromString("11111111-2222-3333-4444-555555555555"), 1L, "手册", index - 1,
                    "0".repeat(64), "正文 " + index, 0.9));
        }
        return KnowledgeRetrievalView.vectorOrdered("dashscope", "text-embedding-v4", 1024, 5, 0.3,
                List.copyOf(citations));
    }
}
