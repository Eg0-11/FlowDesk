package com.flowdesk.agent.ai;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 答案引用校验（RAG 5/6）：纯 Java、确定性、无框架依赖。
 *
 * <h2>规范形式</h2>
 * <p>只接受 {@code [K1]}、{@code [K2]}……即<b>大写 K + 不带前导零的正整数</b>。
 * 判定规则（对 {@code [K…]} 形状的记号逐个检查）：</p>
 * <table border="1">
 *   <caption>引用判定</caption>
 *   <tr><th>记号</th><th>结果</th></tr>
 *   <tr><td>{@code [K1]}、{@code [K12]}</td><td>规范；必须在本次证据集合内，否则
 *       {@link GroundedAnswerFailure#UNKNOWN_CITATION}</td></tr>
 *   <tr><td>{@code [K0]}、{@code [K01]}、{@code [K]}、{@code [K999]}</td>
 *       <td>前三个为{@link GroundedAnswerFailure#INVALID_CITATION_FORMAT}；
 *           {@code [K999]} 形式规范但不在证据集合内 → {@link GroundedAnswerFailure#UNKNOWN_CITATION}</td></tr>
 *   <tr><td>{@code [Known]}（K 后面不是数字）</td>
 *       <td>视为普通文本，不当作引用 —— 这一条是刻意的：只把「K + 数字」和空 {@code [K]}
 *           识别为引用，避免把正文里的英文方括号词误判成引用</td></tr>
 * </table>
 *
 * <h2>不做任何修正</h2>
 * <p>校验<b>不</b>删除、替换、补齐或规范化答案中的引用：一旦发现非法或未知引用，
 * 整次作答就是失败（HTTP 502）。静默修正会把「模型编造引用」变成一个看起来正常的答案，
 * 而那正是本任务要避免的。</p>
 *
 * <h2>usedCitationIds</h2>
 * <p>校验通过后按<b>首次出现顺序</b>返回去重后的编号列表（{@link LinkedHashSet} 保持顺序）。</p>
 */
public final class GroundedCitationValidator {

    /** {@code [K…]} 形状的记号：K 后面是数字，或者什么都没有（空 {@code [K]}）。 */
    private static final Pattern CITATION_SHAPED = Pattern.compile("\\[K(\\d*)\\]");

    /** 规范编号：不带前导零的正整数。 */
    private static final Pattern CANONICAL = Pattern.compile("[1-9]\\d*");

    private GroundedCitationValidator() {
    }

    /**
     * 校验模型答案并抽取实际使用的引用编号。
     *
     * @param rawAnswer         模型原始答案（本方法内部先 strip）
     * @param allowedCitationIds 本次检索给出的编号集合
     * @return 按首次出现顺序去重后的引用编号
     * @throws GroundedAnswerException 答案为空、无引用、引用形式非法，或引用了未知编号
     */
    public static List<String> requireValidCitations(String rawAnswer, List<String> allowedCitationIds) {
        if (rawAnswer == null || rawAnswer.strip().isEmpty()) {
            throw new GroundedAnswerException(GroundedAnswerFailure.ANSWER_EMPTY);
        }
        String answer = rawAnswer.strip();

        Set<String> used = new LinkedHashSet<>();
        Matcher matcher = CITATION_SHAPED.matcher(answer);
        while (matcher.find()) {
            String digits = matcher.group(1);
            if (!CANONICAL.matcher(digits).matches()) {
                // [K0]、[K01]、[K] 等：形式非法，直接失败（不修正、不忽略）
                throw new GroundedAnswerException(GroundedAnswerFailure.INVALID_CITATION_FORMAT);
            }
            String citationId = "K" + digits;
            if (!allowedCitationIds.contains(citationId)) {
                // 形式规范但本次没有给出这个编号：编造引用
                throw new GroundedAnswerException(GroundedAnswerFailure.UNKNOWN_CITATION);
            }
            used.add(citationId);
        }

        if (used.isEmpty()) {
            // 有证据却不给引用：无法审计结论来源
            throw new GroundedAnswerException(GroundedAnswerFailure.ANSWER_WITHOUT_CITATION);
        }
        return new ArrayList<>(used);
    }
}
