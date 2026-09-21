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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 资产诊断结果的不变量（FD-0017-A）。
 *
 * <p>这个记录是调用方唯一能看到的结果形状，因此「哪些组合不可能存在」必须在构造期拒绝：
 * 引用了本次没有的证据、{@code grounded} 与引用集合不一致 —— 都不应该等到调用方去猜。</p>
 */
class AssetDiagnosisResultTests {

    private static final AssetQueryResult ASSET_FOUND = AssetQueryResult.found(
            new AssetView("AST-900001", "SERVER", "IN_SERVICE", SourceOrigin.DEMO));

    private static final AssetQueryResult ASSET_NOT_FOUND = AssetQueryResult.notFound("AST-900001",
            SourceOrigin.DEMO);

    private static final AssetQueryResult ASSET_FAILED = AssetQueryResult.failed(QueryFailure.UNAVAILABLE);

    private static final MonitoringSnapshotQueryResult MONITORING_FOUND = MonitoringSnapshotQueryResult.found(
            new MonitoringSnapshotView("AST-900001", Instant.parse("2026-01-01T00:00:00Z"), HealthState.DEGRADED,
                    92, 68, 1, SourceOrigin.DEMO));

    private static final MonitoringSnapshotQueryResult MONITORING_NOT_FOUND =
            MonitoringSnapshotQueryResult.notFound("AST-900001", SourceOrigin.DEMO);

    private static final MonitoringSnapshotQueryResult MONITORING_FAILED =
            MonitoringSnapshotQueryResult.failed(QueryFailure.DISABLED);

    @Test
    void aFullyGroundedResultCarriesBothEvidenceIdsAndBothRealStates() {
        AssetDiagnosisResult result = new AssetDiagnosisResult("req-1", "结论 [A1]，风险 [M1]。", true,
                List.of("A1", "M1"), ASSET_FOUND, MONITORING_FOUND);

        assertThat(result.grounded()).isTrue();
        assertThat(result.usedEvidenceIds()).containsExactly("A1", "M1");
        assertThat(result.evidenceCount()).isEqualTo(2);
        assertThat(result.usedEvidenceCount()).isEqualTo(2);
        assertThat(result.asset().requireAsset().status()).isEqualTo("IN_SERVICE");
        assertThat(result.monitoring().requireSnapshot().cpuUtilizationPercent()).isEqualTo(92);
    }

    @Test
    void aPartiallyGroundedResultKeepsTheOtherSideRealState() {
        AssetDiagnosisResult result = new AssetDiagnosisResult("req-2", "资产在保 [A1]。", true, List.of("A1"),
                ASSET_FOUND, MONITORING_FAILED);

        assertThat(result.usedEvidenceIds()).containsExactly("A1");
        assertThat(result.evidenceCount()).as("命中证据只有资产一条").isEqualTo(1);
        assertThat(result.monitoring().outcome()).as("另一侧的真实状态必须保留")
                .isEqualTo(com.flowdesk.application.integration.QueryOutcome.FAILED);
        assertThat(result.monitoring().failure()).isEqualTo(QueryFailure.DISABLED);
    }

    @Test
    void aDegradedResultHasNoEvidenceIdsAtAll() {
        AssetDiagnosisResult result = new AssetDiagnosisResult("req-3", AssetDiagnosisResultText.NO_RECORD, false,
                List.of(), ASSET_NOT_FOUND, MONITORING_NOT_FOUND);

        assertThat(result.grounded()).isFalse();
        assertThat(result.usedEvidenceIds()).isEmpty();
        assertThat(result.evidenceCount()).isZero();
        assertThat(result.usedEvidenceCount()).isZero();
    }

