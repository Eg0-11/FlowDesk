package com.flowdesk.agent.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flowdesk.application.ai.AssetDiagnosisCommand;
import com.flowdesk.application.integration.AssetQueryResult;
import com.flowdesk.application.integration.AssetView;
import com.flowdesk.application.integration.HealthState;
import com.flowdesk.application.integration.MonitoringSnapshotQueryResult;
import com.flowdesk.application.integration.MonitoringSnapshotView;
import com.flowdesk.application.integration.SourceOrigin;
import com.flowdesk.application.integration.port.out.AssetQueryPort;
import com.flowdesk.application.integration.port.out.MonitoringSnapshotQueryPort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * 资产诊断日志的结构与脱敏（FD-0017-A）。
 *
 * <p>每次诊断只允许记录固定元数据，因此这里用「模板固定 + 参数位逐个钉死」的方式断言：
 * 模板必须是两条固定文案之一，参数位只能是操作名、请求标识、结果三态、布尔、计数、稳定失败类别、
 * 异常类名与唯一的数值参数（耗时）。assetId、资产详情、监控数值、提示词、模型回答与异常消息
 * 因此<b>没有任何位置可放</b>。</p>
 *
 * <p>为了不让断言落空，测试用的资产与回答里带了哨兵，并先断言它们确实进入了结果 / 异常，
 * 再断言它们没有进入日志。</p>
 */
class AssetDiagnosisLoggingTests {

    private static final String COMPLETED_TEMPLATE =
            "{} completed operation={} requestId={} assetOutcome={} monitoringOutcome={} modelCalled={} "
                    + "evidenceCount={} usedEvidenceCount={} success=true durationMs={}";

    private static final String FAILED_TEMPLATE =
            "{} failed operation={} requestId={} assetOutcome={} monitoringOutcome={} modelCalled={} "
                    + "evidenceCount={} usedEvidenceCount={} failure={} exception={} success=false durationMs={}";

    private static final String OPERATION = AssetDiagnosisService.OPERATION;

    /** 资产字段哨兵：它必须进入提示词与结果，但不得进入日志。 */
    private static final String ASSET_TYPE_SENTINEL = "SENTINEL_SERVER_TYPE";

    /** 模型回答哨兵：它必须被返回，但不得进入日志。 */
    private static final String ANSWER_SENTINEL = "SENTINELANSWER";

    /** 上游异常消息哨兵：它不得进入日志。 */
    private static final String UPSTREAM_SENTINEL = "sentinel-upstream-detail";

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
    void aSuccessfulDiagnosisLogsExactlyOneStructuredLine() {
        Fixture fixture = new Fixture(ANSWER_SENTINEL + " 资产在用 [A1]，监控偏高 [M1]。");

        var result = fixture.service.diagnose(new AssetDiagnosisCommand("AST-900001"));

        assertThat(result.answer()).contains(ANSWER_SENTINEL);
        assertThat(this.appender.list).hasSize(1);

        ILoggingEvent event = this.appender.list.get(0);
        Object[] arguments = event.getArgumentArray();
        assertThat(event.getMessage()).isEqualTo(COMPLETED_TEMPLATE);
        assertThat(arguments).hasSize(9);
        assertThat(arguments[0]).isEqualTo(OPERATION);
        assertThat(arguments[1]).isEqualTo(OPERATION);
        assertThat(arguments[2]).isEqualTo(result.requestId());
        assertThat(arguments[3]).isEqualTo("FOUND");
        assertThat(arguments[4]).isEqualTo("FOUND");
        assertThat(arguments[5]).isEqualTo(true);
        assertThat(arguments[6]).isEqualTo(2);
        assertThat(arguments[7]).isEqualTo(2);
        assertThat(arguments[8]).isInstanceOf(Number.class);
        assertNoSensitiveMaterial();
    }

    @Test
    void aNoEvidenceDiagnosisLogsModelCalledFalse() {
        Fixture fixture = new Fixture("绝不会被调用。");
        fixture.assetPort.returns(AssetQueryResult.notFound("AST-900001", SourceOrigin.DEMO));
        fixture.monitoringPort.returns(MonitoringSnapshotQueryResult.notFound("AST-900001", SourceOrigin.DEMO));

        fixture.service.diagnose(new AssetDiagnosisCommand("AST-900001"));

        assertThat(this.appender.list).hasSize(1);
        Object[] arguments = this.appender.list.get(0).getArgumentArray();
        assertThat(this.appender.list.get(0).getMessage()).isEqualTo(COMPLETED_TEMPLATE);
        assertThat(arguments[5]).isEqualTo(false);
        assertThat(arguments[6]).isEqualTo(0);
        assertThat(arguments[7]).isEqualTo(0);
        assertNoSensitiveMaterial();
    }

