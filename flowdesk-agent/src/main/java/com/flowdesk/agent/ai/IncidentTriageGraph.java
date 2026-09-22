package com.flowdesk.agent.ai;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.flowdesk.application.ai.AiRequestException;
import com.flowdesk.application.ai.IncidentTriageCommand;
import com.flowdesk.application.ai.IncidentTriageResult;
import com.flowdesk.application.ai.KnowledgeEvidence;
import com.flowdesk.application.ai.KnowledgeFailure;
import com.flowdesk.application.integration.AssetIdentifier;
import com.flowdesk.application.integration.AssetQueryResult;
import com.flowdesk.application.integration.MonitoringSnapshotQueryResult;
import com.flowdesk.application.integration.port.out.AssetQueryPort;
import com.flowdesk.application.integration.port.out.MonitoringSnapshotQueryPort;
import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.application.knowledge.port.in.RetrieveKnowledgeUseCase;
import com.flowdesk.application.knowledge.query.KnowledgeQueryNormalizer;
import com.flowdesk.application.knowledge.query.RetrieveKnowledgeQuery;
import com.flowdesk.application.knowledge.view.KnowledgeRetrievalView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;

/**
 * 事件研判 Agent Graph（FD-0018-A）：<b>真正的</b> Spring AI Alibaba StateGraph。
 *
 * <pre>
 * START → validate_asset → retrieve_knowledge → query_asset → query_monitoring
 *       → verify_contracts → evidence_gate ─┬─ evidence_available → generate_answer
 *                                          │                       → validate_citations → finish
 *                                          ├─ no_evidence        → fallback_answer  → finish
 *                                          └─ contract_violation → finish
 *       finish → END
 * </pre>
 *
 * <h2>为什么这里用 Graph</h2>
 * <p>证据收集是「三个来源各自独立、任何一个失败都不影响其它两个」的形状，收集完之后在一个<b>闸门</b>
 * 上分叉成「生成研判」或「固定降级」—— 这正是 StateGraph 的用武之地：节点、边与条件路由都是
 * <b>声明</b>出来的，调度与状态合并由框架负责，而不是藏在一串 if/else 里。</p>
 *
 * <h2>执行语义</h2>
 * <ol>
 *   <li>{@code validate_asset}：用既有 {@link AssetIdentifier} 校验 assetId；不合法抛
 *       {@link AiRequestException} —— 后面的证据节点与模型<b>一个都不会执行</b>；</li>
 *   <li>{@code retrieve_knowledge}：调用既有检索用例。输入不合法（{@code INVALID_RETRIEVAL_QUERY}）
 *       同样抛 {@link AiRequestException}；其余失败（向量化关闭、Embedding/重排上游故障、内部失败）
 *       收敛为知识分支的 {@code FAILED} + 稳定分类，<b>继续</b>查询资产与监控；</li>
 *   <li>{@code query_asset} / {@code query_monitoring}：各调用端口一次，不重试、不缓存；
 *       {@code FAILED}/{@code NOT_FOUND} 原样保留；端口返回 {@code null} 或抛异常记为
 *       <b>端口契约违约</b>，但下一个证据节点仍然执行；</li>
 *   <li>{@code verify_contracts}：有违约 → 路由 {@code contract_violation}；否则校验本次调用上下文
 *       的形状（缺失 → {@link IncidentTriageFailure#GRAPH_FAILURE}），并按「有没有可用证据」决定路由；</li>
 *   <li>{@code evidence_gate}：<b>条件边</b>，把 route 映射到目标节点；</li>
 *   <li>{@code generate_answer} → {@code validate_citations}：模型<b>只调用一次</b>，
 *       然后独立校验引用；失败抛 {@link IncidentTriageException}（不修正、不重试）；</li>
 *   <li>{@code fallback_answer}：完全没有可用证据时的两条固定降级文案（不调用模型）；</li>
 *   <li>{@code finish}：正常终点。</li>
 * </ol>
 *
 * <h2>状态与并发</h2>
 * <ul>
 *   <li>状态里只有三个键（见 {@link IncidentTriageStateKeys}）：本次调用的上下文对象、
 *       路由与执行路径；合并策略集中声明；</li>
 *   <li>{@code executionPath} 用 APPEND 策略，由每个节点在<b>自己执行时</b>追加节点名 ——
 *       路径来自真实执行，不是结束后拼出来的；</li>
 *   <li>图在装配期<b>编译一次</b>并复用；每次调用都用全新的调用上下文与独立的 {@code RunnableConfig}
 *       （{@code threadId} 即本次 requestId），因此并发调用之间不共享任何状态；</li>
 *   <li><b>不</b>启用 checkpoint 持久化（显式传入空的 {@link SaverConfig}）、
 *       <b>不</b>启用人工审批、<b>不</b>保留跨请求会话状态。</li>
 * </ul>
 */
