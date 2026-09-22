package com.flowdesk.agent.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flowdesk.application.ai.AiProviderException;
import com.flowdesk.application.ai.AiRequestException;
import com.flowdesk.application.ai.IncidentTriageCommand;
import com.flowdesk.application.ai.IncidentTriageResult;
import com.flowdesk.application.integration.AssetQueryResult;
import com.flowdesk.application.integration.AssetView;
import com.flowdesk.application.integration.HealthState;
import com.flowdesk.application.integration.MonitoringSnapshotQueryResult;
import com.flowdesk.application.integration.MonitoringSnapshotView;
import com.flowdesk.application.integration.QueryFailure;
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
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 事件研判日志的结构、脱敏与<b>真实执行进度</b>（FD-0018-A / R1）。
 *
 * <p>只记录固定元数据，因此这里用「模板固定 + 参数位逐个钉死」的方式断言：模板必须是两条固定文案
 * 之一，参数位只能是操作名、请求标识、三个来源状态、路由、布尔、计数、稳定失败类别、异常类名与
 * 唯一的数值参数（耗时）。assetId、问题原文、知识正文、资产详情、监控数值、模型回答、提示词与
 * 异常消息<b>没有任何位置可放</b>。</p>
 *
 * <p>R1 起还逐项断言<b>实际执行进度</b>：失败发生在图内部（框架不交出最终状态）时，
 * 三个来源状态、{@code graphRoute}、{@code modelCalled} 与两个计数必须来自已经发生的事 ——
 * 例如完整证据之后的模型失败必须记 {@code route=evidence_available} 且 {@code modelCalled=true}，
 * 引用校验失败不得记一个「已通过校验」的引用数量。</p>
 */
class IncidentTriageLoggingTests {

    private static final String COMPLETED_TEMPLATE =
            "{} completed operation={} requestId={} knowledgeStatus={} assetOutcome={} monitoringOutcome={} "
                    + "graphRoute={} modelCalled={} evidenceCount={} usedEvidenceCount={} success=true durationMs={}";

    private static final String FAILED_TEMPLATE =
            "{} failed operation={} requestId={} knowledgeStatus={} assetOutcome={} monitoringOutcome={} "
                    + "graphRoute={} modelCalled={} evidenceCount={} usedEvidenceCount={} failure={} exception={} "
                    + "success=false durationMs={}";

    private static final String OPERATION = IncidentTriageService.OPERATION;

    private static final String NOT_QUERIED = IncidentTriageService.NOT_QUERIED;

    private static final String ROUTE_UNKNOWN = IncidentTriageService.ROUTE_UNKNOWN;

    private static final String PORT_CONTRACT_VIOLATION =
            IncidentTriageFailure.PORT_CONTRACT_VIOLATION.name();

    private static final String INVALID_INPUT = IncidentTriageFailure.INVALID_INPUT.name();

    private static final String QUESTION_SENTINEL = "SENTINELQUESTION";

    private static final String CONTENT_SENTINEL = "SENTINELCHUNKBODY";

    private static final String ANSWER_SENTINEL = "SENTINELANSWER";

    private static final String UPSTREAM_SENTINEL = "sentinel-upstream-detail";

    private static final String PROVIDER_SENTINEL = "review-provider-message-sentinel";

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attachAppender() {
        this.appender.start();
        logger().addAppender(this.appender);
    }

    @AfterEach
    void detachAppender() {
        logger().detachAppender(this.appender);
        this.appender.stop();
    }

    @Test
    void aSuccessfulTriageLogsExactlyOneStructuredLine() {
        Fixture fixture = new Fixture(ANSWER_SENTINEL + " 现象 [K1]，资产 [A1]，监控 [M1]。");

        IncidentTriageResult result = fixture.triage();

        assertThat(result.answer()).contains(ANSWER_SENTINEL);
        assertThat(this.appender.list).hasSize(1);

        Object[] arguments = completedArguments();
        assertThat(arguments[2]).isEqualTo(result.requestId());
        assertThat(arguments[3]).isEqualTo("FOUND");
        assertThat(arguments[4]).isEqualTo("FOUND");
        assertThat(arguments[5]).isEqualTo("FOUND");
        assertThat(arguments[6]).isEqualTo("evidence_available");
        assertThat(arguments[7]).isEqualTo(true);
        assertThat(arguments[8]).isEqualTo(3);
        assertThat(arguments[9]).isEqualTo(3);
        assertNoSensitiveMaterial();
    }

