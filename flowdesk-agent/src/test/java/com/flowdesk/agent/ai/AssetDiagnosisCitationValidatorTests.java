package com.flowdesk.agent.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 诊断引用校验（FD-0017-A）：只认 {@code [A1]} 与 {@code [M1]}，不做任何修正。
 */
class AssetDiagnosisCitationValidatorTests {

    private static final List<String> BOTH = List.of("A1", "M1");

    private static final List<String> ONLY_ASSET = List.of("A1");

    @Test
    void aCanonicalPairIsAcceptedAndDeduplicatedInFirstAppearanceOrder() {
        assertThat(AssetDiagnosisCitationValidator.requireValidEvidenceCitations(
                "风险 [M1]，现状 [A1]，再次强调 [M1]。", BOTH))
                .as("按首次出现顺序去重")
                .containsExactly("M1", "A1");
    }

    @Test
    void aSingleEvidenceIdIsAcceptedWhenOnlyOneSideWasFound() {
        assertThat(AssetDiagnosisCitationValidator.requireValidEvidenceCitations("结论 [A1]。", ONLY_ASSET))
                .containsExactly("A1");
    }

    @Test
    void anEmptyOrBlankAnswerFails() {
        for (String answer : new String[] { null, "", "   ", "\n" }) {
            assertFailure(answer, BOTH, AssetDiagnosisFailure.ANSWER_EMPTY);
        }
    }

    @Test
    void anAnswerWithoutAnyCitationFails() {
        assertFailure("该资产目前运行正常，没有需要处理的问题。", BOTH,
                AssetDiagnosisFailure.ANSWER_WITHOUT_CITATION);
    }

    @Test
    void malformedReferencesFailWithoutBeingRepaired() {
        String[] malformed = {
                "结论 [a1]。", "结论 [m1]。", "结论 [A01]。", "结论 [A 1]。", "结论 [A1 ]。",
                "结论 [ A1]。", "结论 [A1x]。", "结论 [A2]。", "结论 [M2]。", "结论 [A]。", "结论 [M]。",
                "结论 [A-1]。", "结论 [A+1]。", "结论 [A1,M1]。", "结论 [A1。", "结论 [A１]。"
        };
        for (String answer : malformed) {
            assertFailure(answer, BOTH, AssetDiagnosisFailure.INVALID_CITATION_FORMAT);
        }
    }

    @Test
    void aCanonicalReferenceToEvidenceThatWasNotProvidedFails() {
        assertFailure("监控显示正常 [M1]。", ONLY_ASSET, AssetDiagnosisFailure.UNKNOWN_CITATION);
    }

    @Test
    void everyFoundEvidenceMustBeCitedAtLeastOnce() {
        assertFailure("只谈资产 [A1]。", BOTH, AssetDiagnosisFailure.EVIDENCE_NOT_CITED);
    }

    @Test
    void ordinaryBracketedWordsAreNotCitations() {
        assertFailure("接口 [API] 与地址 [MAC] 都正常。", BOTH,
                AssetDiagnosisFailure.ANSWER_WITHOUT_CITATION);

        assertThat(AssetDiagnosisCitationValidator.requireValidEvidenceCitations(
                "接口 [API] 正常 [A1]，地址 [MAC] 正常 [M1]。", BOTH))
                .containsExactly("A1", "M1");
    }

    private static void assertFailure(String answer, List<String> allowedEvidenceIds,
            AssetDiagnosisFailure expected) {

        assertThatThrownBy(() -> AssetDiagnosisCitationValidator.requireValidEvidenceCitations(answer,
                allowedEvidenceIds))
                .as("answer=[%s]", answer)
                .isInstanceOfSatisfying(AssetDiagnosisException.class,
                        ex -> assertThat(ex.failure()).isEqualTo(expected));
    }
}