final class IncidentTriageGraph {

    /** 图名（日志与诊断用）。 */
    static final String GRAPH_NAME = "incident-triage";

    /** 三类来源都没有命中、且都不是失败时的固定回答（不调用模型）。 */
    static final String NO_EVIDENCE_ANSWER = "未找到可用于事件研判的资产、监控或知识证据。";

    /** 没有命中且至少一个来源失败（FAILED/DISABLED）时的固定降级回答（不调用模型）。 */
    static final String INSUFFICIENT_EVIDENCE_ANSWER = "当前无法获得足够证据，暂时不能完成事件研判。";

    /** 输入不合法时的固定安全文案。 */
    static final String INVALID_ASSET_ID_MESSAGE = "assetId 必须形如 AST-000001（AST- 加 6 位数字）";

    private final RetrieveKnowledgeUseCase retrieveKnowledgeUseCase;

    private final AssetQueryPort assetQueryPort;

    private final MonitoringSnapshotQueryPort monitoringSnapshotQueryPort;

    private final ChatClient deepSeekChatClient;

    private final CompiledGraph compiled;

    /**
     * @param retrieveKnowledgeUseCase    知识检索用例（输入合法性唯一入口）
     * @param assetQueryPort              资产查询端口
     * @param monitoringSnapshotQueryPort 监控快照查询端口
     * @param deepSeekChatClient          DeepSeek ChatClient（不注册工具、无会话记忆）
     */
    IncidentTriageGraph(RetrieveKnowledgeUseCase retrieveKnowledgeUseCase, AssetQueryPort assetQueryPort,
            MonitoringSnapshotQueryPort monitoringSnapshotQueryPort, ChatClient deepSeekChatClient) {

        this.retrieveKnowledgeUseCase = retrieveKnowledgeUseCase;
        this.assetQueryPort = assetQueryPort;
        this.monitoringSnapshotQueryPort = monitoringSnapshotQueryPort;
        this.deepSeekChatClient = deepSeekChatClient;
        this.compiled = compile();
    }

    /**
     * @return 编译好的图（装配期一次，之后只读复用）
     */
    CompiledGraph compiled() {
        return this.compiled;
    }

    /**
     * 每次调用使用的独立运行配置：{@code threadId} 就是本次 requestId。
     *
     * @param requestId 请求标识
     * @return 运行配置
     */
    static RunnableConfig runConfig(String requestId) {
        return RunnableConfig.builder().threadId(requestId).build();
    }

    // ---------- 装配 ----------