    @Test
    void aFallbackLogsModelCalledFalseAndTheFallbackRoute() {
        Fixture fixture = new Fixture("绝不该被调用。");
        fixture.retrieval.returns(emptyRetrieval());
        fixture.assetPort.returns(AssetQueryResult.notFound("AST-900001", SourceOrigin.DEMO));
        fixture.monitoringPort.returns(MonitoringSnapshotQueryResult.notFound("AST-900001", SourceOrigin.DEMO));

        fixture.triage();

        Object[] arguments = completedArguments();
        assertThat(arguments[3]).isEqualTo("NOT_FOUND");
        assertThat(arguments[4]).isEqualTo("NOT_FOUND");
        assertThat(arguments[5]).isEqualTo("NOT_FOUND");
        assertThat(arguments[6]).isEqualTo("no_evidence");
        assertThat(arguments[7]).isEqualTo(false);
        assertThat(arguments[8]).isEqualTo(0);
        assertThat(arguments[9]).isEqualTo(0);
        assertNoSensitiveMaterial();
    }

    @Test
    void aDeclaredKnowledgeFailureKeepsItsOwnStatusAndStillGeneratesFromTheRest() {
        Fixture fixture = new Fixture("资产 [A1]，监控 [M1]。");
        fixture.retrieval.fails(new KnowledgeApplicationException(
                KnowledgeApplicationErrorCode.EMBEDDING_PROVIDER_ERROR, "embedding 上游故障"));

        fixture.triage();

        Object[] arguments = completedArguments();
        assertThat(arguments[3]).as("知识分支状态").isEqualTo("FAILED");
        assertThat(arguments[4]).isEqualTo("FOUND");
        assertThat(arguments[5]).isEqualTo("FOUND");
        assertThat(arguments[6]).isEqualTo("evidence_available");
        assertThat(arguments[7]).isEqualTo(true);
        assertThat(arguments[8]).as("资产与监控两条证据").isEqualTo(2);
        assertThat(arguments[9]).isEqualTo(2);
        assertNoSensitiveMaterial();
    }

    @Test
    void aModelFailureAfterFullEvidenceLogsTheRealProgress() {
        Fixture fixture = new Fixture(new IllegalStateException(UPSTREAM_SENTINEL));

        assertThatThrownBy(fixture::triage).isInstanceOf(AiProviderException.class);

        Object[] arguments = failureArguments();
        assertThat(arguments[3]).as("知识已经查过").isEqualTo("FOUND");
        assertThat(arguments[4]).as("资产已经查过").isEqualTo("FOUND");
        assertThat(arguments[5]).as("监控已经查过").isEqualTo("FOUND");
        assertThat(arguments[6]).as("真实路由").isEqualTo("evidence_available");
        assertThat(arguments[7]).as("模型确实被调用过").isEqualTo(true);
        assertThat(arguments[8]).as("三条可用证据").isEqualTo(3);
        assertThat(arguments[9]).as("没有任何引用通过校验").isEqualTo(0);
        assertThat(arguments[10]).isEqualTo(IncidentTriageFailure.MODEL_CALL_FAILED.name());
        assertThat(arguments[11]).isEqualTo(IllegalStateException.class.getName());
        assertNoSensitiveMaterial();
    }

