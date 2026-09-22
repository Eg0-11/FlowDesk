package com.flowdesk.agent.ai;

import com.flowdesk.application.ai.AiRequestException;
import com.flowdesk.application.ai.IncidentTriageCommand;
import com.flowdesk.application.ai.KnowledgeEvidence;
import com.flowdesk.application.integration.AssetQueryResult;
import com.flowdesk.application.integration.MonitoringSnapshotQueryResult;
import java.util.List;

/**
 * 一次研判调用的<b>调用上下文</b>（FD-0018-A）。
 *
 * <p>它作为单个状态键（{@link IncidentTriageStateKeys#CALL}）在图中传递，节点在执行时把自己的
 * 产物写进来，服务层在调用结束后读取同一个实例。这样做的原因很具体：Graph 框架在为每个
 * {@code NodeOutput} 生成快照时会对状态做<b>序列化克隆</b>，而克隆会走 JSON 往返 ——
 * {@code record} 类型的领域对象经过一次往返就不再是原来的类型。因此：</p>
 * <ul>
 *   <li>节点之间、以及服务层与节点之间的<b>富对象</b>交换放在这个持有者里（同一个实例，不走克隆）；</li>
 *   <li>状态里另外只放<b>简单值</b>（{@code executionPath}、{@code route} 与回答文本），
 *       它们经过 JSON 往返仍然是原本的类型，因此可以从最终状态里安全读回；</li>
 *   <li>这<b>不</b>意味着状态不携带证据：持有者本身就是状态的一部分，证据始终在这一次调用的状态里，
 *       只是它们以一个对象的形式集中携带。</li>
 * </ul>
 *
 * <h2>执行进度（失败路径的审计依据，FD-0018-A-R1）</h2>
 * <p>图执行抛异常时框架不会把最终状态交出来，因此失败日志只能从「这个实例上已经发生的事」读：
 * 三个来源状态、{@link #getRoute() 路由}、{@link #isModelCallStarted() 是否已经发起过模型调用}
 * 与已有的引用。它们都由<b>节点在执行时</b>写入，所以失败日志记录的是真实执行进度，
 * 而不是按计划路由推测出来的形状；没有执行到的部分保持 {@code null}，由日志层写成 {@code NOT_QUERIED}。</p>
 *
 * <p>本类只在一次调用内使用：服务层每次调用都会新建一个实例，因此并发调用之间不可能共享它。</p>
 */
final class IncidentTriageCall {

    private final String requestId;

    private final IncidentTriageCommand command;

    private KnowledgeEvidence knowledge;

    private AssetQueryResult asset;

    private MonitoringSnapshotQueryResult monitoring;

    private String answer;

    private List<String> usedEvidenceIds = List.of();

    private boolean contractViolation;

    private boolean modelCallStarted;

    private String route;

    private AiRequestException inputRejection;

    /**
     * @param requestId 本次请求标识
     * @param command   本次研判命令（可为 {@code null}；合法性由图中的第一个节点判定）
     */
    IncidentTriageCall(String requestId, IncidentTriageCommand command) {
        this.requestId = requestId;
        this.command = command;
    }

    /**
     * @return 本次请求标识
     */
    String getRequestId() {
        return this.requestId;
    }

    /**
     * @return 本次研判命令（可为 {@code null}）
     */
    IncidentTriageCommand getCommand() {
        return this.command;
    }

    /**
     * @return 知识证据分支三态（尚未检索时为 {@code null}）
     */
    KnowledgeEvidence getKnowledge() {
        return this.knowledge;
    }

    /**
     * @param knowledge 知识证据分支三态
     */
    void setKnowledge(KnowledgeEvidence knowledge) {
        this.knowledge = knowledge;
    }

    /**
     * @return 资产查询结果（尚未查询时为 {@code null}）
     */
    AssetQueryResult getAsset() {
        return this.asset;
    }

    /**
     * @param asset 资产查询结果
     */
    void setAsset(AssetQueryResult asset) {
        this.asset = asset;
    }

    /**
     * @return 监控快照查询结果（尚未查询时为 {@code null}）
     */
    MonitoringSnapshotQueryResult getMonitoring() {
        return this.monitoring;
    }

    /**
     * @param monitoring 监控快照查询结果
     */
    void setMonitoring(MonitoringSnapshotQueryResult monitoring) {
        this.monitoring = monitoring;
    }

    /**
     * @return 最终答案（模型输出或固定降级文案）
     */
    String getAnswer() {
        return this.answer;
    }

    /**
     * @param answer 最终答案
     */
    void setAnswer(String answer) {
        this.answer = answer;
    }

    /**
     * @return 答案实际引用的证据编号
     */
    List<String> getUsedEvidenceIds() {
        return this.usedEvidenceIds;
    }

    /**
     * @param usedEvidenceIds 答案实际引用的证据编号
     */
    void setUsedEvidenceIds(List<String> usedEvidenceIds) {
        this.usedEvidenceIds = List.copyOf(usedEvidenceIds);
    }

    /**
     * @return 是否出现过端口契约违约
     */
    boolean isContractViolation() {
        return this.contractViolation;
    }

    /**
     * 记录一次端口契约违约。
     */
    void markContractViolation() {
        this.contractViolation = true;
    }

    /**
     * @return 是否已经<b>发起过</b>模型调用（在调用之前置位，因此失败路径也能如实反映）
     */
    boolean isModelCallStarted() {
        return this.modelCallStarted;
    }

    /**
     * 在真正发起模型调用<b>之前</b>调用：之后的任何失败都必须记录「模型已经被调用过」。
     */
    void markModelCallStarted() {
        this.modelCallStarted = true;
    }

    /**
     * @return 条件边实际使用的路由值（{@code verify_contracts} 算出后写入）；尚未算出时为 {@code null}
     */
    String getRoute() {
        return this.route;
    }

    /**
     * @param route 条件边实际使用的路由值
     */
    void setRoute(String route) {
        this.route = route;
    }

    /**
     * @return 本次调用被拒绝的输入（只有 {@code validate_asset} 与 {@code retrieve_knowledge}
     *         这两个输入校验节点会写入）；没有输入校验失败时为 {@code null}
     */
    AiRequestException getInputRejection() {
        return this.inputRejection;
    }

    /**
     * 记录一次<b>输入校验</b>失败，并返回要抛出的异常：这是「非法输入 → 400」的唯一来源。
     *
     * <p>服务层只认这个字段，<b>不</b>沿异常 cause 链搜索输入异常 —— 否则模型调用阶段抛出的
     * 任何（直接或间接包含）{@code AiRequestException} 的异常都会被误判成输入错误。</p>
     *
     * @param message 既有的稳定安全文案（不回显输入）
     * @return 已记录的输入失败异常（调用方直接 {@code throw}）
     */
    AiRequestException rejectInput(String message) {
        AiRequestException rejection = new AiRequestException(message);
        this.inputRejection = rejection;
        return rejection;
    }
}
