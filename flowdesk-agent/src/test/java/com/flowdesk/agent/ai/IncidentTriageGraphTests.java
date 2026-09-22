package com.flowdesk.agent.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.flowdesk.application.ai.AiProviderException;
import com.flowdesk.application.ai.AiRequestException;
import com.flowdesk.application.ai.IncidentTriageCommand;
import com.flowdesk.application.ai.IncidentTriageResult;
import com.flowdesk.application.ai.KnowledgeEvidence;
import com.flowdesk.application.ai.KnowledgeFailure;
import com.flowdesk.application.integration.AssetQueryResult;
import com.flowdesk.application.integration.AssetView;
import com.flowdesk.application.integration.HealthState;
import com.flowdesk.application.integration.MonitoringSnapshotQueryResult;
import com.flowdesk.application.integration.MonitoringSnapshotView;
import com.flowdesk.application.integration.QueryFailure;
import com.flowdesk.application.integration.QueryOutcome;
import com.flowdesk.application.integration.SourceOrigin;
import com.flowdesk.application.integration.port.out.AssetQueryPort;
import com.flowdesk.application.integration.port.out.MonitoringSnapshotQueryPort;
import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.application.knowledge.port.in.RetrieveKnowledgeUseCase;
import com.flowdesk.application.knowledge.query.RetrieveKnowledgeQuery;
import com.flowdesk.application.knowledge.view.KnowledgeCitationView;
import com.flowdesk.application.knowledge.view.KnowledgeRetrievalView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 事件研判 Graph 的执行语义（FD-0018-A）。
 *
 * <p>本类执行的是<b>真实的 CompiledGraph</b>（真实 StateGraph + 真实条件边 + 真实状态合并），
 * 而不是直接调用各个节点方法：断言里的 {@code executionPath} 是节点在执行时自己追加的，
 * 路由是框架按条件边的返回值选出来的。</p>
 */
class IncidentTriageGraphTests {

    private static final String ASSET_ID = "AST-900001";

    private static final String QUESTION = "系统告警：该资产 CPU 持续偏高，如何处理？";

    private static final String FULL_ANSWER = "现象 [K1]，影响 [K2]，资产在保 [A1]，监控负载偏高 [M1]。";

    private static final List<String> FULL_PATH = List.of("validate_asset", "retrieve_knowledge", "query_asset",
            "query_monitoring", "verify_contracts", "evidence_gate", "generate_answer", "validate_citations",
            "finish");

    private static final List<String> FALLBACK_PATH = List.of("validate_asset", "retrieve_knowledge",
            "query_asset", "query_monitoring", "verify_contracts", "evidence_gate", "fallback_answer", "finish");

    @Test
    void aFullEvidenceRunExecutesTheWholeGraphAndCitesEveryFamily() {
        Fixture fixture = new Fixture(FULL_ANSWER);

        IncidentTriageResult result = fixture.triage();

        assertThat(result.grounded()).isTrue();
        assertThat(result.usedEvidenceIds()).containsExactly("K1", "K2", "A1", "M1");
        assertThat(result.executionPath()).as("真实执行路径").containsExactlyElementsOf(FULL_PATH);
        assertThat(result.knowledge().status()).isEqualTo(KnowledgeEvidence.Status.FOUND);
        assertThat(result.asset().outcome()).isEqualTo(QueryOutcome.FOUND);
        assertThat(result.monitoring().outcome()).isEqualTo(QueryOutcome.FOUND);
        assertThat(fixture.model.calls()).isEqualTo(1);
        assertThat(fixture.retrieval.calls()).isEqualTo(1);
        assertThat(fixture.assetPort.calls()).isEqualTo(1);
        assertThat(fixture.monitoringPort.calls()).isEqualTo(1);
    }