    @Test
    void aModelAiRequestExceptionKeepsTheModelFailureCategoryInTheLog() {
        Fixture fixture = new Fixture(new AiRequestException(PROVIDER_SENTINEL));

        assertThatThrownBy(fixture::triage).isInstanceOf(AiProviderException.class);

        Object[] arguments = failureArguments();
        assertThat(arguments[6]).isEqualTo("evidence_available");
        assertThat(arguments[7]).isEqualTo(true);
        assertThat(arguments[8]).isEqualTo(3);
        assertThat(arguments[10]).as("绝不能被记成输入错误").isEqualTo(
                IncidentTriageFailure.MODEL_CALL_FAILED.name());
        assertThat(arguments[11]).isEqualTo(AiRequestException.class.getName());
        assertNoSensitiveMaterial();
    }

    @Test
    void anEmptyModelAnswerLogsTheModelAsAlreadyCalled() {
        Fixture fixture = new Fixture("");

        assertThatThrownBy(fixture::triage).isInstanceOf(AiProviderException.class);

        Object[] arguments = failureArguments();
        assertThat(arguments[3]).isEqualTo("FOUND");
        assertThat(arguments[6]).isEqualTo("evidence_available");
        assertThat(arguments[7]).as("模型已经被调用过").isEqualTo(true);
        assertThat(arguments[8]).isEqualTo(3);
        assertThat(arguments[9]).isEqualTo(0);
        assertThat(arguments[10]).isEqualTo(IncidentTriageFailure.ANSWER_EMPTY.name());
        assertNoSensitiveMaterial();
    }

    @Test
    void aCitationFailureNeverFakesAPassedCitationCount() {
        Fixture fixture = new Fixture("只谈资产 " + ANSWER_SENTINEL + " [A1]。");

        assertThatThrownBy(fixture::triage).isInstanceOf(AiProviderException.class);

        Object[] arguments = failureArguments();
        assertThat(arguments[3]).isEqualTo("FOUND");
        assertThat(arguments[6]).isEqualTo("evidence_available");
        assertThat(arguments[7]).isEqualTo(true);
        assertThat(arguments[8]).as("本次真实存在三条证据").isEqualTo(3);
        assertThat(arguments[9]).as("引用校验没有通过，因此实际引用为 0").isEqualTo(0);
        assertThat(arguments[10]).isEqualTo(IncidentTriageFailure.EVIDENCE_FAMILY_NOT_CITED.name());
        assertNoSensitiveMaterial();
    }

    @Test
    void aNestedCitationAnswerLogsTheMalformedCategory() {
        Fixture fixture = new Fixture("现象 [[K1]]，资产 [A1]，监控 [M1]。");

        assertThatThrownBy(fixture::triage).isInstanceOf(AiProviderException.class);

        Object[] arguments = failureArguments();
        assertThat(arguments[6]).isEqualTo("evidence_available");
        assertThat(arguments[7]).isEqualTo(true);
        assertThat(arguments[8]).isEqualTo(3);
        assertThat(arguments[9]).isEqualTo(0);
        assertThat(arguments[10]).isEqualTo(IncidentTriageFailure.INVALID_CITATION_FORMAT.name());
        assertNoSensitiveMaterial();
    }

    @Test
    void aContractViolationLogsEveryKnownSourceStateAndTheRealRoute() {
        Fixture fixture = new Fixture("绝不该被调用。");
        fixture.assetPort.returnsNull();

        assertThatThrownBy(fixture::triage).isInstanceOf(AiProviderException.class);

        Object[] arguments = failureArguments();
        assertContractViolationLine(arguments, "FOUND", PORT_CONTRACT_VIOLATION, "FOUND");
        assertThat(fixture.retrievalCalls()).isEqualTo(1);
        assertThat(fixture.assetCalls()).isEqualTo(1);
        assertThat(fixture.monitoringCalls()).isEqualTo(1);
        assertThat(fixture.modelCalls()).isZero();
        assertNoSensitiveMaterial();
    }

    @Test
    void anUndeclaredRetrievalFailureIsLoggedAsAContractViolationNotAsKnowledgeFailure() {
        Fixture fixture = new Fixture("绝不该被调用。");
        fixture.retrieval.throwsUnexpectedly(new IllegalStateException(UPSTREAM_SENTINEL));

        assertThatThrownBy(fixture::triage).isInstanceOf(AiProviderException.class);

        Object[] arguments = failureArguments();
        assertContractViolationLine(arguments, PORT_CONTRACT_VIOLATION, "FOUND", "FOUND");
        assertThat(fixture.retrievalCalls()).isEqualTo(1);
        assertThat(fixture.assetCalls()).isEqualTo(1);
        assertThat(fixture.monitoringCalls()).isEqualTo(1);
        assertThat(fixture.modelCalls()).isZero();
        assertNoSensitiveMaterial();
    }

