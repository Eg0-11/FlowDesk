package com.flowdesk.agent.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowdesk.application.ai.AiProviderException;
import com.flowdesk.application.ai.AiRequestException;
import com.flowdesk.application.ai.AssetDiagnosisCommand;
import com.flowdesk.application.ai.AssetDiagnosisResult;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 资产诊断编排的固定执行规则（FD-0017-A）。
 *
 * <p>用<b>手写替身</b>（不是 Mockito）替换三个协作者：两个查询端口与一个真实
 * {@link ChatClient}（由本地假 {@link ChatModel} 构造）。替身记录调用次数、调用顺序与收到的
 * {@link Prompt}，因此「各调用一次」「顺序固定」「模型不能选工具」这些规则都是被数出来的。</p>
 */
class AssetDiagnosisServiceTests {

    private static final String ASSET_ID = "AST-900001";

    private static final String GOOD_ANSWER = "资产处于在用状态 [A1]，监控显示负载偏高 [M1]。";

    @Test
    void invalidInputIsRejectedBeforeAnyPortOrModelCall() {
        Fixture fixture = new Fixture(GOOD_ANSWER);

        for (AssetDiagnosisCommand command : new AssetDiagnosisCommand[] {
                null,
                new AssetDiagnosisCommand(null),
                new AssetDiagnosisCommand(""),
                new AssetDiagnosisCommand("   "),
                new AssetDiagnosisCommand("AST-1"),
                new AssetDiagnosisCommand("ast-900001"),
                new AssetDiagnosisCommand("AST-900001 "),
                new AssetDiagnosisCommand(" AST-900001"),
                new AssetDiagnosisCommand("AST-9000011"),
                new AssetDiagnosisCommand("AST_900001") }) {

            assertThatThrownBy(() -> fixture.service.diagnose(command))
                    .as("command=%s", command == null ? "null" : command.assetId())
                    .isInstanceOf(AiRequestException.class);
        }

        assertThat(fixture.assetPort.calls()).as("非法输入不得调用任何端口").isEmpty();
        assertThat(fixture.monitoringPort.calls()).isEmpty();
        assertThat(fixture.model.calls()).as("非法输入不得调用模型").isZero();
    }

    @Test
    void bothSidesFoundCallsEachPortOnceAndTheModelOnce() {
        Fixture fixture = new Fixture(GOOD_ANSWER);

        AssetDiagnosisResult result = fixture.service.diagnose(new AssetDiagnosisCommand(ASSET_ID));

        assertThat(fixture.assetPort.calls()).containsExactly(ASSET_ID);
        assertThat(fixture.monitoringPort.calls()).containsExactly(ASSET_ID);
        assertThat(fixture.order).as("查询顺序固定为资产 → 监控").containsExactly("asset", "monitoring");
        assertThat(fixture.model.calls()).isEqualTo(1);
        assertThat(result.grounded()).isTrue();
        assertThat(result.usedEvidenceIds()).containsExactly("A1", "M1");
        assertThat(result.asset().outcome()).isEqualTo(QueryOutcome.FOUND);
        assertThat(result.monitoring().outcome()).isEqualTo(QueryOutcome.FOUND);
        assertThat(fixture.prompt()).contains("[A1]").contains("[M1]");
    }

    @Test
    void theModelRequestContainsExactlyASystemAndAUserMessage() {
        Fixture fixture = new Fixture(GOOD_ANSWER);

        fixture.service.diagnose(new AssetDiagnosisCommand(ASSET_ID));

        Prompt prompt = fixture.model.prompts().get(0);
        assertThat(prompt.getInstructions()).hasSize(2);
        assertThat(prompt.getInstructions().get(0).getMessageType()).isEqualTo(MessageType.SYSTEM);
        assertThat(prompt.getInstructions().get(1).getMessageType()).isEqualTo(MessageType.USER);
    }

