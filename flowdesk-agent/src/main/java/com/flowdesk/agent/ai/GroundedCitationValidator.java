package com.flowdesk.agent.ai;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 答案引用校验（RAG 5/6）：纯 Java、确定性、无框架依赖。
 *
 * <h2>这是一个 ASCII 方括号引用协议</h2>
 * <p>本校验器只认一种引用写法：{@code [K} + {@code [1-9][0-9]*} + {@code ]}，
 * 即<b>大写 K + 不带前导零的正整数 + 右方括号</b>（{@code [K1]}、{@code [K12]}……）。
 * 它处理的是<b>纯 ASCII 方括号记号</b>：不是 Markdown 语法解析器、不是 HTML 实体解码器、
 * 也不是 Unicode 同形字符的净化器（见文末「已知边界」）。</p>
 *
 * <h2>两步判定（FD-0012-R1 / R2）</h2>
 * <ol>
 *   <li><b>找出所有「引用意图」记号</b>：扫描答案中<b>每一个</b> ASCII 左方括号
 *       （用 {@code indexOf} 逐位推进，因此嵌套在其它方括号内部的那一个也会被检查），
 *       取其到下一个右方括号（没有右方括号则取到字符串末尾）之间的文本 {@code inner}；</li>
 *   <li><b>逐个判定</b>：{@code inner} 必须<b>未经修改地</b>严格匹配 {@code K[1-9][0-9]*}
 *       才算合法引用；否则一律失败（{@link GroundedAnswerFailure#INVALID_CITATION_FORMAT}）。
 *       合法引用还必须出现在本次证据里，否则是 {@link GroundedAnswerFailure#UNKNOWN_CITATION}。</li>
 * </ol>
 *
 * <h3>什么算「引用意图」（FD-0012-R2 收紧）</h3>
 * <table border="1">
 *   <caption>引用意图判定</caption>
 *   <tr><th>{@code inner}</th><th>判定</th></tr>
 *   <tr><td>整个内容由<b>至少两个</b> ASCII 字母组成（{@code [A-Za-z]{2,}}），如 {@code [Known]}、
 *       {@code [KB]}、{@code [Kubernetes]}</td>
 *       <td><b>普通英文方括号词</b>：不是引用，按普通文本忽略</td></tr>
 *   <tr><td>其它情况，只要 {@code inner.strip()} 以 {@code K}/{@code k} 开头
 *       （含单字母 {@code [K]}）</td>
 *       <td><b>引用意图</b>：随后必须通过严格规范校验，否则整次作答失败</td></tr>
 *   <tr><td>{@code inner.strip()} 不以 {@code K}/{@code k} 开头（{@code [注意]}、{@code []} 等）</td>
 *       <td>普通文本</td></tr>
 * </table>
 *
 * <p>单字母 {@code [K]} 刻意<b>不</b>算普通词：它不是「完整单词」，而是「K 后面缺了编号」，
 * 因此按 {@link GroundedAnswerFailure#INVALID_CITATION_FORMAT} 失败。</p>
 *
 * <p>早期实现只检查 {@code K} 后面的<b>第一个</b>字符是否为 ASCII 字母，于是
 * {@code [Kx1]}（第二个字符是字母 {@code x}）与 {@code [Ka-1]} 被当成普通文本忽略：
 * 一句「正常结论 [K1]，伪造来源 [Kx1]」会被判为<b>成功</b>。这正是 R2 修掉的缺口 ——
 * 「有引用意图」不等于「第二个字符是数字」，因此例外只留给<b>完整的纯字母单词</b>，
 * 其余 K/k 前缀记号一律进入严格校验。</p>
 *
 * <h3>不做任何修正</h3>
 * <p>校验<b>不</b>删除、替换、补齐、{@code strip()}、大小写折叠或规范化答案里的引用：
 * 规范判定只作用于<b>原始</b> {@code inner}（{@code strip()} 仅用于判断是否存在引用意图，
 * 绝不用于「修好」模型输出）。{@code [ K1 ]}、{@code [k1]}、{@code [K 1]} 一律失败，
 * 而不是被自动纠正成 {@code [K1]} —— 静默修正会把「模型编造引用」变成一个看起来正常的答案。</p>
 *
 * <h3>usedCitationIds</h3>
 * <p>校验通过后按<b>首次出现顺序</b>返回去重后的编号列表（{@link LinkedHashSet} 保持顺序）。</p>
 *
 * <h3>已知边界（如实记录）</h3>
 * <ul>
 *   <li><b>只处理 ASCII 方括号</b>：全角括号 {@code ［K1］} 等其它括号形态不构成引用意图，
 *       也不会被当成引用；</li>
 *   <li><b>不做同形字符识别</b>：西里尔字母 {@code К}（U+041A）等 Unicode 同形字符不属于
 *       ASCII 的 {@code K}/{@code k}，因此 {@code [К1]} 不会被视为引用意图；</li>
 *   <li><b>不做 Markdown / HTML 实体解析</b>：{@code &#91;K1&#93;}、{@code \[K1\]} 等写法
 *       既不构成引用，也不会被解码；</li>
 *   <li>因此这里不使用「所有视觉形态都能识别」这类无法证明的表述：本校验器只保证
 *       <b>ASCII 方括号协议内</b>的行为，规则如上表所述。</li>
 * </ul>
 */
public final class GroundedCitationValidator {

    /**
     * 普通英文方括号词：整个方括号内容由 <b>至少两个</b> ASCII 字母组成
     * （{@code [Known]}、{@code [KB]}、{@code [Kubernetes]}）。
     *
     * <p>单字母 {@code [K]} 不在此列：它不是「完整单词」，而是「K 后面缺了编号」的引用意图，
     * 必须按畸形引用失败。</p>
     */
    private static final Pattern BRACKETED_WORD = Pattern.compile("[A-Za-z]{2,}");

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
     * @param rawAnswer          模型原始答案（本方法内部先 strip 整个答案）
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
                // 不构成引用意图（[Known]、[Kubernetes]、[注意]、[] 等）：只是普通文本
                continue;
            }
            if (close < 0) {
                // 有引用意图但没有右方括号：未闭合的畸形引用
                throw new GroundedAnswerException(GroundedAnswerFailure.INVALID_CITATION_FORMAT);
            }
            String citationId = canonicalCitationId(inner);
            if (citationId == null) {
                // [K]、[K0]、[K01]、[K-1]、[K+1]、[K 1]、[K1 ]、[K1a]、[K1,K2]、[k1]、
                // [Kx1]、[Ka-1]、[Known1]、[Kabc_1]、[ K999]、[ K1 ]…… 一律失败，不做任何修正
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
     * <p>规则（FD-0012-R2）：整体是 ASCII 字母单词（{@code [A-Za-z]+}）时按普通文本处理；
     * 否则只要 {@code inner.strip()} 以 {@code K}/{@code k} 开头，就认为存在引用意图。
     * {@code strip()} 只用于这一步判断，<b>不</b>参与规范判定。</p>
     *
     * @param inner 方括号之间的文本（未闭合时为左括号之后的全部内容）
     * @return 是否存在引用意图
     */
    private static boolean hasCitationIntent(String inner) {
        if (BRACKETED_WORD.matcher(inner).matches()) {
            // [Known]、[KB]、[Kubernetes]：完整的纯 ASCII 字母单词，按普通文本处理
            return false;
        }
        String trimmed = inner.strip();
        if (trimmed.isEmpty()) {
            return false;
        }
        char first = trimmed.charAt(0);
        return first == 'k' || first == CANONICAL_PREFIX;
    }

    /**
     * 判定方括号内的文本是否恰好是一个规范引用。
     *
     * <p>只接受未经修改的原始文本：{@code K} + {@code [1-9][0-9]*}。</p>
     *
     * @param inner 方括号之间的文本
     * @return 规范编号，不是规范形式时返回 {@code null}
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
}