    @Test
    void knowledgeOnlyEvidenceStillGeneratesAnAnswer() {
        Fixture fixture = new Fixture("只有知识证据 [K1]。");
        fixture.assetPort.returns(AssetQueryResult.notFound(ASSET_ID, SourceOrigin.DEMO));
        fixture.monitoringPort.returns(MonitoringSnapshotQueryResult.notFound(ASSET_ID, SourceOrigin.DEMO));

        IncidentTriageResult result = fixture.triage();

        assertThat(result.usedEvidenceIds()).containsExactly("K1");
        assertThat(result.executionPath()).containsExactlyElementsOf(FULL_PATH);
        assertThat(result.asset().outcome()).as("另一侧的真实状态必须保留").isEqualTo(QueryOutcome.NOT_FOUND);
        assertThat(result.monitoring().outcome()).isEqualTo(QueryOutcome.NOT_FOUND);
    }

    @Test
    void assetOnlyEvidenceStillGeneratesAnAnswer() {
        Fixture fixture = new Fixture("只有资产证据 [A1]。");
        fixture.retrieval.returns(emptyRetrieval());
        fixture.monitoringPort.returns(MonitoringSnapshotQueryResult.notFound(ASSET_ID, SourceOrigin.DEMO));

        IncidentTriageResult result = fixture.triage();

        assertThat(result.usedEvidenceIds()).containsExactly("A1");
        assertThat(result.executionPath()).containsExactlyElementsOf(FULL_PATH);
        assertThat(result.knowledge().status()).isEqualTo(KnowledgeEvidence.Status.NOT_FOUND);
    }

    @Test
    void monitoringOnlyEvidenceStillGeneratesAnAnswer() {
        Fixture fixture = new Fixture("只有监控证据 [M1]。");
        fixture.retrieval.returns(emptyRetrieval());
        fixture.assetPort.returns(AssetQueryResult.failed(QueryFailure.DISABLED));

        IncidentTriageResult result = fixture.triage();

        assertThat(result.usedEvidenceIds()).containsExactly("M1");
        assertThat(result.asset().failure()).as("失败保持原义").isEqualTo(QueryFailure.DISABLED);
        assertThat(result.asset().outcome()).isEqualTo(QueryOutcome.FAILED);
        assertThat(fixture.model.calls()).isEqualTo(1);
    }

    @Test
    void allSourcesMissWithoutFailureUsesTheFixedNoEvidenceAnswer() {
        Fixture fixture = new Fixture("绝不该被调用。");
        fixture.retrieval.returns(emptyRetrieval());
        fixture.assetPort.returns(AssetQueryResult.notFound(ASSET_ID, SourceOrigin.DEMO));
        fixture.monitoringPort.returns(MonitoringSnapshotQueryResult.notFound(ASSET_ID, SourceOrigin.DEMO));

        IncidentTriageResult result = fixture.triage();

        assertThat(result.answer()).isEqualTo(IncidentTriageGraph.NO_EVIDENCE_ANSWER);
        assertThat(result.grounded()).isFalse();
        assertThat(result.usedEvidenceIds()).isEmpty();
        assertThat(result.executionPath()).containsExactlyElementsOf(FALLBACK_PATH);
        assertThat(result.executionPath()).doesNotContain("generate_answer", "validate_citations");
        assertThat(fixture.model.calls()).as("没有证据不得调用模型").isZero();
    }

    @Test
    void aFailedSourceWithoutEvidenceUsesTheFixedDegradedAnswer() {
        Fixture fixture = new Fixture("绝不该被调用。");
        fixture.retrieval.fails(new KnowledgeApplicationException(KnowledgeApplicationErrorCode
                .KNOWLEDGE_EMBEDDING_DISABLED, "向量化未启用"));
        fixture.assetPort.returns(AssetQueryResult.notFound(ASSET_ID, SourceOrigin.DEMO));
        fixture.monitoringPort.returns(MonitoringSnapshotQueryResult.notFound(ASSET_ID, SourceOrigin.DEMO));

        IncidentTriageResult result = fixture.triage();

        assertThat(result.answer()).isEqualTo(IncidentTriageGraph.INSUFFICIENT_EVIDENCE_ANSWER);
        assertThat(result.grounded()).isFalse();
        assertThat(result.usedEvidenceIds()).isEmpty();
        assertThat(result.executionPath()).containsExactlyElementsOf(FALLBACK_PATH);
        assertThat(result.knowledge().isFailed()).isTrue();
        assertThat(fixture.model.calls()).isZero();
    }