    @Test
    void anAssetHitWithAMonitoringMissCallsTheModelWithOnlyTheAssetEvidence() {
        for (MonitoringSnapshotQueryResult monitoring : List.of(
                MonitoringSnapshotQueryResult.notFound(ASSET_ID, SourceOrigin.DEMO),
                MonitoringSnapshotQueryResult.failed(QueryFailure.UNAVAILABLE),
                MonitoringSnapshotQueryResult.failed(QueryFailure.DISABLED),
                MonitoringSnapshotQueryResult.failed(QueryFailure.TIMEOUT))) {

            Fixture fixture = new Fixture("资产在用 [A1]。");
            fixture.monitoringPort.returns(monitoring);

            AssetDiagnosisResult result = fixture.service.diagnose(new AssetDiagnosisCommand(ASSET_ID));

            assertThat(fixture.model.calls()).as("monitoring=%s", monitoring.outcome()).isEqualTo(1);
            assertThat(result.grounded()).isTrue();
            assertThat(result.usedEvidenceIds()).containsExactly("A1");
            assertThat(result.monitoring().outcome()).as("另一侧的真实状态必须保留")
                    .isEqualTo(monitoring.outcome());
            assertThat(result.monitoring().failure()).isEqualTo(monitoring.failure());
        }
    }

    @Test
    void aMonitoringHitWithAnAssetMissCallsTheModelWithOnlyTheSnapshotEvidence() {
        for (AssetQueryResult asset : List.of(
                AssetQueryResult.notFound(ASSET_ID, SourceOrigin.DEMO),
                AssetQueryResult.failed(QueryFailure.UNAVAILABLE),
                AssetQueryResult.failed(QueryFailure.DISABLED))) {

            Fixture fixture = new Fixture("监控负载偏高 [M1]。");
            fixture.assetPort.returns(asset);

            AssetDiagnosisResult result = fixture.service.diagnose(new AssetDiagnosisCommand(ASSET_ID));

            assertThat(fixture.model.calls()).as("asset=%s", asset.outcome()).isEqualTo(1);
            assertThat(result.usedEvidenceIds()).containsExactly("M1");
            assertThat(result.asset().outcome()).isEqualTo(asset.outcome());
            assertThat(result.asset().failure()).isEqualTo(asset.failure());
        }
    }

    @Test
    void citingTheSideThatWasNotFoundFailsTheDiagnosis() {
        Fixture fixture = new Fixture("资产在用 [A1]，监控也正常 [M1]。");
        fixture.monitoringPort.returns(MonitoringSnapshotQueryResult.notFound(ASSET_ID, SourceOrigin.DEMO));

        assertThatThrownBy(() -> fixture.service.diagnose(new AssetDiagnosisCommand(ASSET_ID)))
                .isInstanceOf(AiProviderException.class)
                .satisfies(thrown -> assertThat(((AiProviderException) thrown).requestId()).isNotBlank());
    }

    @Test
    void bothSidesNotFoundSkipsTheModelAndAnswersWithTheFixedText() {
        Fixture fixture = new Fixture(GOOD_ANSWER);
        fixture.assetPort.returns(AssetQueryResult.notFound(ASSET_ID, SourceOrigin.DEMO));
        fixture.monitoringPort.returns(MonitoringSnapshotQueryResult.notFound(ASSET_ID, SourceOrigin.DEMO));

        AssetDiagnosisResult result = fixture.service.diagnose(new AssetDiagnosisCommand(ASSET_ID));

        assertThat(fixture.assetPort.calls()).hasSize(1);
        assertThat(fixture.monitoringPort.calls()).hasSize(1);
        assertThat(fixture.model.calls()).as("两边都没命中：不调用模型").isZero();
        assertThat(result.grounded()).isFalse();
        assertThat(result.usedEvidenceIds()).isEmpty();
        assertThat(result.answer()).isEqualTo(AssetDiagnosisService.NO_RECORD_ANSWER);
    }

