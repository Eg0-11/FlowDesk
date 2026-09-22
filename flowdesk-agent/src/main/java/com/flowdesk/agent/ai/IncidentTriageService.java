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
 *   <li>把图执行期间的异常收敛为稳定分类：{@link AiRequestException} 原样上抛（400），
 *       其余一律 {@link AiProviderException}（502，携带本次 requestId，固定文案，
 *       <b>不</b>泄漏框架类名）；</li>
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
 * <b>不</b>记录 assetId、问题原文、知识正文、资产详情、监控数值、模型回答、提示词、
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
            AiRequestException invalidInput = findCause(ex, AiRequestException.class);
            if (invalidInput != null) {
                logFailure(requestId, NOT_QUERIED, NOT_QUERIED, NOT_QUERIED, ROUTE_UNKNOWN, false, 0, 0,
                        IncidentTriageFailure.INVALID_INPUT, ex, startedAt);
                throw invalidInput;
            }
            IncidentTriageFailure failure = failureOf(ex);
            logFailure(requestId, NOT_QUERIED, NOT_QUERIED, NOT_QUERIED, ROUTE_UNKNOWN, false, 0, 0, failure, ex,
                    startedAt);
            throw new AiProviderException(requestId, ex);
        }

        String route;
        List<String> executionPath;
        try {
            route = requireRoute(state);
            executionPath = readPath(state);
        }
        catch (IncidentTriageException ex) {
            logFailure(requestId, NOT_QUERIED, NOT_QUERIED, NOT_QUERIED, ROUTE_UNKNOWN, false, 0, 0, ex.failure(),
                    ex, startedAt);
            throw new AiProviderException(requestId, ex);
        }

        if (call.isContractViolation()) {
            // 端口违约：能执行的证据节点都已执行完，但在调用模型之前统一失败（不伪装成某种查询状态）
            logFailure(requestId, statusOf(call.getKnowledge()), outcomeOf(call.getAsset()),
                    outcomeOf(call.getMonitoring()), route, false, 0, 0,
                    IncidentTriageFailure.PORT_CONTRACT_VIOLATION, null, startedAt);
            throw new AiProviderException(requestId, null);
        }

        KnowledgeEvidence knowledge = call.getKnowledge();
        AssetQueryResult asset = call.getAsset();
        MonitoringSnapshotQueryResult monitoring = call.getMonitoring();
        String answer = call.getAnswer();
        if (knowledge == null || asset == null || monitoring == null || answer == null) {
            logFailure(requestId, statusOf(knowledge), outcomeOf(asset), outcomeOf(monitoring), route, false, 0, 0,
                    IncidentTriageFailure.GRAPH_FAILURE, null, startedAt);
            throw new AiProviderException(requestId, null);
        }

        String knowledgeStatus = knowledge.status().name();
        String assetOutcome = asset.outcome().name();
        String monitoringOutcome = monitoring.outcome().name();

        IncidentTriageResult result;
        try {
            result = new IncidentTriageResult(requestId, answer, !call.getUsedEvidenceIds().isEmpty(),
                    call.getUsedEvidenceIds(), executionPath, knowledge, asset, monitoring);
        }
        catch (RuntimeException ex) {
            // 结果不变量被破坏属于服务端缺陷：收敛为稳定失败，绝不当作成功返回
            logFailure(requestId, knowledgeStatus, assetOutcome, monitoringOutcome, route,
                    modelCalled(route), 0, call.getUsedEvidenceIds().size(), IncidentTriageFailure.GRAPH_FAILURE, ex,
                    startedAt);
            throw new AiProviderException(requestId, ex);
        }

        log.info("{} completed operation={} requestId={} knowledgeStatus={} assetOutcome={} monitoringOutcome={} "
                        + "graphRoute={} modelCalled={} evidenceCount={} usedEvidenceCount={} success=true "
                        + "durationMs={}",
                OPERATION, OPERATION, requestId, knowledgeStatus, assetOutcome, monitoringOutcome, route,
                modelCalled(route), result.evidenceCount(), result.usedEvidenceCount(), elapsedMillis(startedAt));

        return result;
    }

    /**
     * @param route 本次路由
     * @return 本次是否调用过模型（只有「有证据」那条路径会调用）
     */
    private static boolean modelCalled(String route) {
        return IncidentTriageNodes.ROUTE_EVIDENCE_AVAILABLE.equals(route);
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

    private static String statusOf(KnowledgeEvidence knowledge) {
        return knowledge == null ? NOT_QUERIED : knowledge.status().name();
    }

    private static String outcomeOf(AssetQueryResult asset) {
        return asset == null ? NOT_QUERIED : asset.outcome().name();
    }

    private static String outcomeOf(MonitoringSnapshotQueryResult monitoring) {
        return monitoring == null ? NOT_QUERIED : monitoring.outcome().name();
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
     * 只记录固定元数据与异常类名；不记录任何业务数据、提示词、模型回答或异常消息。
     *
     * @param requestId         请求标识
     * @param knowledgeStatus   知识分支状态
     * @param assetOutcome      资产查询三态
     * @param monitoringOutcome 监控查询三态
     * @param route             闸门路由
     * @param modelCalled       是否调用过模型
     * @param evidenceCount     可用证据数量
     * @param usedEvidenceCount 实际引用数量
     * @param failure           稳定失败类别
     * @param cause             原始异常（可为 {@code null}）；只取类名
     * @param startedAt         起始纳秒
     */
    private static void logFailure(String requestId, String knowledgeStatus, String assetOutcome,
            String monitoringOutcome, String route, boolean modelCalled, int evidenceCount, int usedEvidenceCount,
            IncidentTriageFailure failure, RuntimeException cause, long startedAt) {

        log.warn("{} failed operation={} requestId={} knowledgeStatus={} assetOutcome={} monitoringOutcome={} "
                        + "graphRoute={} modelCalled={} evidenceCount={} usedEvidenceCount={} failure={} exception={} "
                        + "success=false durationMs={}",
                OPERATION, OPERATION, requestId, knowledgeStatus, assetOutcome, monitoringOutcome, route,
                modelCalled, evidenceCount, usedEvidenceCount, failure.name(), exceptionName(cause),
                elapsedMillis(startedAt));
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