    private CompiledGraph compile() {
        try {
            StateGraph graph = new StateGraph(GRAPH_NAME, IncidentTriageStateKeys.keyStrategyFactory());

            graph.addNode(IncidentTriageNodes.VALIDATE_ASSET, AsyncNodeAction.node_async(this::validateAsset));
            graph.addNode(IncidentTriageNodes.RETRIEVE_KNOWLEDGE,
                    AsyncNodeAction.node_async(this::retrieveKnowledge));
            graph.addNode(IncidentTriageNodes.QUERY_ASSET, AsyncNodeAction.node_async(this::queryAsset));
            graph.addNode(IncidentTriageNodes.QUERY_MONITORING,
                    AsyncNodeAction.node_async(this::queryMonitoring));
            graph.addNode(IncidentTriageNodes.VERIFY_CONTRACTS,
                    AsyncNodeAction.node_async(this::verifyContracts));
            graph.addNode(IncidentTriageNodes.EVIDENCE_GATE, AsyncNodeAction.node_async(this::evidenceGate));
            graph.addNode(IncidentTriageNodes.GENERATE_ANSWER, AsyncNodeAction.node_async(this::generateAnswer));
            graph.addNode(IncidentTriageNodes.VALIDATE_CITATIONS,
                    AsyncNodeAction.node_async(this::validateCitations));
            graph.addNode(IncidentTriageNodes.FALLBACK_ANSWER,
                    AsyncNodeAction.node_async(this::fallbackAnswer));
            graph.addNode(IncidentTriageNodes.CONTRACT_VIOLATION,
                    AsyncNodeAction.node_async(this::contractViolation));
            graph.addNode(IncidentTriageNodes.FINISH, AsyncNodeAction.node_async(this::finish));

            graph.addEdge(StateGraph.START, IncidentTriageNodes.VALIDATE_ASSET);
            graph.addEdge(IncidentTriageNodes.VALIDATE_ASSET, IncidentTriageNodes.RETRIEVE_KNOWLEDGE);
            graph.addEdge(IncidentTriageNodes.RETRIEVE_KNOWLEDGE, IncidentTriageNodes.QUERY_ASSET);
            graph.addEdge(IncidentTriageNodes.QUERY_ASSET, IncidentTriageNodes.QUERY_MONITORING);
            graph.addEdge(IncidentTriageNodes.QUERY_MONITORING, IncidentTriageNodes.VERIFY_CONTRACTS);
            graph.addEdge(IncidentTriageNodes.VERIFY_CONTRACTS, IncidentTriageNodes.EVIDENCE_GATE);

            // 真正的条件边：evidence_gate 的三个出口由路由值决定
            graph.addConditionalEdges(IncidentTriageNodes.EVIDENCE_GATE,
                    AsyncEdgeAction.edge_async(this::route),
                    Map.of(IncidentTriageNodes.ROUTE_EVIDENCE_AVAILABLE, IncidentTriageNodes.GENERATE_ANSWER,
                            IncidentTriageNodes.ROUTE_NO_EVIDENCE, IncidentTriageNodes.FALLBACK_ANSWER,
                            IncidentTriageNodes.ROUTE_CONTRACT_VIOLATION, IncidentTriageNodes.CONTRACT_VIOLATION));

            graph.addEdge(IncidentTriageNodes.GENERATE_ANSWER, IncidentTriageNodes.VALIDATE_CITATIONS);
            graph.addEdge(IncidentTriageNodes.VALIDATE_CITATIONS, IncidentTriageNodes.FINISH);
            graph.addEdge(IncidentTriageNodes.FALLBACK_ANSWER, IncidentTriageNodes.FINISH);
            graph.addEdge(IncidentTriageNodes.CONTRACT_VIOLATION, IncidentTriageNodes.FINISH);
            graph.addEdge(IncidentTriageNodes.FINISH, StateGraph.END);

            // 明确的空 SaverConfig：不注册任何 checkpoint saver，因此没有持久化记忆
            return graph.compile(CompileConfig.builder().saverConfig(new SaverConfig()).build());
        }
        catch (GraphStateException ex) {
            // 装配期失败是编码/依赖问题，不是运行期输入问题：启动即失败，不进入任何响应
            throw new IllegalStateException("事件研判 Graph 装配失败", ex);
        }
    }

    // ---------- 节点 ----------

    private Map<String, Object> validateAsset(OverAllState state) {
        IncidentTriageCall call = requireCall(state);
        IncidentTriageCommand command = call.getCommand();
        String assetId = command == null ? null : command.assetId();
        if (!AssetIdentifier.isValid(assetId)) {
            throw new AiRequestException(INVALID_ASSET_ID_MESSAGE);
        }
        return update(IncidentTriageNodes.VALIDATE_ASSET);
    }

    private Map<String, Object> retrieveKnowledge(OverAllState state) {
        IncidentTriageCall call = requireCall(state);
        IncidentTriageCommand command = call.getCommand();
        Map<String, Object> update = update(IncidentTriageNodes.RETRIEVE_KNOWLEDGE);

        try {
            // 输入的合法性（空问题、topK/minScore 范围）只由检索用例判定，这里不复制第二套规则
            KnowledgeRetrievalView view = this.retrieveKnowledgeUseCase.retrieve(
                    new RetrieveKnowledgeQuery(command.question(), command.topK(), command.minScore()));
            if (view == null) {
                call.markContractViolation();
                return update;
            }
            call.setKnowledge(view.citations().isEmpty()
                    ? KnowledgeEvidence.notFound(view)
                    : KnowledgeEvidence.found(view));
        }
        catch (KnowledgeApplicationException ex) {
            if (ex.errorCode() == KnowledgeApplicationErrorCode.INVALID_RETRIEVAL_QUERY) {
                // 输入不合法 → 400；此时资产、监控与模型都还没有被调用
                throw new AiRequestException(ex.getMessage());
            }
            call.setKnowledge(KnowledgeEvidence.failed(knowledgeFailure(ex.errorCode())));
        }
        catch (RuntimeException ex) {
            // 检索链路的任何其它运行期异常：知识分支 FAILED，但资产与监控照常查询
            call.setKnowledge(KnowledgeEvidence.failed(KnowledgeFailure.RETRIEVAL_FAILURE));
        }
        return update;
    }