    @Test
    void aNullKnowledgeResultIsLoggedAsAPortContractViolation() {
        Fixture fixture = new Fixture("绝不该被调用。");
        fixture.retrieval.returnsNull();

        assertThatThrownBy(fixture::triage).isInstanceOf(AiProviderException.class);

        assertContractViolationLine(failureArguments(), PORT_CONTRACT_VIOLATION, "FOUND", "FOUND");
        assertEverySourceQueriedExactlyOnce(fixture);
        assertNoSensitiveMaterial();
    }

    @Test
    void anUndeclaredKnowledgeExceptionIsLoggedAsAPortContractViolation() {
        Fixture fixture = new Fixture("绝不该被调用。");
        fixture.retrieval.throwsUnexpectedly(new IllegalStateException(UPSTREAM_SENTINEL));

        assertThatThrownBy(fixture::triage).isInstanceOf(AiProviderException.class);

        assertContractViolationLine(failureArguments(), PORT_CONTRACT_VIOLATION, "FOUND", "FOUND");
        assertEverySourceQueriedExactlyOnce(fixture);
        assertNoSensitiveMaterial();
    }

    @Test
    void aNullAssetResultIsLoggedAsAPortContractViolation() {
        Fixture fixture = new Fixture("绝不该被调用。");
        fixture.assetPort.returnsNull();

        assertThatThrownBy(fixture::triage).isInstanceOf(AiProviderException.class);

        assertContractViolationLine(failureArguments(), "FOUND", PORT_CONTRACT_VIOLATION, "FOUND");
        assertEverySourceQueriedExactlyOnce(fixture);
        assertNoSensitiveMaterial();
    }

    @Test
    void anUndeclaredAssetExceptionIsLoggedAsAPortContractViolation() {
        Fixture fixture = new Fixture("绝不该被调用。");
        fixture.assetPort.throwsUnexpectedly(new IllegalStateException(UPSTREAM_SENTINEL));

        assertThatThrownBy(fixture::triage).isInstanceOf(AiProviderException.class);

        assertContractViolationLine(failureArguments(), "FOUND", PORT_CONTRACT_VIOLATION, "FOUND");
        assertEverySourceQueriedExactlyOnce(fixture);
        assertNoSensitiveMaterial();
    }

    @Test
    void aNullMonitoringResultIsLoggedAsAPortContractViolation() {
        Fixture fixture = new Fixture("绝不该被调用。");
        fixture.monitoringPort.returnsNull();

        assertThatThrownBy(fixture::triage).isInstanceOf(AiProviderException.class);

        assertContractViolationLine(failureArguments(), "FOUND", "FOUND", PORT_CONTRACT_VIOLATION);
        assertEverySourceQueriedExactlyOnce(fixture);
        assertNoSensitiveMaterial();
    }

    @Test
    void anUndeclaredMonitoringExceptionIsLoggedAsAPortContractViolation() {
        Fixture fixture = new Fixture("绝不该被调用。");
        fixture.monitoringPort.throwsUnexpectedly(new IllegalStateException(UPSTREAM_SENTINEL));

        assertThatThrownBy(fixture::triage).isInstanceOf(AiProviderException.class);

        assertContractViolationLine(failureArguments(), "FOUND", "FOUND", PORT_CONTRACT_VIOLATION);
        assertEverySourceQueriedExactlyOnce(fixture);
        assertNoSensitiveMaterial();
    }

