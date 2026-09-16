package com.flowdesk.agent.ai;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 答案引用校验（RAG 5/6）：纯 Java、确定性、无框架依赖。
 *
 * <h2>唯一合法形式</h2>
 * <p>只接受 {@code [K1]}、{@code [K2]}……即<b>大写 K + 不带前导零的正整数 + 右方括号</b>。</p>
 *
 * <h2>扫描方式：先找「引用意图」，再判是否合法（FD-0012-R1）</h2>
 * <p>早期实现只匹配 {@code \[K(\d*)\]}，于是所有<b>不匹配</b>的畸形写法都被当成普通文字忽略：
 * {@code [K-1]}、{@code [K1a]}、{@code [K 2]}、{@code [k9]} 与合法引用混在一句话里时，
 * 整次答案会被判为成功。这不是「宽松」，而是漏检 —— 畸形引用恰恰是最需要被拦下的东西。</p>
 *
 * <p>现在改为<b>两个阶段</b>：</p>
 * <ol>
 *   <li><b>找出所有「引用意图」记号</b>：扫描答案中<b>每一个</b>左方括号（包括嵌套在其它方括号
 *       内部的那一个），取其到下一个右方括号（没有右方括号则取到字符串末尾）之间的文本。
 *       若这段文本以 {@code k}/{@code K} 开头、且紧随其后的字符<b>不是 ASCII 字母</b>
 *       （或 {@code k} 之后已经没有字符），就认为这里存在引用意图；</li>
 *   <li><b>逐个判定</b>：只有「已闭合、且内容严格等于 {@code K} + 无前导零正整数」才算合法引用；
 *       其余一律失败（{@link GroundedAnswerFailure#INVALID_CITATION_FORMAT}）。
 *       合法引用还必须出现在本次证据里，否则是 {@link GroundedAnswerFailure#UNKNOWN_CITATION}。</li>
 * </ol>
 *
 * <p>因此「合法引用 + 畸形引用并存」必然失败：只要答案里存在哪怕一个畸形引用，
 * 整次作答就是失败 —— 一个会在答案里随手写 {@code [K-1]} 的模型，其引用本身就不可信。</p>
 *
 * <h2>{@code [Known]} 仍然只是普通文本</h2>
 * <p>{@code K} 后面紧跟 ASCII 字母（{@code [Known]}、{@code [KB]}）不构成引用意图，
 * 按普通英文方括号词处理。这是本规则<b>唯一</b>的宽松之处，且方向是安全的：
 * 它只会让文本不被当成引用，而不会让畸形引用被接受。</p>
 *
 * <p><b>已知边界</b>：反过来，形如 {@code [k-means]}、{@code [k 均值]} 这类以 k 开头、
 * 后面既不是字母也不是数字的正文记号会被判定为「引用意图 → 形式非法」，从而使整次作答失败。
 * 这是刻意的取舍：宁可让一次回答失败，也不给畸形引用留下绕过通道。</p>
 *
 * <h2>不做任何修正</h2>
 * <p>校验<b>不</b>删除、替换、补齐或规范化答案中的引用：一旦发现畸形或未知引用，
 * 整次作答就是失败（HTTP 502）。静默修正会把「模型编造引用」变成一个看起来正常的答案，
 * 而那正是本任务要避免的。</p>
 *
 * <h2>usedCitationIds</h2>
 * <p>校验通过后按<b>首次出现顺序</b>返回去重后的编号列表（{@link LinkedHashSet} 保持顺序）。</p>
 */
public final class GroundedCitationValidator {

    /** 规范引用的前缀：大写 K。 */
    private static final char CANONICAL_PREFIX = 'K';

    /** 规范引用数字部分的首字符下界：不能是 0，因此不允许前导零。 */
    private static final char FIRST_DIGIT_LOWER_BOUND = '1';

    private static final char LAST_DIGIT = '9';

    private GroundedCitationValidator() {
    }

    /**
     * 校验模型答案并抽取实际使用的引用编号。
     *
     * @param rawAnswer          模型原始答案（本方法内部先 strip）
     * @param allowedCitationIds 本次检索给出的编号集合
     * @return 按首次出现顺序去重后的引用编号
     * @throws GroundedAnswerException 答案为空、无引用、存在畸形引用，或引用了未知编号
     */
    public static List<String> requireValidCitations(String rawAnswer, List<String> allowedCitationIds) {
        if (rawAnswer == null || rawAnswer.strip().isEmpty()) {
            throw new GroundedAnswerException(GroundedAnswerFailure.ANSWER_EMPTY);
        }
        String answer = rawAnswer.strip();

        Set<String> used = new LinkedHashSet<>();
        // 逐个左方括号检查（而不是用非重叠正则），否则 [[K-1]] 这类嵌套写法会让畸形引用漏检
        for (int open = answer.indexOf('['); open >= 0; open = answer.indexOf('[', open + 1)) {
            int close = answer.indexOf(']', open + 1);
            String inner = close < 0 ? answer.substring(open + 1) : answer.substring(open + 1, close);
            if (!hasCitationIntent(inner)) {
                // 不构成引用意图（例如 [Known]、[注意]）：只是普通文本
                continue;
            }
            if (close < 0) {
                // 有引用意图但没有右方括号：未闭合的畸形引用
                throw new GroundedAnswerException(GroundedAnswerFailure.INVALID_CITATION_FORMAT);
            }
            String citationId = canonicalCitationId(inner);
            if (citationId == null) {
                // [K]、[K0]、[K01]、[K-1]、[K+1]、[K 1]、[K1 ]、[K1a]、[K1,K2]、[k1]……
                throw new GroundedAnswerException(GroundedAnswerFailure.INVALID_CITATION_FORMAT);
            }
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

    /**
     * 判断方括号内的文本是否表达了「引用意图」。
     *
     * <p>规则：以 {@code k}/{@code K} 开头，并且紧随其后的字符不是 ASCII 字母
     * （或其后已无字符）。{@code [Known]} 因 {@code K} 后是字母 {@code n} 而不构成引用意图。</p>
     *
     * @param inner 方括号之间的文本（未闭合时为左括号之后的全部内容）
     * @return 是否存在引用意图
     */
    private static boolean hasCitationIntent(String inner) {
        if (inner.isEmpty()) {
            return false;
        }
        char first = inner.charAt(0);
        if (first != 'k' && first != CANONICAL_PREFIX) {
            return false;
        }
        if (inner.length() == 1) {
            // [K]：K 后面什么都没有，显然是「想写一个引用」
            return true;
        }
        return !isAsciiLetter(inner.charAt(1));
    }

    /**
     * 判定方括号内的文本是否恰好是一个规范引用。
     *
     * @param inner 方括号之间的文本
     * @return 规范编号（{@code K} + 无前导零正整数），不是规范形式时返回 {@code null}
     */
    private static String canonicalCitationId(String inner) {
        if (inner.length() < 2 || inner.charAt(0) != CANONICAL_PREFIX) {
            return null;
        }
        char firstDigit = inner.charAt(1);
        if (firstDigit < FIRST_DIGIT_LOWER_BOUND || firstDigit > LAST_DIGIT) {
            return null;
        }
        for (int index = 2; index < inner.length(); index++) {
            char digit = inner.charAt(index);
            if (digit < '0' || digit > LAST_DIGIT) {
                return null;
            }
        }
        return inner;
    }

    /**
     * @param value 待判定字符
     * @return 是否为 ASCII 字母
     */
    private static boolean isAsciiLetter(char value) {
        return (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z');
    }
}
