package com.flowdesk.agent.ai;

import com.flowdesk.application.ai.AssetDiagnosisResult;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 诊断答案的引用校验（FD-0017-A）：纯 Java、确定性、无框架依赖。
 *
 * <h2>这也是一个 ASCII 方括号引用协议</h2>
 * <p>本校验器只认两种引用写法：资产记录 {@code [A1]}、监控快照 {@code [M1]} ——
 * <b>大写字母 + 数字 {@code 1} + 右方括号</b>，且中间没有任何其它字符。
 * 它处理的是纯 ASCII 方括号记号：不是 Markdown 解析器、不是 HTML 实体解码器，
 * 也不做 Unicode 同形字符归一（见文末「已知边界」）。</p>
 *
 * <h2>两步判定</h2>
 * <ol>
 *   <li><b>找出所有「引用意图」记号</b>：扫描答案中<b>每一个</b> ASCII 左方括号
 *       （逐位推进，因此嵌套写法也会被检查），取其到下一个右方括号（没有则取到末尾）之间的文本；</li>
 *   <li><b>逐个判定</b>：文本必须<b>未经修改地</b>等于 {@code A1} 或 {@code M1}，
 *       否则整次诊断失败（{@link AssetDiagnosisFailure#INVALID_CITATION_FORMAT}）。
 *       规范引用还必须出现在本次命中证据里，否则是
 *       {@link AssetDiagnosisFailure#UNKNOWN_CITATION}。</li>
 * </ol>
 *
 * <h3>什么算「引用意图」</h3>
 * <table border="1">
 *   <caption>引用意图判定</caption>
 *   <tr><th>方括号内容</th><th>判定</th></tr>
 *   <tr><td>整个内容由<b>至少两个</b> ASCII 字母组成（{@code [A-Za-z]{2,}}，如 {@code [API]}、
 *       {@code [MAC]}、{@code [MB]}</td><td><b>普通英文方括号词</b>：不是引用</td></tr>
 *   <tr><td>其它情况，只要 {@code strip()} 后以 {@code A}/{@code a}/{@code M}/{@code m} 开头
 *       （含单字母 {@code [A]}）</td><td><b>引用意图</b>：随后必须严格规范，否则失败</td></tr>
 *   <tr><td>不以这四个字母开头（{@code [注意]}、{@code [1]}、{@code []} 等）</td>
 *       <td>普通文本</td></tr>
 * </table>
 *
 * <h3>不做任何修正</h3>
 * <p>不删除、不补齐、不 {@code strip()}、不做大小写折叠：{@code [a1]}、{@code [A01]}、
 * {@code [A 1]}、{@code [A1 ]}、{@code [A1x]}、{@code [A2]}、未闭合的 {@code [A1}
 * 一律失败，而不是被「修好」——静默修正会把「模型编造引用」变成一个看起来正常的答案。</p>
 *
 * <h3>返回与完整性</h3>
 * <p>校验通过后按<b>首次出现顺序</b>返回去重后的编号；并且要求本阶段<b>每一条命中证据</b>
 * 都至少被引用一次（否则 {@link AssetDiagnosisFailure#EVIDENCE_NOT_CITED}），
 * 这样诊断里的每个结论都能逐条回溯到本次证据。</p>
 *
 * <h3>已知边界（如实记录）</h3>
 * <ul>
 *   <li>只处理 ASCII 方括号：全角 {@code ［A1］} 不构成引用意图，也不会被当成引用；</li>
 *   <li>不做同形字符识别：西里尔字母 {@code А}（U+0410）不是 ASCII 的 {@code A}；</li>
 *   <li>不做 Markdown / HTML 实体解析：{@code &#91;A1&#93;}、{@code \[A1\]} 既不构成引用、
 *       也不会被解码；</li>
 *   <li><b>引用校验只证明编号来源</b>：每个引用都能回到本次命中证据，它<b>不证明</b>模型结论在
 *       事实上正确 —— 模型仍可能「引用了正确编号却推理错误」。</li>
 * </ul>
 */
public final class AssetDiagnosisCitationValidator {

    /** 普通英文方括号词：整个内容由至少两个 ASCII 字母组成。 */
    private static final Pattern BRACKETED_WORD = Pattern.compile("[A-Za-z]{2,}");

    private static final char ASSET_PREFIX = 'A';

    private static final char MONITORING_PREFIX = 'M';

    private static final char EXPECTED_DIGIT = '1';

    private AssetDiagnosisCitationValidator() {
    }

    /**
     * 校验模型答案并抽取实际使用的证据编号。
     *
     * @param rawAnswer          模型原始答案（本方法内部先 strip 整个答案）
     * @param allowedEvidenceIds 本次命中证据的编号（{@code A1} 与/或 {@code M1}）
     * @return 按首次出现顺序去重后的证据编号
     * @throws AssetDiagnosisException 答案为空、无引用、存在畸形引用、引用了本次不存在的证据，
     *                                 或本次命中证据没有被全部引用
     */
    public static List<String> requireValidEvidenceCitations(String rawAnswer, List<String> allowedEvidenceIds) {
        if (rawAnswer == null || rawAnswer.strip().isEmpty()) {
            throw new AssetDiagnosisException(AssetDiagnosisFailure.ANSWER_EMPTY);
        }
        String answer = rawAnswer.strip();

        Set<String> used = new LinkedHashSet<>();
        // 逐个左方括号检查（而不是用非重叠正则），否则 [[A1]] 这类嵌套写法会让畸形引用漏检
        for (int open = answer.indexOf('['); open >= 0; open = answer.indexOf('[', open + 1)) {
            int close = answer.indexOf(']', open + 1);
            String inner = close < 0 ? answer.substring(open + 1) : answer.substring(open + 1, close);
            if (!hasCitationIntent(inner)) {
                continue;
            }
            if (close < 0) {
                // 有引用意图却没有右方括号：未闭合的畸形引用
                throw new AssetDiagnosisException(AssetDiagnosisFailure.INVALID_CITATION_FORMAT);
            }
            String evidenceId = canonicalEvidenceId(inner);
            if (evidenceId == null) {
                throw new AssetDiagnosisException(AssetDiagnosisFailure.INVALID_CITATION_FORMAT);
            }
            if (!allowedEvidenceIds.contains(evidenceId)) {
                // 形式规范但本次没有给出这个证据（例如资产未命中却引用 [A1]）
                throw new AssetDiagnosisException(AssetDiagnosisFailure.UNKNOWN_CITATION);
            }
            used.add(evidenceId);
        }

        if (used.isEmpty()) {
            throw new AssetDiagnosisException(AssetDiagnosisFailure.ANSWER_WITHOUT_CITATION);
        }
        if (!used.containsAll(allowedEvidenceIds)) {
            // 有命中证据却没被引用：结论无法逐条回溯
            throw new AssetDiagnosisException(AssetDiagnosisFailure.EVIDENCE_NOT_CITED);
        }
        return new ArrayList<>(used);
    }

    /**
     * 判断方括号内的文本是否表达了「引用意图」。
     *
     * @param inner 方括号之间的文本（未闭合时为左括号之后的全部内容）
     * @return 是否存在引用意图
     */
    private static boolean hasCitationIntent(String inner) {
        if (BRACKETED_WORD.matcher(inner).matches()) {
            // [API]、[MAC]、[MB]：完整的纯 ASCII 字母单词，按普通文本处理
            return false;
        }
        String trimmed = inner.strip();
        if (trimmed.isEmpty()) {
            return false;
        }
        char first = trimmed.charAt(0);
        return first == ASSET_PREFIX || first == MONITORING_PREFIX
                || first == 'a' || first == 'm';
    }

    /**
     * 判定方括号内的文本是否恰好是一个规范引用。
     *
     * @param inner 方括号之间的文本
     * @return 规范编号（{@code A1}/{@code M1}），不是规范形式时返回 {@code null}
     */
    private static String canonicalEvidenceId(String inner) {
        if (inner.length() != 2) {
            return null;
        }
        char prefix = inner.charAt(0);
        if ((prefix != ASSET_PREFIX && prefix != MONITORING_PREFIX)
                || inner.charAt(1) != EXPECTED_DIGIT) {
            return null;
        }
        return inner;
    }
}