    @Test
    void aRetrievalInputRejectionMarksThatSourceAsCalledNotAsUnqueried() {
        Fixture fixture = new Fixture("绝不该被调用。");
        fixture.retrieval.fails(new KnowledgeApplicationException(
                KnowledgeApplicationErrorCode.INVALID_RETRIEVAL_QUERY, "问题不能为空"));

        assertThatThrownBy(() -> fixture.service.triage(new IncidentTriageCommand("AST-900001", "  ", 0, -1.0)))
                .isInstanceOf(AiRequestException.class);

        Object[] arguments = failureArguments();
        assertThat(arguments[3]).as("知识检索被调用过，输入在它内部被拒").isEqualTo(INVALID_INPUT);
        assertThat(arguments[4]).as("资产确实没有被调用").isEqualTo(NOT_QUERIED);
        assertThat(arguments[5]).as("监控确实没有被调用").isEqualTo(NOT_QUERIED);
        assertThat(arguments[6]).isEqualTo(ROUTE_UNKNOWN);
        assertThat(arguments[7]).isEqualTo(false);
        assertThat(arguments[8]).isEqualTo(0);
        assertThat(arguments[9]).isEqualTo(0);
        assertThat(arguments[10]).isEqualTo(INVALID_INPUT);
        assertThat(fixture.retrievalCalls()).isEqualTo(1);
        assertThat(fixture.assetCalls()).isZero();
        assertThat(fixture.monitoringCalls()).isZero();
        assertThat(fixture.modelCalls()).isZero();
        assertNoSensitiveMaterial();
    }

    @Test
    void anInvalidInputLogsOnlyMetadata() {
        Fixture fixture = new Fixture("绝不该被调用。");

        assertThatThrownBy(() -> fixture.service.triage(new IncidentTriageCommand("not-an-asset-id",
                QUESTION_SENTINEL, null, null)))
                .isInstanceOf(AiRequestException.class);

        Object[] arguments = failureArguments();
        assertThat(arguments[3]).as("什么也没有执行").isEqualTo(NOT_QUERIED);
        assertThat(arguments[4]).isEqualTo(NOT_QUERIED);
        assertThat(arguments[5]).isEqualTo(NOT_QUERIED);
        assertThat(arguments[6]).isEqualTo(ROUTE_UNKNOWN);
        assertThat(arguments[7]).isEqualTo(false);
        assertThat(arguments[8]).isEqualTo(0);
        assertThat(arguments[9]).isEqualTo(0);
        assertThat(arguments[10]).isEqualTo(IncidentTriageFailure.INVALID_INPUT.name());
        assertNoSensitiveMaterial();
    }