    @Test
    void everyEvidenceNodeRunsAtMostOnceAndInAFixedOrder() {
        Fixture fixture = new Fixture(FULL_ANSWER);
        fixture.retrieval.fails(new KnowledgeApplicationException(KnowledgeApplicationErrorCode
                .EMBEDDING_PROVIDER_ERROR, "embedding 上游故障"));
        fixture.assetPort.returns(AssetQueryResult.failed(QueryFailure.TIMEOUT));
        fixture.monitoringPort.returns(MonitoringSnapshotQueryResult.notFound(ASSET_ID, SourceOrigin.DEMO));

        IncidentTriageResult result = fixture.triage();

        assertThat(fixture.retrieval.calls()).as("知识检索最多一次").isEqualTo(1);
        assertThat(fixture.assetPort.calls()).as("资产查询最多一次").isEqualTo(1);
        assertThat(fixture.monitoringPort.calls()).as("监控查询最多一次").isEqualTo(1);
        assertThat(result.executionPath()).containsExactlyElementsOf(FALLBACK_PATH);
        assertThat(result.knowledge().failure()).isEqualTo(KnowledgeFailure.EMBEDDING_PROVIDER_UNAVAILABLE);
        assertThat(result.asset().failure()).isEqualTo(QueryFailure.TIMEOUT);
    }

    @Test
    void anInvalidAssetIdStopsTheGraphBeforeAnyDependencyOrModelCall() {
        Fixture fixture = new Fixture("绝不该被调用。");

        for (String assetId : new String[] { null, "", "   ", "AST-1", "ast-900001", "AST-900001 ", "AST-9000011",
                "AST_900001" }) {
            assertThatThrownBy(() -> fixture.service.triage(
                    new IncidentTriageCommand(assetId, QUESTION, null, null)))
                    .as("assetId=[%s]", assetId)
                    .isInstanceOf(AiRequestException.class)
                    .hasMessage(IncidentTriageGraph.INVALID_ASSET_ID_MESSAGE);
        }
        assertThatThrownBy(() -> fixture.service.triage(null)).isInstanceOf(AiRequestException.class);

        assertThat(fixture.retrieval.calls()).as("非法 assetId：知识检索零调用").isZero();
        assertThat(fixture.assetPort.calls()).isZero();
        assertThat(fixture.monitoringPort.calls()).isZero();
        assertThat(fixture.model.calls()).isZero();
    }

    @Test
    void anInvalidRetrievalInputStopsBeforeTheTwoMcpQueriesAndTheModel() {
        Fixture fixture = new Fixture("绝不该被调用。");
        fixture.retrieval.fails(new KnowledgeApplicationException(
                KnowledgeApplicationErrorCode.INVALID_RETRIEVAL_QUERY, "问题不能为空"));

        assertThatThrownBy(() -> fixture.service.triage(new IncidentTriageCommand(ASSET_ID, "  ", 0, -1.0)))
                .isInstanceOf(AiRequestException.class)
                .hasMessage("问题不能为空");

        assertThat(fixture.retrieval.calls()).isEqualTo(1);
        assertThat(fixture.assetPort.calls()).as("检索输入非法：资产查询零调用").isZero();
        assertThat(fixture.monitoringPort.calls()).isZero();
        assertThat(fixture.model.calls()).isZero();
    }

    @Test
    void knowledgeFailuresAreClassifiedIntoStableCategoriesAndTheOtherQueriesStillRun() {
        record Case(KnowledgeApplicationErrorCode code, KnowledgeFailure expected) {
        }
        List<Case> cases = List.of(
                new Case(KnowledgeApplicationErrorCode.KNOWLEDGE_EMBEDDING_DISABLED, KnowledgeFailure.DISABLED),
                new Case(KnowledgeApplicationErrorCode.EMBEDDING_PROVIDER_ERROR,
                        KnowledgeFailure.EMBEDDING_PROVIDER_UNAVAILABLE),
                new Case(KnowledgeApplicationErrorCode.RERANK_PROVIDER_ERROR,
                        KnowledgeFailure.RERANK_PROVIDER_UNAVAILABLE),
                new Case(KnowledgeApplicationErrorCode.KNOWLEDGE_RETRIEVAL_FAILURE,
                        KnowledgeFailure.RETRIEVAL_FAILURE));

        for (Case testCase : cases) {
            Fixture fixture = new Fixture("资产在保 [A1]，监控偏高 [M1]。");
            fixture.retrieval.fails(new KnowledgeApplicationException(testCase.code(), "检索没有完成"));

            IncidentTriageResult result = fixture.triage();

            assertThat(result.knowledge().failure()).as("code=%s", testCase.code())
                    .isEqualTo(testCase.expected());
            assertThat(result.knowledge().retrieval()).as("失败时不得携带检索视图").isNull();
            assertThat(fixture.assetPort.calls()).as("知识失败不影响资产查询").isEqualTo(1);
            assertThat(fixture.monitoringPort.calls()).isEqualTo(1);
            assertThat(result.usedEvidenceIds()).containsExactly("A1", "M1");
        }
    }