    private Map<String, Object> queryAsset(OverAllState state) {
        IncidentTriageCall call = requireCall(state);
        Map<String, Object> update = update(IncidentTriageNodes.QUERY_ASSET);
        try {
            AssetQueryResult result = this.assetQueryPort.findAsset(call.getCommand().assetId());
            if (result == null) {
                call.markContractViolation();
                return update;
            }
            call.setAsset(result);
        }
        catch (RuntimeException ex) {
            // 端口违反契约（本应只返回三态结果）：记录违约，但监控查询仍然要执行
            call.markContractViolation();
        }
        return update;
    }

    private Map<String, Object> queryMonitoring(OverAllState state) {
        IncidentTriageCall call = requireCall(state);
        Map<String, Object> update = update(IncidentTriageNodes.QUERY_MONITORING);
        try {
            MonitoringSnapshotQueryResult result = this.monitoringSnapshotQueryPort
                    .findLatestSnapshot(call.getCommand().assetId());
            if (result == null) {
                call.markContractViolation();
                return update;
            }
            call.setMonitoring(result);
        }
        catch (RuntimeException ex) {
            call.markContractViolation();
        }
        return update;
    }

    private Map<String, Object> verifyContracts(OverAllState state) {
        IncidentTriageCall call = requireCall(state);
        Map<String, Object> update = update(IncidentTriageNodes.VERIFY_CONTRACTS);

        if (call.isContractViolation()) {
            update.put(IncidentTriageStateKeys.ROUTE, IncidentTriageNodes.ROUTE_CONTRACT_VIOLATION);
            return update;
        }

        requireResults(call);
        boolean hasEvidence = !IncidentTriageResult
                .availableEvidenceIds(call.getKnowledge(), call.getAsset(), call.getMonitoring()).isEmpty();
        update.put(IncidentTriageStateKeys.ROUTE,
                hasEvidence ? IncidentTriageNodes.ROUTE_EVIDENCE_AVAILABLE : IncidentTriageNodes.ROUTE_NO_EVIDENCE);
        return update;
    }

    private Map<String, Object> evidenceGate(OverAllState state) {
        return update(IncidentTriageNodes.EVIDENCE_GATE);
    }

    /**
     * 条件边：把 {@code verify_contracts} 算出的路由值映射到目标节点。
     *
     * @param state 当前状态
     * @return 路由值
     */
    private String route(OverAllState state) {
        String route = state.value(IncidentTriageStateKeys.ROUTE, String.class)
                .orElseThrow(() -> new IncidentTriageException(IncidentTriageFailure.GRAPH_FAILURE));
        if (!IncidentTriageNodes.ROUTE_EVIDENCE_AVAILABLE.equals(route)
                && !IncidentTriageNodes.ROUTE_NO_EVIDENCE.equals(route)
                && !IncidentTriageNodes.ROUTE_CONTRACT_VIOLATION.equals(route)) {
            throw new IncidentTriageException(IncidentTriageFailure.GRAPH_FAILURE);
        }
        return route;
    }

    private Map<String, Object> generateAnswer(OverAllState state) {
        IncidentTriageCall call = requireCall(state);
        requireResults(call);

        // 送给模型的问题与送给检索链路的问题逐字符相同（同一个规范化实现）
        IncidentTriagePrompt prompt = IncidentTriagePromptBuilder.build(
                KnowledgeQueryNormalizer.normalize(call.getCommand().question()), call.getKnowledge(),
                call.getAsset(), call.getMonitoring());

        String rawAnswer;
        try {
            rawAnswer = this.deepSeekChatClient.prompt()
                    .system(prompt.systemPrompt())
                    .user(prompt.userPrompt())
                    .call()
                    .content();
        }
        catch (RuntimeException ex) {
            throw new IncidentTriageException(IncidentTriageFailure.MODEL_CALL_FAILED, ex);
        }
        if (rawAnswer == null || rawAnswer.strip().isEmpty()) {
            throw new IncidentTriageException(IncidentTriageFailure.ANSWER_EMPTY);
        }

        call.setAnswer(rawAnswer);
        return update(IncidentTriageNodes.GENERATE_ANSWER);
    }