    @Test
    void theEvidenceIdListIsDefensivelyCopied() {
        List<String> mutable = new ArrayList<>(List.of("A1"));
        AssetDiagnosisResult result = new AssetDiagnosisResult("req-4", "结论 [A1]。", true, mutable,
                ASSET_FOUND, MONITORING_NOT_FOUND);

        mutable.add("M1");

        assertThat(result.usedEvidenceIds()).as("外部改动原列表不得影响结果").containsExactly("A1");
        assertThatThrownBy(() -> result.usedEvidenceIds().add("M1"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void requiredFieldsMustNotBeNull() {
        assertThatThrownBy(() -> new AssetDiagnosisResult(null, "x", false, List.of(), ASSET_NOT_FOUND,
                MONITORING_NOT_FOUND)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AssetDiagnosisResult("req", null, false, List.of(), ASSET_NOT_FOUND,
                MONITORING_NOT_FOUND)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AssetDiagnosisResult("req", "x", false, null, ASSET_NOT_FOUND,
                MONITORING_NOT_FOUND)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AssetDiagnosisResult("req", "x", false, List.of(), null,
                MONITORING_NOT_FOUND)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AssetDiagnosisResult("req", "x", false, List.of(), ASSET_NOT_FOUND,
                null)).isInstanceOf(NullPointerException.class);
    }

    // ---------- FD-0017-B：requestId / answer 不得只有空白 ----------

    @Test
    void blankRequestIdOrAnswerIsRejected() {
        for (String blank : new String[] { "", " ", "   ", "\t", "\n", " \t\n " }) {
            assertThatThrownBy(() -> new AssetDiagnosisResult(blank, "x [A1]。", false, List.of(), ASSET_NOT_FOUND,
                    MONITORING_NOT_FOUND))
                    .as("requestId=[%s]", blank)
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> new AssetDiagnosisResult("req-1", blank, false, List.of(), ASSET_NOT_FOUND,
                    MONITORING_NOT_FOUND))
                    .as("answer=[%s]", blank)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void aRequestIdWithSurroundingWhitespaceIsStillAccepted() {
        // 只有「全是空白」才被拒绝：本类不做 trim，也不改写调用方给出的标识与答案
        AssetDiagnosisResult result = new AssetDiagnosisResult(" req-1 ", " x [A1]。 ", true, List.of("A1"),
                ASSET_FOUND, MONITORING_NOT_FOUND);

        assertThat(result.requestId()).isEqualTo(" req-1 ");
        assertThat(result.answer()).isEqualTo(" x [A1]。 ");
    }

    // ---------- FD-0017-B：编号必须已按首次出现顺序去重 ----------

    @Test
    void theEvidenceIdListMustAlreadyBeDeduplicated() {
        assertThatThrownBy(() -> new AssetDiagnosisResult("req", "x [A1]。", true, List.of("A1", "A1"), ASSET_FOUND,
                MONITORING_NOT_FOUND))
                .as("重复的 A1")
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new AssetDiagnosisResult("req", "x [A1][M1]。", true, List.of("A1", "M1", "A1"),
                ASSET_FOUND, MONITORING_FOUND))
                .as("三元素里含重复")
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new AssetDiagnosisResult("req", "x。", false, List.of("M1", "M1"), ASSET_NOT_FOUND,
                MONITORING_FOUND))
                .as("即使 grounded=false 也先拒绝未去重的编号")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aDeduplicatedListKeepsItsFirstOccurrenceOrder() {
        // 去重由产出方负责，本类只校验顺序与集合：M1 在前就应当在结果里排在前
        AssetDiagnosisResult result = new AssetDiagnosisResult("req", "风险 [M1]，资产 [A1]。", true,
                List.of("M1", "A1"), ASSET_FOUND, MONITORING_FOUND);

        assertThat(result.usedEvidenceIds()).containsExactly("M1", "A1");
    }

    @Test
    void onlyA1AndM1MayAppear() {
        for (String unknown : new String[] { "A2", "M2", "K1", "a1", "m1", "", "A1 ", " A1" }) {
            assertThatThrownBy(() -> new AssetDiagnosisResult("req", "x [A1]。", true, List.of(unknown),
                    ASSET_FOUND, MONITORING_FOUND))
                    .as("evidenceId=[%s]", unknown)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void anEvidenceIdRequiresThatSideToBeFound() {
        assertThatThrownBy(() -> new AssetDiagnosisResult("req", "x [A1]。", true, List.of("A1"), ASSET_NOT_FOUND,
                MONITORING_NOT_FOUND))
                .as("资产未命中却引用 A1")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssetDiagnosisResult("req", "x [M1]。", true, List.of("M1"), ASSET_NOT_FOUND,
                MONITORING_NOT_FOUND))
                .as("监控未命中却引用 M1")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void groundedMustMatchTheCompleteSetOfFoundEvidence() {
        assertThatThrownBy(() -> new AssetDiagnosisResult("req", "x [A1]。", true, List.of("A1"), ASSET_FOUND,
                MONITORING_FOUND))
                .as("两条证据都命中却只引用一条")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssetDiagnosisResult("req", "x。", true, List.of(), ASSET_FOUND,
                MONITORING_FOUND))
                .as("grounded=true 却没有引用")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssetDiagnosisResult("req", "x。", true, List.of(), ASSET_NOT_FOUND,
                MONITORING_NOT_FOUND))
                .as("没有命中证据却声称 grounded")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUngroundedResultMustNotCarryEvidenceIds() {
        assertThatThrownBy(() -> new AssetDiagnosisResult("req", "x [A1]。", false, List.of("A1"), ASSET_FOUND,
                MONITORING_NOT_FOUND))
                .as("grounded=false 不得携带引用")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theFoundEvidenceIdsHelperMatchesTheRecordStates() {
        assertThat(AssetDiagnosisResult.foundEvidenceIds(ASSET_FOUND, MONITORING_FOUND))
                .containsExactly("A1", "M1");
        assertThat(AssetDiagnosisResult.foundEvidenceIds(ASSET_FOUND, MONITORING_NOT_FOUND))
                .containsExactly("A1");
        assertThat(AssetDiagnosisResult.foundEvidenceIds(ASSET_FAILED, MONITORING_FAILED)).isEmpty();
        assertThat(AssetDiagnosisResult.foundEvidenceIds(null, null)).isEmpty();
    }

    /** 固定文案取值（与服务实现保持一致，测试只用于可读性）。 */
    private static final class AssetDiagnosisResultText {

        private static final String NO_RECORD = "未查询到该资产或可用的监控快照。";
    }
}
