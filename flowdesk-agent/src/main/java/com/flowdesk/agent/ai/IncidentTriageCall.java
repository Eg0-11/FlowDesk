package com.flowdesk.agent.ai;

import com.flowdesk.application.ai.AiRequestException;
import com.flowdesk.application.ai.IncidentTriageCommand;
import com.flowdesk.application.ai.KnowledgeEvidence;
import com.flowdesk.application.integration.AssetQueryResult;
import com.flowdesk.application.integration.MonitoringSnapshotQueryResult;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

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
 * <h2>执行进度（失败路径的审计依据，FD-0018-A-R1 / R2）</h2>
 * <p>图执行抛异常时框架不会把最终状态交出来，因此失败日志只能从「这个实例上已经发生的事」读：
 * 三个来源各自的<b>查询进度</b>、{@link #getRoute() 路由}、
 * {@link #isModelCallStarted() 是否已经发起过模型调用}与已有的引用。它们都由<b>节点在执行时</b>
 * 写入，所以失败日志记录的是真实执行进度，而不是按计划路由推测出来的形状。</p>
 *
 * <p><b>查询进度</b>（R2）由 {@link #markQueryStarted(Source)} 在真正调用来源之前置位，因此
 * 「调用了但返回 {@code null} / 抛未声明异常」与「根本没调用」在日志里是两种不同状态：
 * 前者是 {@link SourceProgress#CONTRACT_VIOLATION}，只有后者才是「未查询」。这些状态是
 * <b>内部审计</b>信息，不修改 {@code QueryOutcome}、{@link KnowledgeEvidence} 或任何公开业务错误码，
 * 也不伪造失败结果。</p>
 *
 * <p>本类只在一次调用内使用：服务层每次调用都会新建一个实例，因此并发调用之间不可能共享它。</p>
 */
final class IncidentTriageCall {

    /** 三个证据来源（日志与审计用）。 */
    enum Source {

        /** 知识检索。 */
        KNOWLEDGE,

        /** 资产查询。 */
        ASSET,

        /** 监控快照查询。 */
        MONITORING
    }

    /**
     * 单个来源的查询进度（内部审计状态）。
     *
     * <table border="1">
     *   <caption>四种进度</caption>
     *   <tr><th>取值</th><th>含义</th><th>日志里显示</th></tr>
     *   <tr><td>{@link #NOT_QUERIED}</td><td>该来源还没有被调用</td><td>{@code NOT_QUERIED}</td></tr>
     *   <tr><td>{@link #QUERIED}</td><td>已调用并取得结论</td>
     *       <td>结果自身的 {@code FOUND}/{@code NOT_FOUND}/{@code FAILED}</td></tr>
     *   <tr><td>{@link #CONTRACT_VIOLATION}</td><td>已调用，但返回 {@code null} 或抛出未声明的异常</td>
     *       <td>{@code PORT_CONTRACT_VIOLATION}</td></tr>
     *   <tr><td>{@link #INPUT_REJECTED}</td><td>已调用，但输入在它内部被拒绝（整次调用以输入失败结束）</td>
     *       <td>{@code INVALID_INPUT}</td></tr>
     * </table>
     */
    enum SourceProgress {

        /** 尚未调用。 */
        NOT_QUERIED,

        /** 已调用并取得结论。 */
        QUERIED,

        /** 已调用但违反契约（{@code null} 或未声明异常）。 */
        CONTRACT_VIOLATION,

        /** 已调用但输入被该来源拒绝。 */
        INPUT_REJECTED
    }

    private final String requestId;

    private final IncidentTriageCommand command;

    private final Map<Source, SourceProgress> sourceProgress = new EnumMap<>(Source.class);

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
        for (Source source : Source.values()) {
            this.sourceProgress.put(source, SourceProgress.NOT_QUERIED);
        }
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
     * 记录某来源<b>已经开始查询</b>（必须在真正调用该来源之前调用）。
     *
     * @param source 来源
     */
    void markQueryStarted(Source source) {
        this.sourceProgress.put(source, SourceProgress.QUERIED);
    }

    /**
     * 记录某来源<b>违反了查询契约</b>：返回 {@code null} 或抛出未声明的异常。
     *
     * <p>查询<b>已经执行</b>（因此不是「未查询」），只是没有取得合法结论；同时置位本次调用
     * 整体的违约标记，由 {@code verify_contracts} 决定路由。</p>
     *
     * @param source 来源
     */
    void markQueryContractViolation(Source source) {
        this.sourceProgress.put(source, SourceProgress.CONTRACT_VIOLATION);
        this.contractViolation = true;
    }

    /**
     * @param source 来源
     * @return 该来源当前的查询进度（内部审计状态，不影响公开契约）
     */
    SourceProgress progressOf(Source source) {
        return this.sourceProgress.get(source);
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
        return recordInputRejection(message);
    }

    /**
     * 记录一次发生在<b>某个来源内部</b>的输入校验失败（该来源已经被调用，输入被它拒绝）。
     *
     * <p>与 {@link #rejectInput(String)} 的区别只在审计：这里的来源<b>不再</b>是「未查询」，
     * 而是「已调用、输入被拒」，日志据此如实标注该来源（R2）。</p>
     *
     * @param source  被调用但拒绝了输入的来源
     * @param message 既有的稳定安全文案（不回显输入）
     * @return 已记录的输入失败异常（调用方直接 {@code throw}）
     */
    AiRequestException rejectQueryInput(Source source, String message) {
        this.sourceProgress.put(source, SourceProgress.INPUT_REJECTED);
        return recordInputRejection(message);
    }

    private AiRequestException recordInputRejection(String message) {
        AiRequestException rejection = new AiRequestException(message);
        this.inputRejection = rejection;
        return rejection;
    }
}
