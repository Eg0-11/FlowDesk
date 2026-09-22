package com.flowdesk.agent.ai;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.flowdesk.application.ai.AiProviderException;
import com.flowdesk.application.ai.AiRequestException;
import com.flowdesk.application.ai.IncidentTriageCommand;
import com.flowdesk.application.ai.IncidentTriageResult;
import com.flowdesk.application.ai.IncidentTriageUseCase;
import com.flowdesk.application.ai.KnowledgeEvidence;
import com.flowdesk.application.integration.AssetQueryResult;
import com.flowdesk.application.integration.MonitoringSnapshotQueryResult;
import com.flowdesk.application.integration.port.out.AssetQueryPort;
import com.flowdesk.application.integration.port.out.MonitoringSnapshotQueryPort;
import com.flowdesk.application.knowledge.port.in.RetrieveKnowledgeUseCase;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

/**
 * 事件研判用例实现（FD-0018-A）：驱动 {@link IncidentTriageGraph} 并收敛结果与失败。
 *
 * <h2>职责边界</h2>
 * <ul>
 *   <li>生成 {@code requestId}，为每次调用创建<b>独立的</b>调用上下文与 {@code RunnableConfig}，
 *       执行装配期编译好的图；</li>
 *   <li>把图执行期间的异常收敛为稳定分类：只有<b>输入校验失败</b>（调用上下文里由
 *       {@code validate_asset}/{@code retrieve_knowledge} 记录的 {@code AiRequestException}）
 *       原样上抛（400），其余一律 {@link AiProviderException}（502，携带本次 requestId，固定文案，
 *       <b>不</b>泄漏框架类名）。<b>不</b>沿异常 cause 链搜索输入异常 —— 模型调用阶段抛出的
 *       任何（直接或间接包含）{@code AiRequestException} 的异常都必须是
 *       {@link IncidentTriageFailure#MODEL_CALL_FAILED}；</li>
 *   <li>读取最终状态：路由或执行路径缺失/类型不符 → {@link IncidentTriageFailure#GRAPH_FAILURE}；
 *       端口契约违约 → {@link IncidentTriageFailure#PORT_CONTRACT_VIOLATION}；</li>
 *   <li>把本次调用的产物组装成 application 层的 {@link IncidentTriageResult}，并记录固定元数据日志。</li>
 * </ul>
 *
 * <p>富对象（三个证据结果与答案）从本次调用的上下文对象读取：节点写入的就是同一个实例，
 * 因此不受框架为 {@code NodeOutput} 做序列化克隆的影响（见 {@link IncidentTriageCall}）。</p>
 *
 * <h2>并发隔离</h2>
 * <p>编译好的图是只读的；每次调用都新建调用上下文、输入 Map 与新的 {@code threadId}，
 * 且没有 checkpoint 持久化，因此并发调用之间不共享任何状态。</p>
 *
 * <h2>日志</h2>
 * <p>每次研判只记录：{@code operation}、{@code requestId}、三个来源状态、{@code graphRoute}、
 * {@code modelCalled}、{@code evidenceCount}、{@code usedEvidenceCount}、{@code success}、
 * {@code durationMs}，失败时再加稳定失败类别与异常<b>类名</b>。
 * 这些元数据一律来自本次调用上下文里<b>已经发生</b>的执行进度：失败发生在图内部时框架不会交出
 * 最终状态，若按「计划形状」硬编码（例如一律 {@code NOT_QUERIED}/{@code none}/{@code false}/0），
 * 失败日志就会与真实执行不符（FD-0018-A-R1 修正的就是这一点）。</p>
 * <p>三个来源状态由该来源的<b>查询进度</b>决定（FD-0018-A-R2）：{@code NOT_QUERIED} 只表示
 * 「这个来源从来没有被调用」；调用过但返回 {@code null} 或抛未声明异常记
 * {@code PORT_CONTRACT_VIOLATION}；调用过并取得结论时记结果自身的
 * {@code FOUND}/{@code NOT_FOUND}/{@code FAILED}。</p>
 * <p><b>不</b>记录 assetId、问题原文、知识正文、资产详情、监控数值、模型回答、提示词、
 * 异常消息、端点、密钥、SQL 或堆栈。</p>
 */
public class IncidentTriageService implements IncidentTriageUseCase {

    private static final Logger log = LoggerFactory.getLogger(IncidentTriageService.class);

    /** 操作名（日志固定字段）。 */
    static final String OPERATION = "ai.incident-triage";

    /** 尚未查询（或状态不可得）时的占位值。 */
    static final String NOT_QUERIED = "NOT_QUERIED";

