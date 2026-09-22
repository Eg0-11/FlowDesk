package com.flowdesk.application.ai;

import com.flowdesk.application.integration.AssetQueryResult;
import com.flowdesk.application.integration.MonitoringSnapshotQueryResult;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 事件研判结果（FD-0018-A）。
 *
 * <h2>四层信息必须能被分开审计</h2>
 * <ul>
 *   <li>{@code answer}：模型给出的中文研判（结论带 {@code [K1]}/{@code [A1]}/{@code [M1]} 引用）；</li>
 *   <li>{@code usedEvidenceIds}：答案<b>实际引用</b>的编号，按首次出现顺序、已去重；</li>
 *   <li>{@code executionPath}：本次<b>真实执行过</b>的 Graph 节点序列（由节点自己追加，事后不伪造）；</li>
 *   <li>{@code knowledge}/{@code asset}/{@code monitoring}：三个来源的<b>真实状态</b>，
 *       包含各自的 FOUND/NOT_FOUND/FAILED（失败还带稳定分类）。</li>
 * </ul>
 *
 * <h2>证据编号</h2>
 * <p>知识沿用检索给出的 {@code K1}…{@code Kn}，资产固定 {@value #ASSET_EVIDENCE_ID}，
 * 监控固定 {@value #MONITORING_EVIDENCE_ID}。</p>
 *
 * <h2>不变量（构造期强制）</h2>
 * <ul>
 *   <li>{@code requestId}、{@code answer} 非 null 且非空白；</li>
 *   <li>{@code usedEvidenceIds} 与 {@code executionPath} 被防御性复制为不可变列表；</li>
 *   <li>{@code executionPath} 至少有一个节点，且 {@code usedEvidenceIds} 不允许重复；</li>
 *   <li>{@code usedEvidenceIds} 只能出现<b>本次真实存在</b>的编号（{@code K1}…{@code Kn}、{@code A1}、{@code M1}）；</li>
 *   <li>{@code grounded} <b>当且仅当</b>引用集合非空 —— 因此降级回答（fallback）必然是
 *       {@code grounded=false} 且引用为空，不可能既声称有据又没有任何引用；</li>
 *   <li>三个分支的形状由各自类型保证（见 {@link KnowledgeEvidence} 与两个查询结果），
 *       这里再统一校验一遍「引用必须来自本次证据」。</li>
 * </ul>
 *
 * @param requestId       服务端生成的请求标识
 * @param answer          研判文本；无可用证据时是固定降级文案
 * @param grounded        是否建立在本次证据之上（当且仅当引用了证据）
 * @param usedEvidenceIds 答案实际引用的证据编号（首次出现顺序、去重）
 * @param executionPath   Graph 真实执行过的节点名序列
 * @param knowledge       知识检索分支三态
 * @param asset           资产查询真实结果（含失败分类）
 * @param monitoring      监控快照查询真实结果（含失败分类）
 */
public record IncidentTriageResult(String requestId,
                                   String answer,
                                   boolean grounded,
                                   List<String> usedEvidenceIds,
                                   List<String> executionPath,
                                   KnowledgeEvidence knowledge,
                                   AssetQueryResult asset,
                                   MonitoringSnapshotQueryResult monitoring) {

    /** 资产的证据编号。 */
    public static final String ASSET_EVIDENCE_ID = "A1";

    /** 监控快照的证据编号。 */
    public static final String MONITORING_EVIDENCE_ID = "M1";

    /**
     * @throws NullPointerException     必需字段为 {@code null}
     * @throws IllegalArgumentException 字段为空白、引用重复，或引用/grounded 与本次证据不一致
     */
    public IncidentTriageResult {
        this.requireText(requestId, "requestId");
        this.requireText(answer, "answer");
        Objects.requireNonNull(knowledge, "knowledge 不能为 null");
        Objects.requireNonNull(asset, "asset 不能为 null");
        Objects.requireNonNull(monitoring, "monitoring 不能为 null");
        usedEvidenceIds = List.copyOf(Objects.requireNonNull(usedEvidenceIds, "usedEvidenceIds 不能为 null"));
        executionPath = List.copyOf(Objects.requireNonNull(executionPath, "executionPath 不能为 null"));

        if (executionPath.isEmpty()) {
            throw new IllegalArgumentException("executionPath 至少要有真实执行过的节点");
        }
        if (new HashSet<>(usedEvidenceIds).size() != usedEvidenceIds.size()) {
            throw new IllegalArgumentException("usedEvidenceIds 必须已按首次出现顺序去重：" + usedEvidenceIds);
        }

        Set<String> available = availableEvidenceIds(knowledge, asset, monitoring);
        for (String used : usedEvidenceIds) {
            if (!available.contains(used)) {
                throw new IllegalArgumentException("usedEvidenceIds 必须是本次存在证据的子集：" + used);
            }
        }
        if (grounded != !usedEvidenceIds.isEmpty()) {
            throw new IllegalArgumentException("grounded 必须当且仅当实际引用了证据");
        }
    }

    /**
     * 本次<b>真实存在</b>的证据编号（顺序固定：知识在前，然后资产、监控）。
     *
     * @param knowledge  知识分支
     * @param asset      资产查询结果（可为 {@code null}，视为不存在）
     * @param monitoring 监控查询结果（可为 {@code null}，视为不存在）
     * @return 编号集合
     */
    public static Set<String> availableEvidenceIds(KnowledgeEvidence knowledge, AssetQueryResult asset,
            MonitoringSnapshotQueryResult monitoring) {

        Set<String> available = new LinkedHashSet<>();
        if (knowledge != null && knowledge.isFound()) {
            available.addAll(knowledge.citationIds());
        }
        if (asset != null && asset.isFound()) {
            available.add(ASSET_EVIDENCE_ID);
        }
        if (monitoring != null && monitoring.isFound()) {
            available.add(MONITORING_EVIDENCE_ID);
        }
        return available;
    }

    /**
     * @return 本次可用证据数量（知识按切片条数计）
     */
    public int evidenceCount() {
        return availableEvidenceIds(this.knowledge, this.asset, this.monitoring).size();
    }

    /**
     * @return 答案实际引用的证据数量
     */
    public int usedEvidenceCount() {
        return this.usedEvidenceIds.size();
    }

    /**
     * @return 本次是否使用了知识证据
     */
    public boolean hasKnowledgeEvidence() {
        return this.knowledge.isFound();
    }

    private void requireText(String value, String field) {
        if (value == null) {
            throw new NullPointerException(field + " 不能为 null");
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空白");
        }
    }
}