    private Map<String, Object> validateCitations(OverAllState state) {
        IncidentTriageCall call = requireCall(state);
        requireResults(call);

        List<String> used = IncidentTriageCitationValidator.requireValidCitations(call.getAnswer(),
                call.getKnowledge(), call.getAsset(), call.getMonitoring());
        call.setUsedEvidenceIds(used);
        return update(IncidentTriageNodes.VALIDATE_CITATIONS);
    }

    private Map<String, Object> fallbackAnswer(OverAllState state) {
        IncidentTriageCall call = requireCall(state);
        requireResults(call);

        boolean anyFailure = call.getKnowledge().isFailed() || call.getAsset().isFailed()
                || call.getMonitoring().isFailed();

        call.setAnswer(anyFailure ? INSUFFICIENT_EVIDENCE_ANSWER : NO_EVIDENCE_ANSWER);
        call.setUsedEvidenceIds(List.of());
        return update(IncidentTriageNodes.FALLBACK_ANSWER);
    }

    private Map<String, Object> contractViolation(OverAllState state) {
        return update(IncidentTriageNodes.CONTRACT_VIOLATION);
    }

    private Map<String, Object> finish(OverAllState state) {
        return update(IncidentTriageNodes.FINISH);
    }

    // ---------- 状态读取（集中、有界、类型明确） ----------

    /**
     * 读取本次调用的上下文对象。
     *
     * @param state 当前状态
     * @return 调用上下文
     * @throws IncidentTriageException 状态里没有调用上下文（图装配/输入错误）
     */
    private static IncidentTriageCall requireCall(OverAllState state) {
        try {
            return state.value(IncidentTriageStateKeys.CALL, IncidentTriageCall.class)
                    .orElseThrow(() -> new IncidentTriageException(IncidentTriageFailure.GRAPH_FAILURE));
        }
        catch (IncidentTriageException ex) {
            throw ex;
        }
        catch (RuntimeException ex) {
            // 状态里的调用上下文类型不符：收敛为稳定失败，不让框架异常直接穿透
            throw new IncidentTriageException(IncidentTriageFailure.GRAPH_FAILURE, ex);
        }
    }

    /**
     * 校验三个证据结果都已就位；缺失说明节点没有按契约写入。
     *
     * @param call 调用上下文
     * @throws IncidentTriageException 三个结果里有缺失
     */
    private static void requireResults(IncidentTriageCall call) {
        if (call.getKnowledge() == null || call.getAsset() == null || call.getMonitoring() == null) {
            throw new IncidentTriageException(IncidentTriageFailure.GRAPH_FAILURE);
        }
    }

    /**
     * 供服务层与测试读取最终状态里的调用上下文。
     *
     * @param state 最终状态
     * @return 调用上下文
     */
    static IncidentTriageCall readCall(OverAllState state) {
        return requireCall(state);
    }

    /**
     * 构造一个只含「本节点已执行」的增量更新。
     *
     * @param node 节点名
     * @return 可变增量（调用方继续 put 其它键）
     */
    private static Map<String, Object> update(String node) {
        Map<String, Object> update = new LinkedHashMap<>();
        update.put(IncidentTriageStateKeys.EXECUTION_PATH, node);
        return update;
    }

    /**
     * 把检索用例的错误码映射为知识分支的稳定失败分类。
     *
     * @param errorCode 检索错误码
     * @return 知识失败分类
     */
    private static KnowledgeFailure knowledgeFailure(KnowledgeApplicationErrorCode errorCode) {
        return switch (errorCode) {
            case KNOWLEDGE_EMBEDDING_DISABLED -> KnowledgeFailure.DISABLED;
            case EMBEDDING_PROVIDER_ERROR -> KnowledgeFailure.EMBEDDING_PROVIDER_UNAVAILABLE;
            case RERANK_PROVIDER_ERROR -> KnowledgeFailure.RERANK_PROVIDER_UNAVAILABLE;
            default -> KnowledgeFailure.RETRIEVAL_FAILURE;
        };
    }
}
