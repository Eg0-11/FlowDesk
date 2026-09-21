package com.flowdesk.application.ai;

import com.flowdesk.application.integration.AssetQueryResult;
import com.flowdesk.application.integration.MonitoringSnapshotQueryResult;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 资产诊断结果（FD-0017-A）。
 *
 * <h2>三份东西必须能被分开审计</h2>
 * <ul>
 *   <li>{@code answer}：模型给出的诊断文本，其中的结论带 {@code [A1]}/{@code [M1]} 引用；</li>
 *   <li>{@code usedEvidenceIds}：答案<b>实际引用</b>的证据编号（按首次出现顺序去重）；</li>
 *   <li>{@code asset} 与 {@code monitoring}：本次两次查询的<b>真实状态</b> ——
 *       包含各自原始的 {@code FOUND}/{@code NOT_FOUND}/{@code FAILED}（失败还带稳定分类）。</li>
 * </ul>
 *
 * <p>「查到了什么」与「答案用了什么」是两件事；而「没查到」与「没查成」也是两件事。
 * 结果对象同时保留这三层信息，因此调用方不需要（也不可能）从答案文本里反推数据状态。</p>
 *
 * <h2>证据编号</h2>
 * <p>本阶段只有两项证据：{@value #ASSET_EVIDENCE_ID}（资产记录）与
 * {@value #MONITORING_EVIDENCE_ID}（监控快照）。编号是固定契约，不随查询结果增减。</p>
 *
 * <h2>不变量（构造期强制）</h2>
 * <ul>
 *   <li>{@code requestId}、{@code answer}、{@code usedEvidenceIds}、{@code asset}、
 *       {@code monitoring} 都不得为 {@code null}；</li>
 *   <li>{@code requestId} 与 {@code answer} 也不得只有空白 —— 一个空白标识无法与日志、
 *       错误响应对齐，一段空白答案不是答案；</li>
 *   <li>{@code usedEvidenceIds} 被<b>防御性复制</b>为不可变列表（外部持有原列表也改不了结果）；</li>
 *   <li>{@code usedEvidenceIds} 必须<b>已经按首次出现顺序去重</b>：出现重复编号（例如
 *       {@code ["A1","A1"]}、{@code ["A1","M1","A1"]}）直接拒绝，而不是在本类里静默去重 ——
 *       去重是产出方的责任，静默修正会让「引用集合被谁改过」变得不可追查；</li>
 *   <li>{@code usedEvidenceIds} 只能出现 {@value #ASSET_EVIDENCE_ID}/
 *       {@value #MONITORING_EVIDENCE_ID}；</li>
 *   <li>{@value #ASSET_EVIDENCE_ID} 只有在资产查询<b>命中</b>时才允许出现，
 *       {@value #MONITORING_EVIDENCE_ID} 同理 —— 「引用了本次并不存在的证据」在类型层面不可表达；</li>
 *   <li>{@code grounded=true} 时 {@code usedEvidenceIds} 必须<b>恰好等于</b>本次所有命中证据的集合
 *       （既不能少引，也不能多引）；</li>
 *   <li>{@code grounded=false} 时 {@code usedEvidenceIds} 必须为空 ——
 *       降级回答与「有证据支撑」不能同时成立。</li>
 * </ul>
 *
 * <p>换句话说：结果对象无法表达自相矛盾的状态。真实的查询状态（含失败与未命中）永远如实保留，
 * 不会被包装成另一种状态。</p>
 *
 * @param requestId       服务端生成的请求标识（便于与日志、错误响应对齐）
 * @param answer          诊断文本；无可用证据时是固定降级文案
 * @param grounded        答案是否建立在本次命中证据之上
 * @param usedEvidenceIds 答案实际引用的证据编号（按首次出现顺序去重）
 * @param asset           本次资产查询的真实结果（含失败分类）
 * @param monitoring      本次监控查询的真实结果（含失败分类）
 */
public record AssetDiagnosisResult(String requestId,
                                   String answer,
                                   boolean grounded,
                                   List<String> usedEvidenceIds,
                                   AssetQueryResult asset,
                                   MonitoringSnapshotQueryResult monitoring) {

    /** 资产记录的证据编号。 */
    public static final String ASSET_EVIDENCE_ID = "A1";

    /** 监控快照的证据编号。 */
    public static final String MONITORING_EVIDENCE_ID = "M1";

    /**
     * 紧凑构造器：防御性复制编号，并强制所有不变量。
     *
     * @throws NullPointerException     任一必需字段为 {@code null}
     * @throws IllegalArgumentException {@code requestId}/{@code answer} 只有空白、编号未去重、
     *                                 出现本次不存在的证据编号，或 {@code grounded} 与编号不一致
     */
    public AssetDiagnosisResult {
        Objects.requireNonNull(requestId, "requestId 不能为 null");
        Objects.requireNonNull(answer, "answer 不能为 null");
        Objects.requireNonNull(asset, "asset 不能为 null");
        Objects.requireNonNull(monitoring, "monitoring 不能为 null");
        if (requestId.isBlank()) {
            throw new IllegalArgumentException("requestId 不能为空白");
        }
        if (answer.isBlank()) {
            throw new IllegalArgumentException("answer 不能为空白");
        }
        usedEvidenceIds = List.copyOf(Objects.requireNonNull(usedEvidenceIds, "usedEvidenceIds 不能为 null"));
        if (new LinkedHashSet<>(usedEvidenceIds).size() != usedEvidenceIds.size()) {
            // 重复编号意味着调用方没有按契约去重：与其在这里静默去重（让「谁改过引用集合」变得不可追查），
            // 不如直接拒绝。去重是产出方的责任，校验方的职责是发现它没做。
            throw new IllegalArgumentException("usedEvidenceIds 必须已按首次出现顺序去重：" + usedEvidenceIds);
        }

        Set<String> found = foundEvidenceIds(asset, monitoring);
        for (String used : usedEvidenceIds) {
            if (!ASSET_EVIDENCE_ID.equals(used) && !MONITORING_EVIDENCE_ID.equals(used)) {
                throw new IllegalArgumentException("usedEvidenceIds 只允许出现 " + ASSET_EVIDENCE_ID + " 与 "
                        + MONITORING_EVIDENCE_ID);
            }
            if (!found.contains(used)) {
                throw new IllegalArgumentException("usedEvidenceIds 必须是本次命中证据的子集：" + used);
            }
        }

        if (grounded) {
            if (found.isEmpty() || !new LinkedHashSet<>(usedEvidenceIds).equals(found)) {
                throw new IllegalArgumentException("grounded=true 时 usedEvidenceIds 必须恰好等于本次所有命中证据");
            }
        }
        else if (!usedEvidenceIds.isEmpty()) {
            throw new IllegalArgumentException("grounded=false 时 usedEvidenceIds 必须为空");
        }
    }

    /**
     * 本次两次查询里<b>真正命中</b>的证据编号（顺序固定为资产、监控）。
     *
     * @param asset      资产查询结果（可为 {@code null}，视为未命中）
     * @param monitoring 监控查询结果（可为 {@code null}，视为未命中）
     * @return 命中证据编号集合
     */
    public static Set<String> foundEvidenceIds(AssetQueryResult asset, MonitoringSnapshotQueryResult monitoring) {
        Set<String> found = new LinkedHashSet<>();
        if (asset != null && asset.isFound()) {
            found.add(ASSET_EVIDENCE_ID);
        }
        if (monitoring != null && monitoring.isFound()) {
            found.add(MONITORING_EVIDENCE_ID);
        }
        return found;
    }

    /**
     * @return 本次可用（命中）的证据数量
     */
    public int evidenceCount() {
        return foundEvidenceIds(this.asset, this.monitoring).size();
    }

    /**
     * @return 答案实际引用的证据数量
     */
    public int usedEvidenceCount() {
        return this.usedEvidenceIds.size();
    }
}