    @Test
    void anUndeclaredRetrievalFailureIsAContractViolationThatStillQueriesBothPorts() {
        Fixture fixture = new Fixture("绝不该被调用。");
        fixture.retrieval.throwsUnexpectedly(new IllegalStateException("sentinel retrieval exploded"));

        assertThatThrownBy(fixture::triage)
                .isInstanceOf(AiProviderException.class)
                .satisfies(thrown -> {
                    AiProviderException failure = (AiProviderException) thrown;
                    assertThat(failure.requestId()).isNotBlank();
                    assertThat(failure.getMessage()).as("对外文案固定").isEqualTo("上游 AI 服务调用失败");
                    assertThat(failure.getMessage()).doesNotContain("sentinel");
                });

        assertThat(fixture.retrieval.calls()).as("知识检索恰好一次").isEqualTo(1);
        assertThat(fixture.assetPort.calls()).as("未声明的检索异常不是「知识失败」，后续证据照常查询").isEqualTo(1);
        assertThat(fixture.monitoringPort.calls()).isEqualTo(1);
        assertThat(fixture.model.calls()).as("违约在调用模型前统一失败").isZero();
    }

    @Test
    void aNullRetrievalResultIsTheSameContractViolation() {
        Fixture fixture = new Fixture("绝不该被调用。");
        fixture.retrieval.returnsNull();

        assertThatThrownBy(fixture::triage).isInstanceOf(AiProviderException.class);

        assertThat(fixture.assetPort.calls()).isEqualTo(1);
        assertThat(fixture.monitoringPort.calls()).isEqualTo(1);
        assertThat(fixture.model.calls()).isZero();
    }

    @Test
    void aModelAiRequestExceptionIsNeverClassifiedAsAnInputError() {
        String sentinel = "review-provider-message-sentinel";
        Fixture fixture = new Fixture(new AiRequestException(sentinel));

        assertThatThrownBy(fixture::triage)
                .as("模型阶段抛出的输入异常不得被当成 400")
                .isInstanceOf(AiProviderException.class)
                .isNotInstanceOf(AiRequestException.class)
                .satisfies(thrown -> {
                    AiProviderException failure = (AiProviderException) thrown;
                    assertThat(failure.requestId()).isNotBlank();
                    assertThat(failure.getMessage()).isEqualTo("上游 AI 服务调用失败");
                    assertThat(failure.getMessage()).doesNotContain(sentinel);
                    assertThat(triageFailureOf(thrown)).as("保持模型失败分类").isEqualTo(
                            IncidentTriageFailure.MODEL_CALL_FAILED);
                    assertThat(messagesOf(thrown)).as("哨兵确实存在过，只是不外泄").contains(sentinel);
                });

        assertThat(fixture.model.calls()).as("模型只调用一次").isEqualTo(1);
    }

    @Test
    void aModelExceptionWrappingAnAiRequestExceptionStaysAModelFailure() {
        String sentinel = "review-provider-message-sentinel";
        Fixture fixture = new Fixture(
                new IllegalStateException("model-wrapper-sentinel", new AiRequestException(sentinel)));

        assertThatThrownBy(fixture::triage)
                .isInstanceOf(AiProviderException.class)
                .isNotInstanceOf(AiRequestException.class)
                .satisfies(thrown -> {
                    assertThat(((AiProviderException) thrown).requestId()).isNotBlank();
                    assertThat(thrown.getMessage()).doesNotContain(sentinel).doesNotContain("model-wrapper-sentinel");
                    assertThat(triageFailureOf(thrown)).isEqualTo(IncidentTriageFailure.MODEL_CALL_FAILED);
                    assertThat(messagesOf(thrown)).contains(sentinel);
                });

        assertThat(fixture.model.calls()).isEqualTo(1);
    }