    /**
     * 文本层只扫不可能与数字撞车的哨兵（耗时可能恰好是 92 之类的数字）；
     * 数值类信息由「参数位逐个钉死」保证没有位置可放。
     */
    private void assertNoSensitiveMaterial() {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : this.appender.list) {
            text.append(event.getFormattedMessage()).append('\n');
            Object[] arguments = event.getArgumentArray();
            if (arguments != null) {
                for (Object argument : arguments) {
                    text.append(argument).append('\n');
                }
            }
            if (event.getThrowableProxy() != null) {
                text.append(event.getThrowableProxy().getClassName()).append(':')
                        .append(event.getThrowableProxy().getMessage()).append('\n');
            }
        }
        assertThat(text.toString())
                .doesNotContain("AST-900001")
                .doesNotContain(QUESTION_SENTINEL)
                .doesNotContain(CONTENT_SENTINEL)
                .doesNotContain(ANSWER_SENTINEL)
                .doesNotContain(UPSTREAM_SENTINEL)
                .doesNotContain(PROVIDER_SENTINEL)
                .doesNotContain("VPN 故障处理手册")
                .doesNotContain("IN_SERVICE")
                .doesNotContain("DEGRADED")
                .doesNotContain(IncidentTriagePromptBuilder.DATA_BEGIN)
                .doesNotContain("http://");
    }

    private Object[] completedArguments() {
        ILoggingEvent event = onlyEvent();
        assertThat(event.getMessage()).isEqualTo(COMPLETED_TEMPLATE);
        assertThat(event.getArgumentArray()).hasSize(11);
        assertThat(event.getArgumentArray()[0]).isEqualTo(OPERATION);
        assertThat(event.getArgumentArray()[1]).isEqualTo(OPERATION);
        assertThat((String) event.getArgumentArray()[2]).isNotBlank();
        assertThat(event.getArgumentArray()[10]).isInstanceOf(Number.class);
        return event.getArgumentArray();
    }

    /**
     * 断言一条「端口违约」失败日志：三个来源状态由调用方给出，其余字段固定。
     *
     * @param arguments        参数位
     * @param knowledgeState   知识来源的日志状态
     * @param assetState       资产来源的日志状态
     * @param monitoringState  监控来源的日志状态
     */
    private void assertContractViolationLine(Object[] arguments, String knowledgeState, String assetState,
            String monitoringState) {

        assertThat(arguments[3]).isEqualTo(knowledgeState);
        assertThat(arguments[4]).isEqualTo(assetState);
        assertThat(arguments[5]).isEqualTo(monitoringState);
        assertThat(arguments[6]).as("真实路由").isEqualTo("contract_violation");
        assertThat(arguments[7]).as("违约在调用模型前统一失败").isEqualTo(false);
        assertThat(arguments[8]).as("另外两个来源各贡献一条证据").isEqualTo(2);
        assertThat(arguments[9]).isEqualTo(0);
        assertThat(arguments[10]).isEqualTo(PORT_CONTRACT_VIOLATION);
        assertThat(arguments[11]).as("违约没有底层异常").isEqualTo("none");
    }

    /**
     * 断言三个来源各被调用一次、模型零调用 —— 「已调用」必须与日志状态一致。
     *
     * @param fixture 夹具
     */
    private static void assertEverySourceQueriedExactlyOnce(Fixture fixture) {
        assertThat(fixture.retrievalCalls()).isEqualTo(1);
        assertThat(fixture.assetCalls()).isEqualTo(1);
        assertThat(fixture.monitoringCalls()).isEqualTo(1);
        assertThat(fixture.modelCalls()).isZero();
    }

    private Object[] failureArguments() {
        ILoggingEvent event = onlyEvent();
        assertThat(event.getMessage()).isEqualTo(FAILED_TEMPLATE);
        assertThat(event.getArgumentArray()).hasSize(13);
        assertThat(event.getArgumentArray()[0]).isEqualTo(OPERATION);
        assertThat(event.getArgumentArray()[1]).isEqualTo(OPERATION);
        assertThat((String) event.getArgumentArray()[2]).isNotBlank();
        assertThat(event.getArgumentArray()[12]).isInstanceOf(Number.class);
        return event.getArgumentArray();
    }

    private ILoggingEvent onlyEvent() {
        assertThat(this.appender.list).as("每次研判恰好一条日志").hasSize(1);
        return this.appender.list.get(0);
    }

    private static Logger logger() {
        return (Logger) LoggerFactory.getLogger(IncidentTriageService.class);
    }

    private static KnowledgeRetrievalView emptyRetrieval() {
        return KnowledgeRetrievalView.vectorOrdered("dashscope", "text-embedding-v4", 1024, 5, 0.3, List.of());
    }

    /** 最小夹具：知识一条带哨兵正文的切片 + 命中的资产与监控。 */
    private static final class Fixture {

        private final FakeRetrieval retrieval = new FakeRetrieval();

        private final FakeAssetPort assetPort = new FakeAssetPort();

        private final FakeMonitoringPort monitoringPort = new FakeMonitoringPort();

        private final FakeChatModel model;

        private final IncidentTriageService service;

        Fixture(String answer) {
            this.model = new FakeChatModel(answer);
            this.service = new IncidentTriageService(this.retrieval, this.assetPort, this.monitoringPort,
                    ChatClient.create(this.model));
        }

        Fixture(RuntimeException failure) {
            this.model = new FakeChatModel(failure);
            this.service = new IncidentTriageService(this.retrieval, this.assetPort, this.monitoringPort,
                    ChatClient.create(this.model));
        }

        IncidentTriageResult triage() {
            return this.service.triage(new IncidentTriageCommand("AST-900001", QUESTION_SENTINEL, 5, 0.3));
        }

        int retrievalCalls() {
            return this.retrieval.calls();
        }

        int assetCalls() {
            return this.assetPort.calls();
        }

        int monitoringCalls() {
            return this.monitoringPort.calls();
        }

        int modelCalls() {
            return this.model.calls();
        }
    }

    private static final class FakeRetrieval implements RetrieveKnowledgeUseCase {

        private KnowledgeRetrievalView view = KnowledgeRetrievalView.vectorOrdered("dashscope",
                "text-embedding-v4", 1024, 5, 0.3, List.of(KnowledgeCitationView.vectorOnly("K1", 1,
                        UUID.fromString("11111111-2222-3333-4444-555555555555"), 2L, "VPN 故障处理手册", 0,
                        "0".repeat(64), CONTENT_SENTINEL, 0.9)));

        private KnowledgeApplicationException failure;

        private RuntimeException unexpected;

        private boolean nullView;

        private int calls;

        @Override
        public KnowledgeRetrievalView retrieve(RetrieveKnowledgeQuery query) {
            this.calls++;
            if (this.failure != null) {
                throw this.failure;
            }
            if (this.unexpected != null) {
                throw this.unexpected;
            }
            return this.nullView ? null : this.view;
        }

        void returns(KnowledgeRetrievalView view) {
            this.view = view;
        }

        void returnsNull() {
            this.nullView = true;
        }

        void fails(KnowledgeApplicationException failure) {
            this.failure = failure;
        }

        void throwsUnexpectedly(RuntimeException unexpected) {
            this.unexpected = unexpected;
        }

        int calls() {
            return this.calls;
        }
    }

    private static final class FakeAssetPort implements AssetQueryPort {

        private AssetQueryResult result = AssetQueryResult.found(
                new AssetView("AST-900001", "SERVER", "IN_SERVICE", SourceOrigin.DEMO));

        private boolean nullResult;

        private RuntimeException unexpected;

        private int calls;

        @Override
        public AssetQueryResult findAsset(String assetId) {
            this.calls++;
            if (this.unexpected != null) {
                throw this.unexpected;
            }
            return this.nullResult ? null : this.result;
        }

        void returns(AssetQueryResult result) {
            this.result = result;
        }

        void returnsNull() {
            this.nullResult = true;
        }

        void throwsUnexpectedly(RuntimeException unexpected) {
            this.unexpected = unexpected;
        }

        int calls() {
            return this.calls;
        }
    }

    private static final class FakeMonitoringPort implements MonitoringSnapshotQueryPort {

        private MonitoringSnapshotQueryResult result = MonitoringSnapshotQueryResult.found(
                new MonitoringSnapshotView("AST-900001", Instant.parse("2026-01-01T00:00:00Z"), HealthState.DEGRADED,
                        92, 68, 1, SourceOrigin.DEMO));

        private boolean nullResult;

        private RuntimeException unexpected;

        private int calls;

        @Override
        public MonitoringSnapshotQueryResult findLatestSnapshot(String assetId) {
            this.calls++;
            if (this.unexpected != null) {
                throw this.unexpected;
            }
            return this.nullResult ? null : this.result;
        }

        void returns(MonitoringSnapshotQueryResult result) {
            this.result = result;
        }

        void returnsNull() {
            this.nullResult = true;
        }

        void throwsUnexpectedly(RuntimeException unexpected) {
            this.unexpected = unexpected;
        }

        int calls() {
            return this.calls;
        }
    }

    private static final class FakeChatModel implements ChatModel {

        private final List<Prompt> prompts = new ArrayList<>();

        private final String answer;

        private final RuntimeException failure;

        FakeChatModel(String answer) {
            this.answer = answer;
            this.failure = null;
        }

        FakeChatModel(RuntimeException failure) {
            this.answer = null;
            this.failure = failure;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            this.prompts.add(prompt);
            if (this.failure != null) {
                throw this.failure;
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(this.answer))));
        }

        int calls() {
            return this.prompts.size();
        }
    }
}
