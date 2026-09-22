package com.flowdesk.agent.ai;

import com.flowdesk.application.ai.IncidentTriageResult;
import com.flowdesk.application.integration.AssetQueryResult;
import com.flowdesk.application.integration.MonitoringSnapshotQueryResult;
import com.flowdesk.application.ai.KnowledgeEvidence;
import com.flowdesk.application.knowledge.view.KnowledgeCitationView;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 组合引用校验（FD-0018-A）：知识 {@code K1..Kn}、资产 {@code A1}、监控 {@code M1}。
 *
 * <h2>协议</h2>
 * <p>只接受 ASCII 方括号里<b>未经修改的规范编号</b>：</p>
 * <ul>
 *   <li>知识：{@code K} + {@code [1-9][0-9]*}（不允许前导零），且必须是本次检索给出的编号之一；</li>
 *   <li>资产：恰好 {@code A1}；监控：恰好 {@code M1}；</li>
 *   <li>其它一切（{@code [a1]}、{@code [K01]}、{@code [K 1]}、{@code [K1 ]}、{@code [K1x]}、
 *       {@code [K1,A1]}、{@code [A2]}、{@code [K]}、未闭合的 {@code [K1}、全角数字……）一律失败，
 *       不做任何修正、不补引用、不重新调用模型。</li>
 * </ul>
 *
 * <h2>完整性要求</h2>
 * <ul>
 *   <li>至少引用一条证据（有证据却不引用 → {@code ANSWER_WITHOUT_CITATION}）；</li>
 *   <li><b>每一族本次有证据的来源都必须被引用</b>：有知识证据 → 至少一个 {@code K} 编号；
 *       资产命中 → 必须引用 {@code A1}；监控命中 → 必须引用 {@code M1}；
 *       否则 {@code EVIDENCE_FAMILY_NOT_CITED} —— 否则某一条证据可以「存在但从不被使用」，
 *       审计时看不出它到底支撑了哪一句结论。</li>
 * </ul>
 *
 * <h2>去重与顺序</h2>
 * <p>重复引用按<b>首次出现顺序</b>去重后返回。</p>
 *
 * <h2>已知边界（如实记录）</h2>
 * <ul>
 *   <li>只处理 ASCII 方括号：全角 {@code ［K1］} 不构成引用意图；</li>
 *   <li>不做同形字符识别、不做 Markdown/HTML 实体解码；</li>
 *   <li>纯 ASCII 字母方括号词（{@code [API]}、{@code [MAC]}、{@code [Known]}）按普通文本忽略；</li>
 *   <li><b>只证明编号来源</b>：每个引用都能回到本次证据，<b>不</b>证明结论在事实上正确。</li>
 * </ul>
 */
final class IncidentTriageCitationValidator {

    /** 普通英文方括号词：整个内容由至少两个 ASCII 字母组成。 */
    private static final Pattern BRACKETED_WORD = Pattern.compile("[A-Za-z]{2,}");

    private static final char KNOWLEDGE_PREFIX = 'K';

    private static final char ASSET_PREFIX = 'A';

    private static final char MONITORING_PREFIX = 'M';

    private IncidentTriageCitationValidator() {
    }

    /**
     * 校验模型答案并抽取实际使用的证据编号。
     *
     * @param rawAnswer  模型原始答案（本方法内部先 strip 整个答案）
     * @param knowledge  知识分支三态
     * @param asset      资产查询结果
     * @param monitoring 监控查询结果
     * @return 按首次出现顺序去重后的编号
     * @throws IncidentTriageException 答案为空、无引用、畸形引用、未知编号或漏掉证据族
     */
    static List<String> requireValidCitations(String rawAnswer, KnowledgeEvidence knowledge,
            AssetQueryResult asset, MonitoringSnapshotQueryResult monitoring) {

        if (rawAnswer == null || rawAnswer.strip().isEmpty()) {
            throw new IncidentTriageException(IncidentTriageFailure.ANSWER_EMPTY);
        }
        String answer = rawAnswer.strip();
        Set<String> available = IncidentTriageResult.availableEvidenceIds(knowledge, asset, monitoring);

        Set<String> used = new LinkedHashSet<>();
        // 逐个左方括号检查（而不是非重叠正则），否则 [[K1]] 这类嵌套写法会让畸形引用漏检
        for (int open = answer.indexOf('['); open >= 0; open = answer.indexOf('[', open + 1)) {
            int close = answer.indexOf(']', open + 1);
            String inner = close < 0 ? answer.substring(open + 1) : answer.substring(open + 1, close);
            if (!hasCitationIntent(inner)) {
                continue;
            }
            if (close < 0) {
                throw new IncidentTriageException(IncidentTriageFailure.INVALID_CITATION_FORMAT);
            }
            String evidenceId = canonicalEvidenceId(inner);
            if (evidenceId == null) {
                throw new IncidentTriageException(IncidentTriageFailure.INVALID_CITATION_FORMAT);
            }
            if (!available.contains(evidenceId)) {
                throw new IncidentTriageException(IncidentTriageFailure.UNKNOWN_CITATION);
            }
            used.add(evidenceId);
        }

        if (used.isEmpty()) {
            throw new IncidentTriageException(IncidentTriageFailure.ANSWER_WITHOUT_CITATION);
        }
        requireEveryAvailableFamily(used, knowledge, asset, monitoring);
        return new ArrayList<>(used);
    }

    /**
     * 每一族本次有证据的来源都必须被引用。
     *
     * @param used       答案实际引用
     * @param knowledge  知识分支
     * @param asset      资产结果
     * @param monitoring 监控结果
     * @throws IncidentTriageException 某族有证据却一条都没被引用
     */
    private static void requireEveryAvailableFamily(Set<String> used, KnowledgeEvidence knowledge,
            AssetQueryResult asset, MonitoringSnapshotQueryResult monitoring) {

        if (knowledge != null && knowledge.isFound() && used.stream().noneMatch(id -> id.startsWith("K"))) {
            throw new IncidentTriageException(IncidentTriageFailure.EVIDENCE_FAMILY_NOT_CITED);
        }
        if (asset != null && asset.isFound() && !used.contains(IncidentTriageResult.ASSET_EVIDENCE_ID)) {
            throw new IncidentTriageException(IncidentTriageFailure.EVIDENCE_FAMILY_NOT_CITED);
        }
        if (monitoring != null && monitoring.isFound()
                && !used.contains(IncidentTriageResult.MONITORING_EVIDENCE_ID)) {
            throw new IncidentTriageException(IncidentTriageFailure.EVIDENCE_FAMILY_NOT_CITED);
        }
    }

    /**
     * 方括号内是否表达「引用意图」。
     *
     * @param inner 方括号之间的文本（未闭合时为左括号之后的全部内容）
     * @return 是否存在引用意图
     */
    private static boolean hasCitationIntent(String inner) {
        if (BRACKETED_WORD.matcher(inner).matches()) {
            // [API]、[MAC]、[Known]：完整的纯 ASCII 字母单词，按普通文本处理
            return false;
        }
        String trimmed = inner.strip();
        if (trimmed.isEmpty()) {
            return false;
        }
        char first = trimmed.charAt(0);
        return first == KNOWLEDGE_PREFIX || first == ASSET_PREFIX || first == MONITORING_PREFIX
                || first == 'k' || first == 'a' || first == 'm';
    }

    /**
     * 判定方括号内的文本是否恰好是一个规范编号（形态判定，不判断是否本次存在）。
     *
     * @param inner 方括号之间的文本
     * @return 规范编号（{@code K1}… / {@code A1} / {@code M1}）；不是规范形式时返回 {@code null}
     */
    private static String canonicalEvidenceId(String inner) {
        if (inner.length() == 2) {
            char prefix = inner.charAt(0);
            if ((prefix == ASSET_PREFIX || prefix == MONITORING_PREFIX) && inner.charAt(1) == '1') {
                return inner;
            }
        }
        if (inner.length() >= 2 && inner.charAt(0) == KNOWLEDGE_PREFIX) {
            char first = inner.charAt(1);
            if (first < '1' || first > '9') {
                // K0、K01、K-1、K 1、Kx 等一律畸形
                return null;
            }
            for (int index = 2; index < inner.length(); index++) {
                char digit = inner.charAt(index);
                if (digit < '0' || digit > '9') {
                    return null;
                }
            }
            // 形态合法：是否本次存在由调用方判定（不存在即编造引用）
            return inner;
        }
        return null;
    }
}