    /** 路由不可得时的占位值。 */
    static final String ROUTE_UNKNOWN = "none";

    private final IncidentTriageGraph graph;

    /**
     * @param retrieveKnowledgeUseCase    知识检索用例（输入合法性唯一入口）
     * @param assetQueryPort              资产查询端口
     * @param monitoringSnapshotQueryPort 监控快照查询端口
     * @param deepSeekChatClient          DeepSeek ChatClient（不注册工具、无会话记忆）
     */
    public IncidentTriageService(RetrieveKnowledgeUseCase retrieveKnowledgeUseCase, AssetQueryPort assetQueryPort,
            MonitoringSnapshotQueryPort monitoringSnapshotQueryPort, ChatClient deepSeekChatClient) {

        this.graph = new IncidentTriageGraph(retrieveKnowledgeUseCase, assetQueryPort, monitoringSnapshotQueryPort,
                deepSeekChatClient);
    }

    @Override
    public IncidentTriageResult triage(IncidentTriageCommand command) {
        long startedAt = System.nanoTime();
        String requestId = UUID.randomUUID().toString();
        IncidentTriageCall call = new IncidentTriageCall(requestId, command);

        OverAllState state;
        try {
            state = this.graph.compiled()
                    .invoke(Map.of(IncidentTriageStateKeys.CALL, call), IncidentTriageGraph.runConfig(requestId))
                    .orElseThrow(() -> new IncidentTriageException(IncidentTriageFailure.GRAPH_FAILURE));
        }
        catch (RuntimeException ex) {
            // 输入校验失败只认调用上下文里的记录（由两个输入校验节点写入），
            // 绝不沿 cause 链搜索 AiRequestException —— 否则模型阶段抛出的同类异常会被误判成 400
            AiRequestException rejected = call.getInputRejection();
            if (rejected != null) {
                logFailure(requestId, call, IncidentTriageFailure.INVALID_INPUT, ex, startedAt);
                throw rejected;
            }
            logFailure(requestId, call, failureOf(ex), ex, startedAt);
            throw new AiProviderException(requestId, ex);
        }

        String route;
        List<String> executionPath;
        try {
            route = requireRoute(state);
            executionPath = readPath(state);
        }
        catch (IncidentTriageException ex) {
            logFailure(requestId, call, ex.failure(), ex, startedAt);
            throw new AiProviderException(requestId, ex);
        }

        if (call.isContractViolation()) {
            // 端口违约：能执行的证据节点都已执行完，但在调用模型之前统一失败（不伪装成某种查询状态）
            logFailure(requestId, call, IncidentTriageFailure.PORT_CONTRACT_VIOLATION, null, startedAt);
            throw new AiProviderException(requestId, null);
        }

        KnowledgeEvidence knowledge = call.getKnowledge();
        AssetQueryResult asset = call.getAsset();
        MonitoringSnapshotQueryResult monitoring = call.getMonitoring();
        String answer = call.getAnswer();
        if (knowledge == null || asset == null || monitoring == null || answer == null) {
            logFailure(requestId, call, IncidentTriageFailure.GRAPH_FAILURE, null, startedAt);
            throw new AiProviderException(requestId, null);
        }

        IncidentTriageResult result;
        try {
            result = new IncidentTriageResult(requestId, answer, !call.getUsedEvidenceIds().isEmpty(),
                    call.getUsedEvidenceIds(), executionPath, knowledge, asset, monitoring);
        }
        catch (RuntimeException ex) {
            // 结果不变量被破坏属于服务端缺陷：收敛为稳定失败，绝不当作成功返回
            logFailure(requestId, call, IncidentTriageFailure.GRAPH_FAILURE, ex, startedAt);
            throw new AiProviderException(requestId, ex);
        }

        log.info("{} completed operation={} requestId={} knowledgeStatus={} assetOutcome={} monitoringOutcome={} "
                        + "graphRoute={} modelCalled={} evidenceCount={} usedEvidenceCount={} success=true "
                        + "durationMs={}",
                OPERATION, OPERATION, requestId, knowledgeStatusOf(call), assetOutcomeOf(call),
                monitoringOutcomeOf(call), route, call.isModelCallStarted(), result.evidenceCount(),
                result.usedEvidenceCount(), elapsedMillis(startedAt));

        return result;
    }