    @Test
    void noEvidenceWithAtLeastOneFailureUsesTheFixedDegradedText() {
        record Case(AssetQueryResult asset, MonitoringSnapshotQueryResult monitoring) {
        }
        List<Case> cases = List.of(
                new Case(AssetQueryResult.failed(QueryFailure.UNAVAILABLE),
                        MonitoringSnapshotQueryResult.notFound(ASSET_ID, SourceOrigin.DEMO)),
                new Case(AssetQueryResult.notFound(ASSET_ID, SourceOrigin.DEMO),
                        MonitoringSnapshotQueryResult.failed(QueryFailure.DISABLED)),
                new Case(AssetQueryResult.failed(QueryFailure.DISABLED),
                        MonitoringSnapshotQueryResult.failed(QueryFailure.TIMEOUT)));

        for (Case testCase : cases) {
            Fixture fixture = new Fixture(GOOD_ANSWER);
            fixture.assetPort.returns(testCase.asset());
            fixture.monitoringPort.returns(testCase.monitoring());

            AssetDiagnosisResult result = fixture.service.diagnose(new AssetDiagnosisCommand(ASSET_ID));

            assertThat(fixture.model.calls()).as("无命中证据：不调用模型").isZero();
            assertThat(result.grounded()).isFalse();
            assertThat(result.answer()).isEqualTo(AssetDiagnosisService.INSUFFICIENT_EVIDENCE_ANSWER);
            assertThat(result.asset().outcome()).isEqualTo(testCase.asset().outcome());
            assertThat(result.monitoring().outcome()).isEqualTo(testCase.monitoring().outcome());
        }
    }

    @Test
    void theSecondQueryRunsEvenWhenTheFirstOneFailed() {
        Fixture fixture = new Fixture("监控负载偏高 [M1]。");
        fixture.assetPort.returns(AssetQueryResult.failed(QueryFailure.UNAVAILABLE));

        AssetDiagnosisResult result = fixture.service.diagnose(new AssetDiagnosisCommand(ASSET_ID));

        assertThat(fixture.order).containsExactly("asset", "monitoring");
        assertThat(fixture.monitoringPort.calls()).as("第一次查询失败后第二次仍然执行").hasSize(1);
        assertThat(result.monitoring().outcome()).isEqualTo(QueryOutcome.FOUND);
    }

    @Test
    void aPortContractViolationStillPerformsBothQueriesAndThenFails() {
        Fixture fixture = new Fixture(GOOD_ANSWER);
        fixture.assetPort.throwsOnCall(new IllegalStateException("port contract violated"));

        assertThatThrownBy(() -> fixture.service.diagnose(new AssetDiagnosisCommand(ASSET_ID)))
                .isInstanceOf(AiProviderException.class);

        assertThat(fixture.order).as("端口违约时第二次查询仍然执行").containsExactly("asset", "monitoring");
        assertThat(fixture.model.calls()).as("没有证据不得调用模型").isZero();
    }

    @Test
    void aFailingModelCallBecomesAProviderFailureCarryingTheRequestId() {
        Fixture fixture = new Fixture();

        assertThatThrownBy(() -> fixture.service.diagnose(new AssetDiagnosisCommand(ASSET_ID)))
                .isInstanceOf(AiProviderException.class)
                .satisfies(thrown -> {
                    AiProviderException failure = (AiProviderException) thrown;
                    assertThat(failure.requestId()).isNotBlank();
                    assertThat(failure.getMessage()).as("对外文案固定，不含上游原文")
                            .doesNotContain("model failure");
                });
    }

    @Test
    void anEmptyModelAnswerBecomesAProviderFailure() {
        for (String answer : new String[] { "", "   ", "\n" }) {
            Fixture fixture = new Fixture(answer);

            assertThatThrownBy(() -> fixture.service.diagnose(new AssetDiagnosisCommand(ASSET_ID)))
                    .as("answer=[%s]", answer)
                    .isInstanceOf(AiProviderException.class)
                    .satisfies(thrown -> assertThat(((AiProviderException) thrown).requestId()).isNotBlank());
        }
    }