    @Test
    void aNestedCitationAnswerFailsTheGraphWithoutRepairOrRetry() {
        Fixture fixture = new Fixture("现象 [K1]，资产 [[A1]]，监控 [M1]。");

        assertThatThrownBy(fixture::triage)
                .isInstanceOf(AiProviderException.class)
                .satisfies(thrown -> {
                    AiProviderException failure = (AiProviderException) thrown;
                    assertThat(failure.requestId()).isNotBlank();
                    assertThat(failure.getMessage()).isEqualTo("上游 AI 服务调用失败");
                    assertThat(messagesOf(thrown)).doesNotContain("[[A1]]");
                });

        assertThat(fixture.model.calls()).as("不修正、不重新调用模型").isEqualTo(1);
        assertThat(fixture.assetPort.calls()).isEqualTo(1);
        assertThat(fixture.monitoringPort.calls()).isEqualTo(1);
    }

    @Test
    void aKnowledgeFailureStillAllowsPartialEvidenceGeneration() {
        Fixture fixture = new Fixture("资产在保 [A1]，监控偏高 [M1]。");
        fixture.retrieval.fails(new KnowledgeApplicationException(
                KnowledgeApplicationErrorCode.EMBEDDING_PROVIDER_ERROR, "embedding 上游故障"));

        IncidentTriageResult result = fixture.triage();

        assertThat(result.grounded()).isTrue();
        assertThat(result.usedEvidenceIds()).containsExactly("A1", "M1");
        assertThat(result.knowledge().isFailed()).isTrue();
        assertThat(fixture.assetPort.calls()).isEqualTo(1);
        assertThat(fixture.monitoringPort.calls()).isEqualTo(1);
        assertThat(fixture.model.calls()).isEqualTo(1);
    }

    @Test
    void aNullDependencyResultIsAContractViolationThatStillQueriesTheRest() {
        Fixture fixture = new Fixture("绝不该被调用。");
        fixture.assetPort.returnsNull();

        assertThatThrownBy(fixture::triage)
                .isInstanceOf(AiProviderException.class)
                .satisfies(thrown -> {
                    AiProviderException failure = (AiProviderException) thrown;
                    assertThat(failure.requestId()).isNotBlank();
                    assertThat(failure.getMessage()).as("对外文案固定").isEqualTo("上游 AI 服务调用失败");
                    assertThat(failure.getMessage()).doesNotContain("NullPointer");
                });

        assertThat(fixture.monitoringPort.calls()).as("违约后后续证据节点仍要执行").isEqualTo(1);
        assertThat(fixture.model.calls()).as("违约在调用模型前统一失败").isZero();
    }

    @Test
    void aThrowingDependencyIsAContractViolationToo() {
        Fixture fixture = new Fixture("绝不该被调用。");
        fixture.monitoringPort.throwsUnexpectedly(new IllegalStateException("sentinel-monitoring-broken"));

        assertThatThrownBy(fixture::triage).isInstanceOf(AiProviderException.class);

        assertThat(fixture.model.calls()).isZero();
    }

    @Test
    void theThreeMcpAndKnowledgeStatesKeepTheirOriginalMeaning() {
        Fixture fixture = new Fixture("资产不可用但监控偏高 [M1]。");
        fixture.retrieval.returns(emptyRetrieval());
        fixture.assetPort.returns(AssetQueryResult.failed(QueryFailure.UNAVAILABLE));

        IncidentTriageResult result = fixture.triage();

        assertThat(result.asset().outcome()).as("失败绝不能被伪装成未找到").isEqualTo(QueryOutcome.FAILED);
        assertThat(result.asset().isNotFound()).isFalse();
        assertThat(result.asset().failure()).isEqualTo(QueryFailure.UNAVAILABLE);
        assertThat(result.knowledge().status()).isEqualTo(KnowledgeEvidence.Status.NOT_FOUND);
        assertThat(result.monitoring().outcome()).isEqualTo(QueryOutcome.FOUND);
    }

