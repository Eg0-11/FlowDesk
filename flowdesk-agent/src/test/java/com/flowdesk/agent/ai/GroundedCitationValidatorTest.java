package com.flowdesk.agent.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 答案引用校验的单元测试（RAG 5/6）。
 *
 * <p>这层校验决定了「一次作答能否作为成功返回」：它<b>不修正</b>模型输出，只做判定。
 * 因此这里的重点是三件事：</p>
 * <ol>
 *   <li>合法引用按首次出现顺序去重返回；</li>
 *   <li><b>所有</b>畸形引用形态都被拒绝 —— 包括与合法引用混在同一句话里的情况
 *       （FD-0012-R1：早期实现只匹配 {@code \[K(\d*)\]}，漏检了 {@code [K-1]}、{@code [K1a]}、
 *       {@code [K 2]}、{@code [k9]} 这类写法）；</li>
 *   <li>形式合法但本次没给出的编号仍然归类为未知引用。</li>
 * </ol>
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
        // [Known] 里 K 后面是 ASCII 字母：不构成引用意图，按普通英文方括号词处理
        List<String> used = GroundedCitationValidator.requireValidCitations(
                "[Known] 只是正文里的一个方括号词，真正的引用在这里 [K1]。", ALLOWED);

        assertThat(used).containsExactly("K1");
    }

    @Test
    void treatsOtherBracketedProseAsPlainText() {
        assertThat(GroundedCitationValidator.requireValidCitations(
                "注意 [注意] 这里的方括号不是引用，真正的引用是 [K2]。", ALLOWED))
                .containsExactly("K2");
    }

    @Test
    void doesNotAddTheCitationsTheModelForgotToUse() {
        // 证据里有三条，答案只引用了一条：不得自动补齐成三条 —— 「答案用了什么」必须来自答案本身
        assertThat(GroundedCitationValidator.requireValidCitations("只看这一条 [K1]。", ALLOWED))
                .containsExactly("K1");
    }

    // ---------- 畸形引用：逐个形态（FD-0012-R1）----------

    @ParameterizedTest
    @ValueSource(strings = {
            "[K]",          // 空的编号
            "[K0]",         // 0 不是正整数
            "[K01]",        // 前导零
            "[K-1]",        // 负号
            "[K+1]",        // 正号
            "[K 1]",        // 编号前有空格
            "[K1 ]",        // 编号后有空格
            "[K1a]",        // 编号后有多余字符
            "[K1,K2]",      // 一次写两个编号
            "[k1]",         // 小写 k
            "[K1"           // 未闭合
    })
    void rejectsEveryMalformedCitationShape(String malformed) {
        Throwable thrown = catchThrowable(
                () -> GroundedCitationValidator.requireValidCitations("结论 " + malformed + "。", ALLOWED));

        assertThat(thrown).as("malformed=[%s]", malformed).isInstanceOf(GroundedAnswerException.class);
        assertThat(((GroundedAnswerException) thrown).failure())
                .as("malformed=[%s]", malformed)
                .isEqualTo(GroundedAnswerFailure.INVALID_CITATION_FORMAT);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[K-1]",
            "[K1a]",
            "[K 2]",
            "[k9]",
            "[K0]",
            "[K]",
            "[K1,K2]",
            "[K1"
    })
    void aMalformedCitationNextToAValidOneStillFailsTheWholeAnswer(String malformed) {
        // 这正是 FD-0012 的验收缺口：合法引用与畸形引用混在一起时，整次答案仍然必须失败
        String answer = "合法 [K1]，伪造 " + malformed;

        Throwable thrown = catchThrowable(
                () -> GroundedCitationValidator.requireValidCitations(answer, ALLOWED));

        assertThat(thrown).as("answer=[%s]", answer).isInstanceOf(GroundedAnswerException.class);
        assertThat(((GroundedAnswerException) thrown).failure())
                .as("畸形引用不得被当成普通文字忽略")
                .isEqualTo(GroundedAnswerFailure.INVALID_CITATION_FORMAT);
    }

    @Test
    void rejectsMalformedCitationsEvenWhenNestedInsideAnotherBracket() {
        // [[K-1]]：如果只做非重叠匹配，内层的畸形引用会被跳过
        Throwable thrown = catchThrowable(
                () -> GroundedCitationValidator.requireValidCitations("结论 [K1] 参见 [[K-1]]。", ALLOWED));

        assertThat(thrown).isInstanceOf(GroundedAnswerException.class);
        assertThat(((GroundedAnswerException) thrown).failure())
                .isEqualTo(GroundedAnswerFailure.INVALID_CITATION_FORMAT);
    }

    @Test
    void rejectsAMalformedCitationThatAppearsBeforeAValidOne() {
        Throwable thrown = catchThrowable(
                () -> GroundedCitationValidator.requireValidCitations("伪造 [K-1] 合法 [K1]", ALLOWED));

        assertThat(thrown).isInstanceOf(GroundedAnswerException.class);
        assertThat(((GroundedAnswerException) thrown).failure())
                .isEqualTo(GroundedAnswerFailure.INVALID_CITATION_FORMAT);
    }

    // ---------- 未知引用 ----------

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
    void rejectsEveryCitationWhenNoEvidenceWasRetrieved() {
        Throwable thrown = catchThrowable(
                () -> GroundedCitationValidator.requireValidCitations("结论 [K1]。", List.of()));

        assertThat(thrown).isInstanceOf(GroundedAnswerException.class);
        assertThat(((GroundedAnswerException) thrown).failure())
                .as("没有任何证据时，一切引用都是未知引用")
                .isEqualTo(GroundedAnswerFailure.UNKNOWN_CITATION);
    }

    // ---------- 无引用 ----------

    @Test
    void rejectsAnAnswerWithoutAnyCitation() {
        for (String answer : List.of("结论是重启服务。", "[Known] 只是正文。", "正文里 (K1) 是圆括号。")) {
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
    void failsOnTheFirstProblemInsteadOfSilentlyRepairingTheRest() {
        // 先出现合法引用，随后出现畸形引用：整体仍然失败（不做局部修正）
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