    /**
     * 读取最终状态里的路由。
     *
     * @param state 最终状态
     * @return 路由值
     * @throws IncidentTriageException 缺失或类型不符
     */
    static String requireRoute(OverAllState state) {
        Optional<String> route;
        try {
            route = state.value(IncidentTriageStateKeys.ROUTE, String.class);
        }
        catch (RuntimeException ex) {
            throw new IncidentTriageException(IncidentTriageFailure.GRAPH_FAILURE, ex);
        }
        String value = route.orElseThrow(() -> new IncidentTriageException(IncidentTriageFailure.GRAPH_FAILURE));
        if (!IncidentTriageNodes.ROUTE_EVIDENCE_AVAILABLE.equals(value)
                && !IncidentTriageNodes.ROUTE_NO_EVIDENCE.equals(value)
                && !IncidentTriageNodes.ROUTE_CONTRACT_VIOLATION.equals(value)) {
            throw new IncidentTriageException(IncidentTriageFailure.GRAPH_FAILURE);
        }
        return value;
    }

    /**
     * 最终状态里的执行路径（APPEND 策略累积出来的真实节点序列）。
     *
     * @param state 最终状态
     * @return 节点名列表
     */
    @SuppressWarnings("unchecked")
    static List<String> readPath(OverAllState state) {
        Object path;
        try {
            path = state.value(IncidentTriageStateKeys.EXECUTION_PATH).orElse(null);
        }
        catch (RuntimeException ex) {
            throw new IncidentTriageException(IncidentTriageFailure.GRAPH_FAILURE, ex);
        }
        if (!(path instanceof List<?> list) || list.isEmpty()) {
            throw new IncidentTriageException(IncidentTriageFailure.GRAPH_FAILURE);
        }
        for (Object node : list) {
            if (!(node instanceof String)) {
                throw new IncidentTriageException(IncidentTriageFailure.GRAPH_FAILURE);
            }
        }
        return List.copyOf((List<String>) list);
    }

    /**
     * 日志里单个来源的状态：由该来源的<b>查询进度</b>决定，而不是由「结果是不是 {@code null}」决定（R2）。
     *
     * <table border="1">
     *   <caption>进度到日志取值的映射</caption>
     *   <tr><th>查询进度</th><th>日志取值</th><th>含义</th></tr>
     *   <tr><td>{@code NOT_QUERIED}</td><td>{@link #NOT_QUERIED}</td><td>这个来源<b>从来没有被调用</b></td></tr>
     *   <tr><td>{@code QUERIED}</td><td>结果自身的 {@code FOUND}/{@code NOT_FOUND}/{@code FAILED}</td>
     *       <td>已调用并取得合法结论</td></tr>
     *   <tr><td>{@code CONTRACT_VIOLATION}</td>
     *       <td>{@link IncidentTriageFailure#PORT_CONTRACT_VIOLATION}</td>
     *       <td>已调用，但返回 {@code null} 或抛出未声明的异常</td></tr>
     *   <tr><td>{@code INPUT_REJECTED}</td><td>{@link IncidentTriageFailure#INVALID_INPUT}</td>
     *       <td>已调用，但输入在它内部被拒绝</td></tr>
     * </table>
     *
     * @param call         本次调用上下文
     * @param source       来源
     * @param reportedState 该来源结果自身的状态名（没有结果时为 {@code null}）
     * @return 日志取值
     */
    private static String sourceStateOf(IncidentTriageCall call, IncidentTriageCall.Source source,
            String reportedState) {

        return switch (call.progressOf(source)) {
            case NOT_QUERIED -> NOT_QUERIED;
            case CONTRACT_VIOLATION -> IncidentTriageFailure.PORT_CONTRACT_VIOLATION.name();
            case INPUT_REJECTED -> IncidentTriageFailure.INVALID_INPUT.name();
            // 已调用就必须有结论：没有结论只可能是契约问题，绝不谎报「未查询」
            case QUERIED -> reportedState == null ? IncidentTriageFailure.PORT_CONTRACT_VIOLATION.name()
                    : reportedState;
        };
    }

    private static String knowledgeStatusOf(IncidentTriageCall call) {
        KnowledgeEvidence knowledge = call.getKnowledge();
        return sourceStateOf(call, IncidentTriageCall.Source.KNOWLEDGE,
                knowledge == null ? null : knowledge.status().name());
    }

    private static String assetOutcomeOf(IncidentTriageCall call) {
        AssetQueryResult asset = call.getAsset();
        return sourceStateOf(call, IncidentTriageCall.Source.ASSET, asset == null ? null : asset.outcome().name());
    }

    private static String monitoringOutcomeOf(IncidentTriageCall call) {
        MonitoringSnapshotQueryResult monitoring = call.getMonitoring();
        return sourceStateOf(call, IncidentTriageCall.Source.MONITORING,
                monitoring == null ? null : monitoring.outcome().name());
    }