    @Test
    void aWrongTypedInputStateFailsBounded() {
        Fixture fixture = new Fixture(FULL_ANSWER);

        assertThatThrownBy(() -> fixture.graph.compiled()
                .invoke(Map.of(IncidentTriageStateKeys.CALL, "not-a-call"),
                        IncidentTriageGraph.runConfig("wrong-type")))
                .as("状态类型不符必须有界失败")
                .satisfies(thrown -> {
                    assertThat(hasCause(thrown, IncidentTriageException.class)).isTrue();
                    assertThat(hasCause(thrown, NullPointerException.class))
                            .as("不允许裸 NullPointerException 穿透")
                            .isFalse();
                });
    }

    @Test
    void aMissingOrWrongTypedFinalStateFailsBounded() {
        OverAllState empty = new OverAllState(Map.of());
        assertThatThrownBy(() -> IncidentTriageService.requireRoute(empty))
                .isInstanceOfSatisfying(IncidentTriageException.class,
                        ex -> assertThat(ex.failure()).isEqualTo(IncidentTriageFailure.GRAPH_FAILURE));
        assertThatThrownBy(() -> IncidentTriageService.readPath(empty))
                .isInstanceOfSatisfying(IncidentTriageException.class,
                        ex -> assertThat(ex.failure()).isEqualTo(IncidentTriageFailure.GRAPH_FAILURE));

        OverAllState wrongType = new OverAllState(Map.of(IncidentTriageStateKeys.ROUTE, 42,
                IncidentTriageStateKeys.EXECUTION_PATH, List.of("validate_asset")));
        assertThatThrownBy(() -> IncidentTriageService.requireRoute(wrongType))
                .isInstanceOfSatisfying(IncidentTriageException.class,
                        ex -> assertThat(ex.failure()).isEqualTo(IncidentTriageFailure.GRAPH_FAILURE));

        OverAllState badPath = new OverAllState(Map.of(IncidentTriageStateKeys.EXECUTION_PATH,
                List.of(1, 2, 3)));
        assertThatThrownBy(() -> IncidentTriageService.readPath(badPath))
                .isInstanceOfSatisfying(IncidentTriageException.class,
                        ex -> assertThat(ex.failure()).isEqualTo(IncidentTriageFailure.GRAPH_FAILURE));
    }

    private static boolean hasCause(Throwable thrown, Class<? extends Throwable> type) {
        Throwable current = thrown;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 在<b>有界</b>的 cause 链上找服务端稳定失败分类（用于断言「分类没有被输入异常覆盖」）。
     *
     * @param thrown 抛出的异常
     * @return 找到的失败分类；没有则为 {@code null}
     */
    private static IncidentTriageFailure triageFailureOf(Throwable thrown) {
        Throwable current = thrown;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof IncidentTriageException triage) {
                return triage.failure();
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * 收集整个 cause 链上的异常消息（用于「先证明哨兵存在，再证明不外泄」）。
     *
     * @param thrown 抛出的异常
     * @return 消息列表
     */
    private static List<String> messagesOf(Throwable thrown) {
        List<String> messages = new ArrayList<>();
        Throwable current = thrown;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current.getMessage() != null) {
                messages.add(current.getMessage());
            }
            current = current.getCause();
        }
        return messages;
    }

