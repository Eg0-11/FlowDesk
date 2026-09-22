package com.flowdesk.agent.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowdesk.application.ai.KnowledgeEvidence;
import com.flowdesk.application.ai.KnowledgeFailure;
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
 * 组合引用校验（FD-0018-A）：K1..Kn、A1、M1 的规范形态、族完整性与去重。
 */
class IncidentTriageCitationValidatorTests {

    private static final AssetQueryResult ASSET_FOUND = AssetQueryResult.found(
            new AssetView("AST-900001", "SERVER", "IN_SERVICE", SourceOrigin.DEMO));

    private static final AssetQueryResult ASSET_NOT_FOUND = AssetQueryResult.notFound("AST-900001",
            SourceOrigin.DEMO);

    private static final MonitoringSnapshotQueryResult MONITORING_FOUND = MonitoringSnapshotQueryResult.found(
            new MonitoringSnapshotView("AST-900001", Instant.parse("2026-01-01T00:00:00Z"), HealthState.DEGRADED,
                    92, 68, 1, SourceOrigin.DEMO));

    private static final MonitoringSnapshotQueryResult MONITORING_FAILED =
            MonitoringSnapshotQueryResult.failed(QueryFailure.UNAVAILABLE);

    @Test
    void aCanonicalTripleIsAcceptedInFirstAppearanceOrderAndDeduplicated() {
        assertThat(validate("影响 [M1]，现象 [K2]，资产在保 [A1]，再强调 [K2]。", 2, ASSET_FOUND, MONITORING_FOUND))
                .containsExactly("M1", "K2", "A1");
    }

    @Test
    void oneKnowledgeCitationIsEnoughForTheKnowledgeFamily() {
        assertThat(validate("只引用第二条 [K2]。", 2, ASSET_NOT_FOUND, MONITORING_FAILED))
                .containsExactly("K2");
    }

    @Test
    void anEmptyAnswerFails() {
        for (String answer : new String[] { null, "", "   ", "\n" }) {
            assertFailure(answer, 2, ASSET_FOUND, MONITORING_FOUND, IncidentTriageFailure.ANSWER_EMPTY);
        }
    }

    @Test
    void anAnswerWithoutAnyCitationFails() {
        assertFailure("该资产目前运行正常，没有需要处理的问题。", 2, ASSET_FOUND, MONITORING_FOUND,
                IncidentTriageFailure.ANSWER_WITHOUT_CITATION);
    }

    @Test
    void malformedReferencesFailWithoutBeingRepaired() {
        String[] malformed = {
                "结论 [k1]。", "结论 [K01]。", "结论 [K 1]。", "结论 [K1 ]。", "结论 [ K1]。",
                "结论 [K1x]。", "结论 [K0]。", "结论 [K]。", "结论 [K1,A1]。", "结论 [K-1]。",
                "结论 [K+1]。", "结论 [K1。", "结论 [K１]。", "结论 [A]。", "结论 [A01]。",
                "结论 [A2]。", "结论 [M2]。", "结论 [M]。", "结论 [A1 ]。", "结论 [A１]。"
        };
        for (String answer : malformed) {
            assertFailure(answer, 2, ASSET_FOUND, MONITORING_FOUND,
                    IncidentTriageFailure.INVALID_CITATION_FORMAT);
        }
    }

    @Test
    void referencesToEvidenceThatIsNotAvailableFail() {
        assertFailure("引用了一个不存在的切片 [K9]。", 2, ASSET_NOT_FOUND, MONITORING_FAILED,
                IncidentTriageFailure.UNKNOWN_CITATION);
        assertFailure("资产不可用却引用 [A1]。", 0, AssetQueryResult.failed(QueryFailure.UNAVAILABLE),
                MONITORING_FAILED, IncidentTriageFailure.UNKNOWN_CITATION);
        assertFailure("监控不可用却引用 [M1]。", 0, ASSET_NOT_FOUND, MONITORING_FAILED,
                IncidentTriageFailure.UNKNOWN_CITATION);
    }

    @Test
    void everyAvailableFamilyMustBeCitedAtLeastOnce() {
        assertFailure("只谈资产 [A1]，监控也正常 [M1]。", 2, ASSET_FOUND, MONITORING_FOUND,
                IncidentTriageFailure.EVIDENCE_FAMILY_NOT_CITED);
        assertFailure("只谈知识 [K1]。", 1, ASSET_FOUND, MONITORING_FAILED,
                IncidentTriageFailure.EVIDENCE_FAMILY_NOT_CITED);
        assertFailure("只谈知识 [K1]。", 1, ASSET_NOT_FOUND, MONITORING_FOUND,
                IncidentTriageFailure.EVIDENCE_FAMILY_NOT_CITED);
    }