    @Test
    void aCitationFailureLogsOnlyTheStableFailureCategory() {
        Fixture fixture = new Fixture("只谈资产 " + ANSWER_SENTINEL + " [A1]。");

        assertThatThrownBy(() -> fixture.service.diagnose(new AssetDiagnosisCommand("AST-900001")))
                .isInstanceOf(com.flowdesk.application.ai.AiProviderException.class);

        assertThat(this.appender.list).hasSize(1);
        ILoggingEvent event = this.appender.list.get(0);
        Object[] arguments = event.getArgumentArray();
        assertThat(event.getMessage()).isEqualTo(FAILED_TEMPLATE);
        assertThat(arguments).hasSize(11);
        assertThat(arguments[8]).isEqualTo(AssetDiagnosisFailure.EVIDENCE_NOT_CITED.name());
        assertThat(arguments[9]).isEqualTo(AssetDiagnosisException.class.getName());
        assertNoSensitiveMaterial();
    }

    @Test
    void aModelFailureLogsTheStableCategoryAndTheExceptionClassOnly() {
        Fixture fixture = new Fixture(new IllegalStateException(UPSTREAM_SENTINEL));

        assertThatThrownBy(() -> fixture.service.diagnose(new AssetDiagnosisCommand("AST-900001")))
                .isInstanceOf(com.flowdesk.application.ai.AiProviderException.class);

        Object[] arguments = this.appender.list.get(0).getArgumentArray();
        assertThat(this.appender.list.get(0).getMessage()).isEqualTo(FAILED_TEMPLATE);
        assertThat(arguments[8]).isEqualTo(AssetDiagnosisFailure.MODEL_CALL_FAILED.name());
        assertThat(arguments[9]).isEqualTo(IllegalStateException.class.getName());
        assertNoSensitiveMaterial();
    }

    @Test
    void anInvalidInputLogsNothingButMetadata() {
        Fixture fixture = new Fixture("不会被调用。");

        assertThatThrownBy(() -> fixture.service.diagnose(new AssetDiagnosisCommand("not-an-asset-id")))
                .isInstanceOf(com.flowdesk.application.ai.AiRequestException.class);

        assertThat(this.appender.list).hasSize(1);
        Object[] arguments = this.appender.list.get(0).getArgumentArray();
        assertThat(arguments[2]).isEqualTo("none");
        assertThat(arguments[3]).isEqualTo(AssetDiagnosisService.NOT_QUERIED);
        assertThat(arguments[4]).isEqualTo(AssetDiagnosisService.NOT_QUERIED);
        assertThat(arguments[8]).isEqualTo(AssetDiagnosisFailure.INVALID_INPUT.name());
        assertNoSensitiveMaterial();
    }

    /**
     * 文本层只扫不可能与数字撞车的哨兵（耗时可能恰好是 92 之类的数字）；
     * 数值类信息由上面的「参数位逐个钉死」保证没有位置可放。
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
                .as("日志里不得出现 assetId、资产详情、监控文本字段、模型回答、提示词哨兵或异常消息")
                .doesNotContain("AST-900001")
                .doesNotContain(ASSET_TYPE_SENTINEL)
                .doesNotContain("IN_SERVICE")
                .doesNotContain("DEGRADED")
                .doesNotContain(ANSWER_SENTINEL)
                .doesNotContain(UPSTREAM_SENTINEL)
                .doesNotContain(AssetDiagnosisPromptBuilder.DATA_BEGIN)
                .doesNotContain("http://")
                .doesNotContain("Authorization");
    }

    private static Logger logger() {
        return (Logger) LoggerFactory.getLogger(AssetDiagnosisService.class);
    }

    /** 最小夹具：资产字段带哨兵、监控快照为固定虚构数据。 */
    private static final class Fixture {

        private final FakeAssetPort assetPort = new FakeAssetPort();

        private final FakeMonitoringPort monitoringPort = new FakeMonitoringPort();

        private final AssetDiagnosisService service;

        Fixture(String answer) {
            this.service = new AssetDiagnosisService(this.assetPort, this.monitoringPort,
                    ChatClient.create(new FakeChatModel(answer)));
        }

        Fixture(RuntimeException failure) {
            this.service = new AssetDiagnosisService(this.assetPort, this.monitoringPort,
                    ChatClient.create(new FakeChatModel(failure)));
        }
    }

    private static final class FakeAssetPort implements AssetQueryPort {

        private AssetQueryResult result = AssetQueryResult.found(
                new AssetView("AST-900001", ASSET_TYPE_SENTINEL, "IN_SERVICE", SourceOrigin.DEMO));

        @Override
        public AssetQueryResult findAsset(String assetId) {
            return this.result;
        }

        void returns(AssetQueryResult result) {
            this.result = result;
        }
    }

    private static final class FakeMonitoringPort implements MonitoringSnapshotQueryPort {

        private MonitoringSnapshotQueryResult result = MonitoringSnapshotQueryResult.found(
                new MonitoringSnapshotView("AST-900001", Instant.parse("2026-01-01T00:00:00Z"), HealthState.DEGRADED,
                        92, 68, 1, SourceOrigin.DEMO));

        @Override
        public MonitoringSnapshotQueryResult findLatestSnapshot(String assetId) {
            return this.result;
        }

        void returns(MonitoringSnapshotQueryResult result) {
            this.result = result;
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
    }
}