    @Test
    void concurrentRunsDoNotLeakStateBetweenRequests() throws Exception {
        Fixture fixture = new Fixture((String) null);
        fixture.retrieval.answersPerQuestion();
        fixture.assetPort.answersPerAssetId();

        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<IncidentTriageResult>> tasks = new ArrayList<>();
            List<String> assetIds = new ArrayList<>();
            for (int index = 0; index < 12; index++) {
                String assetId = String.format("AST-9%05d", index + 1);
                assetIds.add(assetId);
                tasks.add(() -> fixture.service.triage(new IncidentTriageCommand(assetId,
                        "事件-" + assetId, 3, 0.3)));
            }
            List<Future<IncidentTriageResult>> futures = executor.invokeAll(tasks);

            List<String> requestIds = new ArrayList<>();
            for (int index = 0; index < futures.size(); index++) {
                IncidentTriageResult result = futures.get(index).get();
                String assetId = assetIds.get(index);
                requestIds.add(result.requestId());

                assertThat(result.asset().requireAsset().assetId()).as("证据不得串线").isEqualTo(assetId);
                assertThat(result.answer()).contains(assetId);
                assertThat(result.usedEvidenceIds()).containsExactly("K1", "A1", "M1");
                assertThat(result.executionPath()).containsExactlyElementsOf(FULL_PATH);
            }
            assertThat(requestIds).as("每次调用有各自的 requestId").doesNotHaveDuplicates();
            assertThat(fixture.retrieval.calls()).as("知识检索各调用一次").isEqualTo(12);
            assertThat(fixture.assetPort.calls()).as("资产端口各调用一次").isEqualTo(12);
            assertThat(fixture.monitoringPort.calls()).as("监控端口各调用一次").isEqualTo(12);
            assertThat(fixture.model.calls()).as("模型各调用一次").isEqualTo(12);
        }
        finally {
            executor.shutdownNow();
        }
    }

    private static KnowledgeRetrievalView emptyRetrieval() {
        return KnowledgeRetrievalView.vectorOrdered("dashscope", "text-embedding-v4", 1024, 5, 0.3, List.of());
    }

    private static KnowledgeRetrievalView retrievalWithCitations(int count) {
        List<KnowledgeCitationView> citations = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            citations.add(KnowledgeCitationView.vectorOnly("K" + index, index,
                    UUID.fromString("11111111-2222-3333-4444-555555555555"), 1L,
                    "VPN 故障处理手册", index - 1, "0".repeat(64), "第 " + index + " 段正文", 0.9 - index * 0.1));
        }
        return KnowledgeRetrievalView.vectorOrdered("dashscope", "text-embedding-v4", 1024, 5, 0.3,
                List.copyOf(citations));
    }

    /** 把三个替身、假模型与真实 Graph 编排装到一起。 */
    private static final class Fixture {

        private final FakeRetrieval retrieval = new FakeRetrieval();

        private final FakeAssetPort assetPort = new FakeAssetPort();

        private final FakeMonitoringPort monitoringPort = new FakeMonitoringPort();

        private final FakeChatModel model;

        private final IncidentTriageGraph graph;

        private final IncidentTriageService service;

        Fixture(String answer) {
            // answer 为 null 时由假模型按本次提示词里的 assetId 生成答案（并发用例据此判断有没有串线）
            this.model = new FakeChatModel(answer);
            this.graph = new IncidentTriageGraph(this.retrieval, this.assetPort, this.monitoringPort,
                    ChatClient.create(this.model));
            this.service = new IncidentTriageService(this.retrieval, this.assetPort, this.monitoringPort,
                    ChatClient.create(this.model));
        }

        /**
         * 让模型在调用时抛出指定异常（用于模型阶段的失败语义）。
         *
         * @param failure 模型抛出的异常
         */
        Fixture(RuntimeException failure) {
            this.model = new FakeChatModel(failure);
            this.graph = new IncidentTriageGraph(this.retrieval, this.assetPort, this.monitoringPort,
                    ChatClient.create(this.model));
            this.service = new IncidentTriageService(this.retrieval, this.assetPort, this.monitoringPort,
                    ChatClient.create(this.model));
        }

        IncidentTriageResult triage() {
            return this.service.triage(new IncidentTriageCommand(ASSET_ID, QUESTION, 5, 0.3));
        }
    }

    private static final class FakeRetrieval implements RetrieveKnowledgeUseCase {

        private final List<RetrieveKnowledgeQuery> queries = new CopyOnWriteArrayList<>();

        private KnowledgeRetrievalView view = retrievalWithCitations(2);

        private KnowledgeApplicationException failure;

        private RuntimeException unexpected;

        private boolean perQuestion;

        private boolean nullView;

        @Override
        public KnowledgeRetrievalView retrieve(RetrieveKnowledgeQuery query) {
            this.queries.add(query);
            if (this.failure != null) {
                throw this.failure;
            }
            if (this.unexpected != null) {
                throw this.unexpected;
            }
            if (this.nullView) {
                return null;
            }
            if (this.perQuestion) {
                return retrievalWithCitations(1);
            }
            return this.view;
        }

        void returns(KnowledgeRetrievalView view) {
            this.view = view;
        }

        void fails(KnowledgeApplicationException failure) {
            this.failure = failure;
        }

        void throwsUnexpectedly(RuntimeException unexpected) {
            this.unexpected = unexpected;
        }

        void answersPerQuestion() {
            this.perQuestion = true;
        }

        void returnsNull() {
            this.nullView = true;
        }

        int calls() {
            return this.queries.size();
        }
    }

    private static final class FakeAssetPort implements AssetQueryPort {

        private final AtomicInteger calls = new AtomicInteger();

        private AssetQueryResult result = AssetQueryResult.found(
                new AssetView(ASSET_ID, "SERVER", "IN_SERVICE", SourceOrigin.DEMO));

        private boolean nullResult;

        private boolean perAssetId;

        @Override
        public AssetQueryResult findAsset(String assetId) {
            this.calls.incrementAndGet();
            if (this.nullResult) {
                return null;
            }
            if (this.perAssetId) {
                return AssetQueryResult.found(new AssetView(assetId, "SERVER", "IN_SERVICE", SourceOrigin.DEMO));
            }
            return this.result;
        }

        void returns(AssetQueryResult result) {
            this.result = result;
        }

        void returnsNull() {
            this.nullResult = true;
        }

        void answersPerAssetId() {
            this.perAssetId = true;
        }

        int calls() {
            return this.calls.get();
        }
    }

    private static final class FakeMonitoringPort implements MonitoringSnapshotQueryPort {

        private final AtomicInteger calls = new AtomicInteger();

        private MonitoringSnapshotQueryResult result = MonitoringSnapshotQueryResult.found(
                new MonitoringSnapshotView(ASSET_ID, Instant.parse("2026-01-01T00:00:00Z"), HealthState.DEGRADED,
                        92, 68, 1, SourceOrigin.DEMO));

        private RuntimeException unexpected;

        @Override
        public MonitoringSnapshotQueryResult findLatestSnapshot(String assetId) {
            this.calls.incrementAndGet();
            if (this.unexpected != null) {
                throw this.unexpected;
            }
            return this.result;
        }

        void returns(MonitoringSnapshotQueryResult result) {
            this.result = result;
        }

        void throwsUnexpectedly(RuntimeException unexpected) {
            this.unexpected = unexpected;
        }

        int calls() {
            return this.calls.get();
        }
    }

    private static final class FakeChatModel implements ChatModel {

        private final List<Prompt> prompts = new CopyOnWriteArrayList<>();

        private final String fixedAnswer;

        private final RuntimeException failure;

        FakeChatModel(String fixedAnswer) {
            this.fixedAnswer = fixedAnswer;
            this.failure = null;
        }

        FakeChatModel(RuntimeException failure) {
            this.fixedAnswer = null;
            this.failure = failure;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            this.prompts.add(prompt);
            if (this.failure != null) {
                throw this.failure;
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(answerFor(prompt)))));
        }

        /**
         * 固定答案优先；没有固定答案时从提示词里取本次 assetId，构造与之对应的答案 ——
         * 这样并发用例可以判断「答案有没有串线」。
         *
         * @param prompt 模型收到的提示词
         * @return 答案
         */
        private String answerFor(Prompt prompt) {
            if (this.fixedAnswer != null) {
                return this.fixedAnswer;
            }
            String user = prompt.getInstructions().get(prompt.getInstructions().size() - 1).getText();
            String marker = "\"assetId\":\"";
            int start = user.indexOf(marker);
            String assetId = start < 0 ? "unknown" : user.substring(start + marker.length(),
                    Math.min(user.length(), start + marker.length() + 10));
            return "资产 " + assetId + " 研判：现象 [K1]，资产在保 [A1]，监控偏高 [M1]。";
        }

        int calls() {
            return this.prompts.size();
        }
    }
}