    /**
     * @param call 本次调用上下文
     * @return 条件边实际使用的路由；尚未算出来（还没执行到 {@code verify_contracts}）时为 {@link #ROUTE_UNKNOWN}
     */
    private static String routeOf(IncidentTriageCall call) {
        return call.getRoute() == null ? ROUTE_UNKNOWN : call.getRoute();
    }

    /**
     * @param call 本次调用上下文
     * @return 本次<b>真实存在</b>的可用证据数量（三个来源里任何尚未执行的都按不存在计）
     */
    private static int availableEvidenceCount(IncidentTriageCall call) {
        return IncidentTriageResult
                .availableEvidenceIds(call.getKnowledge(), call.getAsset(), call.getMonitoring()).size();
    }

    /**
     * 把图执行期间抛出的异常映射为稳定失败类别。
     *
     * @param thrown 原始异常
     * @return 稳定失败类别
     */
    private static IncidentTriageFailure failureOf(RuntimeException thrown) {
        IncidentTriageException triage = findCause(thrown, IncidentTriageException.class);
        return triage == null ? IncidentTriageFailure.GRAPH_FAILURE : triage.failure();
    }

    /**
     * 在有界且带环路保护的 cause 链上查找指定类型的异常。
     *
     * @param thrown 原始异常
     * @param type   目标类型
     * @param <T>    类型
     * @return 找到的异常；没有则为 {@code null}
     */
    private static <T extends Throwable> T findCause(Throwable thrown, Class<T> type) {
        Throwable current = thrown;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int depth = 0; current != null && depth < 16 && seen.add(current); depth++) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * 记录失败时的固定元数据：全部取自<b>本次调用上下文里已经发生的事</b>。
     *
     * <p>图执行抛异常时框架不会交出最终状态，因此这里的三个来源状态、路由、是否调用过模型与
     * 证据数量都来自节点在执行时写入调用上下文的真实进度：<b>未执行</b>的部分才写成
     * {@link #NOT_QUERIED}/{@link #ROUTE_UNKNOWN}，而 {@code modelCalled} 来自「调用模型之前
     * 置位」的标记（不是按计划路由推测），{@code usedEvidenceCount} 来自真正通过校验的引用
     * （校验失败时它必然为空，不伪造一个通过校验的数量）。</p>
     *
     * <p>三个来源状态走 {@link #sourceStateOf}：<b>调用过但没有合法结论</b>（返回 {@code null}
     * 或抛未声明异常）记 {@link IncidentTriageFailure#PORT_CONTRACT_VIOLATION}，
     * 只有<b>真的没调用</b>才是 {@link #NOT_QUERIED}（R2）。</p>
     *
     * @param requestId 请求标识
     * @param call      本次调用上下文（真实执行进度）
     * @param failure   稳定失败类别
     * @param cause     原始异常（可为 {@code null}）；只取类名
     * @param startedAt 起始纳秒
     */
    private static void logFailure(String requestId, IncidentTriageCall call, IncidentTriageFailure failure,
            RuntimeException cause, long startedAt) {

        log.warn("{} failed operation={} requestId={} knowledgeStatus={} assetOutcome={} monitoringOutcome={} "
                        + "graphRoute={} modelCalled={} evidenceCount={} usedEvidenceCount={} failure={} exception={} "
                        + "success=false durationMs={}",
                OPERATION, OPERATION, requestId, knowledgeStatusOf(call), assetOutcomeOf(call),
                monitoringOutcomeOf(call), routeOf(call), call.isModelCallStarted(),
                availableEvidenceCount(call), call.getUsedEvidenceIds().size(), failure.name(),
                exceptionName(cause), elapsedMillis(startedAt));
    }

    /**
     * 诊断用的异常<b>类名</b>（永不记录异常消息或堆栈）。
     *
     * <p>本模块的 {@link IncidentTriageException} 只是稳定失败分类的包装层，本身不带诊断价值，
     * 因此上报它包住的原始异常类名；没有被包装时上报自身类名。</p>
     *
     * @param cause 原始异常（可为 {@code null}）
     * @return 异常类名；没有异常时为 {@code "none"}
     */
    private static String exceptionName(RuntimeException cause) {
        if (cause == null) {
            return "none";
        }
        Throwable current = cause;
        for (int depth = 0; depth < 16 && current instanceof IncidentTriageException
                && current.getCause() != null; depth++) {
            current = current.getCause();
        }
        return current.getClass().getName();
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }
}
