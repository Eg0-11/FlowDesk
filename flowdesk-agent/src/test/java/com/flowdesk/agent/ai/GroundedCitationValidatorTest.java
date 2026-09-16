package com.flowdesk.agent.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 答案引用校验的单元测试（RAG 5/6）。
 *
 * <p>这层校验决定了「一次作答能否作为成功返回」：它<b>不修正</b>模型输出，
 * 只做判定。因此这里的重点是「非法与未知引用一律失败」以及「合法引用按首次出现顺序去重」。</p>
 */
class GroundedCitationValidatorTest {

    private static final List<String> ALLOWED = List.of("K1", "K2", "K3");

    // ---------- 合法 ----------

    @Test
    void acceptsCanonicalCitationsAndReturnsThemInFirstAppearanceOrder() {
        List<String> used = GroundedCitationValidator.requireValidCitations(
                "先检查隧道状态 [K2]，再确认账号状态 [K1]，最后回到隧道 [K2]。", ALLOWED);

        assertThat(used).as("按首次出现顺序去重").containsExactly("K2", "K1");
    }

    @Test
    void acceptsAnAnswerThatUsesEveryAllowedCitation() {
        assertThat(GroundedCitationValidator.requireValidCitations("[K1][K2][K3]", ALLOWED))
                .containsExactly("K1", "K2", "K3");
    }

    @Test
    void acceptsMultiDigitCitationIds() {
        List<String> allowed = new ArrayList<>(ALLOWED);
        allowed.add("K10");

        assertThat(GroundedCitationValidator.requireValidCitations("结论 [K10]", allowed))
                .containsExactly("K10");
    }

    @Test
    void treatsABracketedWordStartingWithKAsProseRatherThanACitation() {
        // [Known] 后面不是数字：刻意不当作引用，避免把正文里的方括号词误判
        List<String> used = GroundedCitationValidator.requireValidCitations(
                "[Known] 只是正文里的一个方括号词，真正的引用在这里 [K1]。", ALLOWED);

        assertThat(used).containsExactly("K1");
    }

    @Test
    void doesNotAddTheCitationsTheModelForgotToUse() {
        // 证据里有三条，答案只引用了一条：不得自动补齐成三条 —— 「答案用了什么」必须来自答案本身
        assertThat(GroundedCitationValidator.requireValidCitations("只看这一条 [K1]。", ALLOWED))
                .containsExactly("K1");
    }

    // ---------- 非法形式 ----------

    @Test
    void rejectsNonCanonicalCitationShapes() {
        for (String answer : List.of("结论 [K0]。", "结论 [K01]。", "结论 [K]。", "结论 [K1] 与 [K01]。")) {
            Throwable thrown = catchThrowable(
                    () -> GroundedCitationValidator.requireValidCitations(answer, ALLOWED));

            assertThat(thrown).as("answer=[%s]", answer).isInstanceOf(GroundedAnswerException.class);
            assertThat(((GroundedAnswerException) thrown).failure())
                    .as("answer=[%s]", answer)
                    .isEqualTo(GroundedAnswerFailure.INVALID_CITATION_FORMAT);
        }
    }

    @Test
    void rejectsUnknownCitationIdsEvenWhenTheShapeIsCanonical() {
        for (String answer : List.of("结论 [K9]。", "结论 [K1] 与 [K4]。", "结论 [K999]。")) {
            Throwable thrown = catchThrowable(
                    () -> GroundedCitationValidator.requireValidCitations(answer, ALLOWED));

            assertThat(thrown).as("answer=[%s]", answer).isInstanceOf(GroundedAnswerException.class);
            assertThat(((GroundedAnswerException) thrown).failure())
                    .as("形式规范但本次没有给出这个编号 —— 属于编造引用")
                    .isEqualTo(GroundedAnswerFailure.UNKNOWN_CITATION);
        }
    }

    @Test
    void rejectsAnAnswerWithoutAnyCitation() {
        for (String answer : List.of("结论是重启服务。", "[Known] 只是正文。", "结论 [k1] 小写不算引用。")) {
            Throwable thrown = catchThrowable(
                    () -> GroundedCitationValidator.requireValidCitations(answer, ALLOWED));

            assertThat(thrown).as("answer=[%s]", answer).isInstanceOf(GroundedAnswerException.class);
            assertThat(((GroundedAnswerException) thrown).failure())
                    .as("answer=[%s]", answer)
                    .isEqualTo(GroundedAnswerFailure.ANSWER_WITHOUT_CITATION);
        }
    }

    @Test
    void rejectsEmptyAnswers() {
        for (String answer : Arrays.asList(null, "", "   ", "\n\t ")) {
            Throwable thrown = catchThrowable(
                    () -> GroundedCitationValidator.requireValidCitations(answer, ALLOWED));

            assertThat(thrown).as("answer=[%s]", answer).isInstanceOf(GroundedAnswerException.class);
            assertThat(((GroundedAnswerException) thrown).failure())
                    .isEqualTo(GroundedAnswerFailure.ANSWER_EMPTY);
        }
    }

    @Test
    void rejectsEveryCitationWhenNoEvidenceWasRetrieved() {
        Throwable thrown = catchThrowable(
                () -> GroundedCitationValidator.requireValidCitations("结论 [K1]。", List.of()));

        assertThat(thrown).isInstanceOf(GroundedAnswerException.class);
        assertThat(((GroundedAnswerException) thrown).failure())
                .as("没有任何证据时，一切引用都是未知引用")
                .isEqualTo(GroundedAnswerFailure.UNKNOWN_CITATION);
    }

    @Test
    void failsOnTheFirstProblemInsteadOfSilentlyRepairingTheRest() {
        // 先出现合法引用，随后出现非法引用：整体仍然失败（不做局部修正）
        Throwable thrown = catchThrowable(
                () -> GroundedCitationValidator.requireValidCitations("[K1] 与 [K0]", ALLOWED));

        assertThat(thrown).isInstanceOf(GroundedAnswerException.class);
        assertThat(((GroundedAnswerException) thrown).failure())
                .isEqualTo(GroundedAnswerFailure.INVALID_CITATION_FORMAT);
    }

    // ---------- 失败类别与异常契约 ----------

    @ParameterizedTest
    @EnumSource(GroundedAnswerFailure.class)
    void everyFailureCategoryHasAStableNameAndASafeMessage(GroundedAnswerFailure failure) {
        GroundedAnswerException exception = new GroundedAnswerException(failure);

        assertThat(failure.name()).isNotBlank();
        assertThat(exception.failure()).isEqualTo(failure);
        assertThat(exception)
                .as("异常消息是固定服务端文案，不含答案或证据")
                .hasMessage("模型答案未通过引用校验：" + failure.name());
    }

    @Test
    void theFailureCategoryEnumIsExactlyTheDocumentedSet() {
        // MODEL_CALL_FAILED 由服务在「模型调用抛异常」时使用，不属于校验器的判定结果
        assertThat(GroundedAnswerFailure.values()).containsExactly(
                GroundedAnswerFailure.ANSWER_EMPTY,
                GroundedAnswerFailure.ANSWER_WITHOUT_CITATION,
                GroundedAnswerFailure.INVALID_CITATION_FORMAT,
                GroundedAnswerFailure.UNKNOWN_CITATION,
                GroundedAnswerFailure.MODEL_CALL_FAILED);
    }
}