    @Test
    void aCitationFailureBecomesAProviderFailureWithoutRepairingOrRetrying() {
        record Case(String answer, AssetDiagnosisFailure expected) {
        }
        List<Case> cases = List.of(
                new Case("资产在用，没有引用。", AssetDiagnosisFailure.ANSWER_WITHOUT_CITATION),
                new Case("资产在用 [a1]，监控偏高 [M1]。", AssetDiagnosisFailure.INVALID_CITATION_FORMAT),
                new Case("资产在用 [A1x]，监控偏高 [M1]。", AssetDiagnosisFailure.INVALID_CITATION_FORMAT),
                new Case("只在资产上作了结论 [A1]。", AssetDiagnosisFailure.EVIDENCE_NOT_CITED));

        for (Case testCase : cases) {
            Fixture fixture = new Fixture(testCase.answer());

            assertThatThrownBy(() -> fixture.service.diagnose(new AssetDiagnosisCommand(ASSET_ID)))
                    .as("answer=[%s]", testCase.answer())
                    .isInstanceOf(AiProviderException.class);
            assertThat(fixture.model.calls()).as("不重新调用模型").isEqualTo(1);
        }
    }

    @Test
    void theAnswerIsStrippedButNotRewritten() {
        Fixture fixture = new Fixture("  资产在用 [A1]，监控偏高 [M1]。  ");

        AssetDiagnosisResult result = fixture.service.diagnose(new AssetDiagnosisCommand(ASSET_ID));

        assertThat(result.answer()).isEqualTo("资产在用 [A1]，监控偏高 [M1]。");
    }

    /** 把两个端口替身、假模型与编排服务装到一起。 */
    private static final class Fixture {

        private final List<String> order = new ArrayList<>();

        private final FakeAssetPort assetPort = new FakeAssetPort(order);

        private final FakeMonitoringPort monitoringPort = new FakeMonitoringPort(order);

        private final FakeChatModel model;

        private final AssetDiagnosisService service;

        Fixture(String answer) {
            this.model = new FakeChatModel(answer);
            this.service = new AssetDiagnosisService(this.assetPort, this.monitoringPort,
                    ChatClient.create(this.model));
        }

        Fixture() {
            this.model = new FakeChatModel(new IllegalStateException("model failure"));
            this.service = new AssetDiagnosisService(this.assetPort, this.monitoringPort,
                    ChatClient.create(this.model));
        }

        String prompt() {
            return this.model.prompts().get(0).getInstructions().get(1).getText();
        }
    }

    private static final class FakeAssetPort implements AssetQueryPort {

        private final List<String> calls = new ArrayList<>();

        private final List<String> order;

        private AssetQueryResult result = AssetQueryResult.found(
                new AssetView(ASSET_ID, "SERVER", "IN_SERVICE", SourceOrigin.DEMO));

        private RuntimeException failure;

        FakeAssetPort(List<String> order) {
            this.order = order;
        }

        @Override
        public AssetQueryResult findAsset(String assetId) {
            this.order.add("asset");
            this.calls.add(assetId);
            if (this.failure != null) {
                throw this.failure;
            }
            return this.result;
        }

        void returns(AssetQueryResult result) {
            this.result = result;
        }

        void throwsOnCall(RuntimeException failure) {
            this.failure = failure;
        }

        List<String> calls() {
            return List.copyOf(this.calls);
        }
    }

    private static final class FakeMonitoringPort implements MonitoringSnapshotQueryPort {

        private final List<String> calls = new ArrayList<>();

        private final List<String> order;

        private MonitoringSnapshotQueryResult result = MonitoringSnapshotQueryResult.found(
                new MonitoringSnapshotView(ASSET_ID, Instant.parse("2026-01-01T00:00:00Z"), HealthState.DEGRADED,
                        92, 68, 1, SourceOrigin.DEMO));

        FakeMonitoringPort(List<String> order) {
            this.order = order;
        }

        @Override
        public MonitoringSnapshotQueryResult findLatestSnapshot(String assetId) {
            this.order.add("monitoring");
            this.calls.add(assetId);
            return this.result;
        }

        void returns(MonitoringSnapshotQueryResult result) {
            this.result = result;
        }

        List<String> calls() {
            return List.copyOf(this.calls);
        }
    }

    /** 假 ChatModel：返回预置答案或抛出预置异常，并记录每一次收到的 {@link Prompt}。 */
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

        List<Prompt> prompts() {
            return List.copyOf(this.prompts);
        }
    }
}