    @Test
    void ordinaryBracketedWordsAreNotCitations() {
        assertFailure("接口 [API] 与地址 [MAC] 都正常。", 0, ASSET_NOT_FOUND, MONITORING_FAILED,
                IncidentTriageFailure.ANSWER_WITHOUT_CITATION);
        assertThat(validate("接口 [API] 正常 [K1]，地址 [MAC] 正常 [A1]。", 1, ASSET_FOUND, MONITORING_FAILED))
                .containsExactly("K1", "A1");
    }

    @Test
    void nestedBracketsAreRejectedInsteadOfBeingUnwrapped() {
        String[] nested = {
                "结论 [[A1]]。", "结论 [[M1]]。", "结论 [[K1]]。", "结论 [[K2]]。",
                "结论 [ [M1] ]。", "结论 [[K1]。",
                "正常 [K1]，嵌套 [[A1]]，监控 [M1]。",
                "嵌套 [[A1]] 在前，正常 [K1] 与 [M1] 在后。",
                "正常 [M1]，嵌套 [[K1]]，资产 [A1]。"
        };
        for (String answer : nested) {
            assertFailure(answer, 2, ASSET_FOUND, MONITORING_FOUND,
                    IncidentTriageFailure.INVALID_CITATION_FORMAT);
        }
    }

    @Test
    void nestedBracketsWithoutCitationIntentRemainPlainText() {
        // 既有规则：完整 ASCII 字母方括号词不是引用；被再包一层也仍然不是引用意图
        assertFailure("接口 [[API]] 与地址 [[MAC]] 都正常。", 0, ASSET_NOT_FOUND, MONITORING_FAILED,
                IncidentTriageFailure.ANSWER_WITHOUT_CITATION);
        assertThat(validate("接口 [[API]] 正常 [K1]，资产 [A1]，监控 [M1]。", 1, ASSET_FOUND, MONITORING_FOUND))
                .containsExactly("K1", "A1", "M1");
    }

    @Test
    void aStrayClosingBracketOutsideAnyGroupIsPlainText() {
        // 记录的边界：只有「方括号组内部」参与解释，组外多余的右括号是普通文本
        assertThat(validate("结论 [K1]，资产 [A1]，监控 [M1]]。", 1, ASSET_FOUND, MONITORING_FOUND))
                .containsExactly("K1", "A1", "M1");
    }

    @Test
    void aKnowledgeFailureLeavesNoKnowledgeFamilyToCite() {
        KnowledgeEvidence failed = KnowledgeEvidence.failed(KnowledgeFailure.DISABLED);

        assertThat(IncidentTriageCitationValidator.requireValidCitations("资产在保 [A1]。", failed, ASSET_FOUND,
                MONITORING_FAILED)).containsExactly("A1");
        assertThatThrownBy(() -> IncidentTriageCitationValidator.requireValidCitations("引用知识 [K1]。", failed,
                ASSET_FOUND, MONITORING_FAILED))
                .isInstanceOfSatisfying(IncidentTriageException.class,
                        ex -> assertThat(ex.failure()).isEqualTo(IncidentTriageFailure.UNKNOWN_CITATION));
    }

    private static List<String> validate(String answer, int knowledgeCitations, AssetQueryResult asset,
            MonitoringSnapshotQueryResult monitoring) {
        return IncidentTriageCitationValidator.requireValidCitations(answer,
                knowledge(knowledgeCitations), asset, monitoring);
    }

    private static void assertFailure(String answer, int knowledgeCitations, AssetQueryResult asset,
            MonitoringSnapshotQueryResult monitoring, IncidentTriageFailure expected) {

        assertThatThrownBy(() -> validate(answer, knowledgeCitations, asset, monitoring))
                .as("answer=[%s]", answer)
                .isInstanceOfSatisfying(IncidentTriageException.class,
                        ex -> assertThat(ex.failure()).isEqualTo(expected));
    }

    private static KnowledgeEvidence knowledge(int citationCount) {
        List<KnowledgeCitationView> citations = new ArrayList<>();
        for (int index = 1; index <= citationCount; index++) {
            citations.add(KnowledgeCitationView.vectorOnly("K" + index, index,
                    UUID.fromString("11111111-2222-3333-4444-555555555555"), 1L, "手册", index - 1,
                    "0".repeat(64), "正文 " + index, 0.9));
        }
        KnowledgeRetrievalView view = KnowledgeRetrievalView.vectorOrdered("dashscope", "text-embedding-v4", 1024,
                5, 0.3, List.copyOf(citations));
        return citationCount == 0 ? KnowledgeEvidence.notFound(view) : KnowledgeEvidence.found(view);
    }
}
