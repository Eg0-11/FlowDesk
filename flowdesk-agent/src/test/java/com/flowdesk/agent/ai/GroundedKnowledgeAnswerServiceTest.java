package com.flowdesk.agent.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.flowdesk.application.ai.AiProviderException;
import com.flowdesk.application.ai.KnowledgeAnswerCommand;
import com.flowdesk.application.ai.KnowledgeAnswerResult;
import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.application.knowledge.port.in.RetrieveKnowledgeUseCase;
import com.flowdesk.application.knowledge.query.KnowledgeQueryNormalizer;
import com.flowdesk.application.knowledge.query.RetrieveKnowledgeQuery;
import com.flowdesk.application.knowledge.view.KnowledgeCitationView;
import com.flowdesk.application.knowledge.view.KnowledgeRetrievalView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 知识库问答编排的单元测试（RAG 5/6）。
 *
 * <p>用<b>手写替身</b>（不是 Mockito）替换两个协作者：检索用例与一个真实的
 * {@link ChatClient}（由本地假 {@link ChatModel} 构造）。这样「检索 → 无证据降级 →
 * 构造提示词 → 调用模型一次 → 校验引用 → 返回」这条顺序是被真实执行的，
 * 而且能断言「模型究竟收到了什么」以及「什么情况下<b>根本不该</b>调用模型」。</p>
 */
class GroundedKnowledgeAnswerServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final UUID DOCUMENT_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static final String DIGEST = "0123456789abcdef".repeat(4);

    private static final String QUESTION = "VPN 无法连接应该如何处理？";

    // ---------- 正常路径 ----------

    @Test
    void returnsTheAnswerTogetherWithTheUsedCitationsAndTheFullEvidence() {
        FakeRetrieval retrieval = FakeRetrieval.withCitations(2);
        FakeChatModel model = new FakeChatModel("先检查隧道状态 [K1]，再确认账号状态 [K2]。");
        KnowledgeAnswerUseCaseFixture fixture = new KnowledgeAnswerUseCaseFixture(retrieval, model);

        KnowledgeAnswerResult result = fixture.answer(new KnowledgeAnswerCommand(QUESTION, null, null));

        assertThat(result.grounded()).isTrue();
        assertThat(result.answer()).isEqualTo("先检查隧道状态 [K1]，再确认账号状态 [K2]。");
        assertThat(result.usedCitationIds()).containsExactly("K1", "K2");
        assertThat(result.requestId()).isNotBlank();
        assertThat(result.retrievedCitationCount()).isEqualTo(2);
        assertThat(result.usedCitationCount()).isEqualTo(2);
        assertThat(result.retrieval().citations())
                .as("返回的是完整证据，而不只是被引用的那几条")
                .hasSize(2);

        assertThat(model.calls()).as("一轮问答只调用一次模型，不做内部重试").isEqualTo(1);
        assertThat(retrieval.queries())
                .as("原始问题原样交给检索用例：合法性校验只有那一处实现，规范化由共用的 KnowledgeQueryNormalizer 负责")
                .containsExactly(QUESTION);
    }

    @Test
    void sendsExactlyTwoMessagesWithoutToolsAndWithoutMemory() throws Exception {
        FakeChatModel model = new FakeChatModel("结论 [K1]。");
        KnowledgeAnswerUseCaseFixture fixture = new KnowledgeAnswerUseCaseFixture(
                FakeRetrieval.withCitations(1), model);

        fixture.answer(new KnowledgeAnswerCommand(QUESTION, null, null));

        Prompt prompt = model.prompts().get(0);
        List<Message> messages = prompt.getInstructions();
        assertThat(messages).hasSize(2);
        // 只有 system + user：既没有工具调用/工具结果消息，也没有任何历史轮次（无会话记忆）
        assertThat(messages).extracting(Message::getMessageType)
                .containsExactly(MessageType.SYSTEM, MessageType.USER);
        assertThat(messages.get(1).getText())
                .contains(KnowledgeAnswerPromptBuilder.DATA_BEGIN)
                .contains(KnowledgeAnswerPromptBuilder.DATA_END);
        assertThat(promptQuestion(messages.get(1).getText())).isEqualTo(QUESTION);
    }

    // ---------- 规范化问题：检索与生成必须是同一个字符串（FD-0012-R1）----------

    @Test
    void asksTheModelTheSameNormalizedQuestionThatRetrievalReceived() throws Exception {
        for (String raw : List.of("   VPN   ", "e\u0301", "\tVPN\n")) {
            FakeChatModel model = new FakeChatModel("结论 [K1]。");
            KnowledgeAnswerUseCaseFixture fixture = new KnowledgeAnswerUseCaseFixture(
                    FakeRetrieval.withCitations(1), model);

            fixture.answer(new KnowledgeAnswerCommand(raw, null, null));

            String promptQuestion = promptQuestion(model.prompts().get(0).getInstructions().get(1).getText());
            assertThat(promptQuestion)
                    .as("raw=[%s]", raw)
                    .isEqualTo(KnowledgeQueryNormalizer.normalize(raw));
        }

        assertThat(KnowledgeQueryNormalizer.normalize("e\u0301"))
                .as("分解形式必须折叠成单个 NFC 字符")
                .isEqualTo("\u00e9");
    }

    @Test
    void doesNotLetUnboundedLeadingAndTrailingWhitespaceIntoThePrompt() throws Exception {
        String raw = " ".repeat(10_000) + "VPN" + " ".repeat(10_000);
        FakeChatModel model = new FakeChatModel("结论 [K1]。");
        KnowledgeAnswerUseCaseFixture fixture = new KnowledgeAnswerUseCaseFixture(
                FakeRetrieval.withCitations(1), model);

        fixture.answer(new KnowledgeAnswerCommand(raw, null, null));

        String userPrompt = model.prompts().get(0).getInstructions().get(1).getText();
        assertThat(promptQuestion(userPrompt)).isEqualTo("VPN");
        assertThat(userPrompt)
                .as("两万个空白不得进入提示词")
                .doesNotContain(" ".repeat(2))
                .hasSizeLessThan(1_000);
    }

    @Test
    void keepsTheRawQuestionOutOfTheRetrievalCallOnlyUpToThePortContract() {
        // 原始字符串原样交给检索用例（它负责规范化与合法性），本层不重复实现这套规则
        FakeRetrieval retrieval = FakeRetrieval.withCitations(1);
        KnowledgeAnswerUseCaseFixture fixture = new KnowledgeAnswerUseCaseFixture(
                retrieval, new FakeChatModel("结论 [K1]。"));

        fixture.answer(new KnowledgeAnswerCommand("  VPN  ", null, null));

        assertThat(retrieval.queries()).containsExactly("  VPN  ");
    }

    @Test
    void trimsTheAnswerButNeverRewritesIt() {
        KnowledgeAnswerUseCaseFixture fixture = new KnowledgeAnswerUseCaseFixture(
                FakeRetrieval.withCitations(1), new FakeChatModel("  结论 [K1]。  "));

        KnowledgeAnswerResult result = fixture.answer(new KnowledgeAnswerCommand(QUESTION, null, null));

        assertThat(result.answer()).isEqualTo("结论 [K1]。");
    }

    @Test
    void onlyCountsTheCitationsTheAnswerActuallyUses() {
        KnowledgeAnswerUseCaseFixture fixture = new KnowledgeAnswerUseCaseFixture(
                FakeRetrieval.withCitations(3), new FakeChatModel("只依据第二条 [K2]。"));

        KnowledgeAnswerResult result = fixture.answer(new KnowledgeAnswerCommand(QUESTION, null, null));

        assertThat(result.usedCitationIds()).containsExactly("K2");
        assertThat(result.retrievedCitationCount()).isEqualTo(3);
        assertThat(result.usedCitationCount()).isEqualTo(1);
    }

    // ---------- 无证据：不调用模型 ----------

    @Test
    void doesNotCallTheModelAtAllWhenRetrievalFindsNothing() {
        FakeChatModel model = new FakeChatModel("这段回答绝不能出现 [K1]。");
        KnowledgeAnswerUseCaseFixture fixture = new KnowledgeAnswerUseCaseFixture(FakeRetrieval.empty(), model);

        KnowledgeAnswerResult result = fixture.answer(new KnowledgeAnswerCommand(QUESTION, null, null));

        assertThat(model.calls()).as("没有检索结果时绝不能调用模型").isZero();
        assertThat(result.grounded()).isFalse();
        assertThat(result.usedCitationIds()).isEmpty();
        assertThat(result.retrieval().citations()).isEmpty();
        assertThat(result.answer())
                .as("返回固定降级文案，而不是模型的自由发挥")
                .isEqualTo("当前知识库中没有足够证据回答该问题。");
    }

    // ---------- 检索失败：原样传播且不调用模型 ----------

    @Test
    void propagatesRetrievalFailuresUnchangedAndNeverCallsTheModel() {
        for (KnowledgeApplicationErrorCode code : List.of(
                KnowledgeApplicationErrorCode.KNOWLEDGE_EMBEDDING_DISABLED,
                KnowledgeApplicationErrorCode.EMBEDDING_PROVIDER_ERROR,
                KnowledgeApplicationErrorCode.INVALID_RETRIEVAL_QUERY,
                KnowledgeApplicationErrorCode.KNOWLEDGE_RETRIEVAL_FAILURE)) {

            KnowledgeApplicationException failure = new KnowledgeApplicationException(code, "检索阶段失败");
            FakeChatModel model = new FakeChatModel("绝不该出现 [K1]。");
            KnowledgeAnswerUseCaseFixture fixture = new KnowledgeAnswerUseCaseFixture(
                    FakeRetrieval.failing(failure), model);

            Throwable thrown = catchThrowable(
                    () -> fixture.answer(new KnowledgeAnswerCommand(QUESTION, null, null)));

            assertThat(thrown).as("code=%s", code).isSameAs(failure);
            assertThat(model.calls()).as("code=%s：检索失败时不得调用模型", code).isZero();
        }
    }

    @Test
    void neverCallsTheModelWhenTheQueryIsRejectedByRetrieval() {
        // 输入校验属于检索用例：它抛出的异常必须原样上抛（400 由它自己决定），而不是被包装成 502
        KnowledgeApplicationException invalid = new KnowledgeApplicationException(
                KnowledgeApplicationErrorCode.INVALID_RETRIEVAL_QUERY, "检索请求不合法");
        FakeChatModel model = new FakeChatModel("绝不该出现 [K1]。");
        KnowledgeAnswerUseCaseFixture fixture = new KnowledgeAnswerUseCaseFixture(
                FakeRetrieval.failing(invalid), model);

        assertThatThrownBy(() -> fixture.answer(new KnowledgeAnswerCommand(null, null, null)))
                .isSameAs(invalid);
        assertThat(model.calls()).isZero();
    }

    // ---------- 模型侧失败 ----------

    @Test
    void mapsAModelFailureToAiProviderErrorWithTheSameRequestId() {
        FakeChatModel model = new FakeChatModel(new IllegalStateException("连接被拒绝 sentinel-upstream"));
        KnowledgeAnswerUseCaseFixture fixture = new KnowledgeAnswerUseCaseFixture(
                FakeRetrieval.withCitations(1), model);

        Throwable thrown = catchThrowable(() -> fixture.answer(new KnowledgeAnswerCommand(QUESTION, null, null)));

        assertThat(thrown).isInstanceOf(AiProviderException.class);
        AiProviderException providerError = (AiProviderException) thrown;
        assertThat(providerError.requestId()).isNotBlank();
        assertThat(providerError)
                .as("对外文案固定，不含上游原文")
                .hasMessage("上游 AI 服务调用失败")
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsAnEmptyModelAnswerWith502Semantics() {
        for (String answer : Arrays.asList(null, "", "   ")) {
            KnowledgeAnswerUseCaseFixture fixture = new KnowledgeAnswerUseCaseFixture(
                    FakeRetrieval.withCitations(1), new FakeChatModel(answer));

            Throwable thrown = catchThrowable(
                    () -> fixture.answer(new KnowledgeAnswerCommand(QUESTION, null, null)));

            assertThat(thrown).as("answer=[%s]", answer).isInstanceOf(AiProviderException.class);
            assertThat(((AiProviderException) thrown).requestId()).isNotBlank();
        }
    }

    @Test
    void mapsAnInvalidOrUnknownCitationTo502WithoutLeakingTheAnswer() {
        for (String answer : List.of(
                "结论是重启服务。",
                "结论 [K0]。",
                "结论 [K99]。",
                "结论 [K1] 与 [K99]。",
                // FD-0012-R1：合法引用与畸形引用混合时，整次作答同样失败
                "合法 [K1]，伪造 [K-1]。",
                "合法 [K1]，伪造 [K1a]。",
                "合法 [K1]，伪造 [K 2]。",
                "合法 [K1]，伪造 [k9]。",
                "合法 [K1]，伪造 [K1。",
                // FD-0012-R2：K 后第一个字符是字母的绕过
                "正常结论 [K1]，伪造来源 [Kx1]",
                "合法 [K1]，伪造 [Ka-1]。",
                "合法 [K1]，伪造 [Known1]。",
                "合法 [K1]，伪造 [Kabc_1]。",
                "合法 [K1]，伪造 [ K999]。",
                "合法 [K1]，伪造 [ K1 ]。",
                "合法 [K1]，伪造 [\tK1]。",
                "合法 [K1]，伪造 [Kx1。")) {

            KnowledgeAnswerUseCaseFixture fixture = new KnowledgeAnswerUseCaseFixture(
                    FakeRetrieval.withCitations(1), new FakeChatModel(answer));

            Throwable thrown = catchThrowable(
                    () -> fixture.answer(new KnowledgeAnswerCommand(QUESTION, null, null)));

            assertThat(thrown).as("answer=[%s]", answer).isInstanceOf(AiProviderException.class);
            AiProviderException providerError = (AiProviderException) thrown;
            assertThat(providerError.requestId()).isNotBlank();
            assertThat(providerError.getMessage()).doesNotContain("结论");
            assertThat(providerError.getCause())
                    .as("失败类别作为 cause 保留在服务端")
                    .isInstanceOf(GroundedAnswerException.class);
        }
    }

    @Test
    void reportsTheInvalidCitationFormatFailureCategoryForAMixedAnswer() {
        // 合法引用在前、畸形引用在后：失败类别必须是「形式非法」，而不是被当成普通文字忽略
        for (String answer : List.of(
                "正常结论 [K1]，伪造来源 [K-1]",
                "正常结论 [K1]，伪造来源 [Kx1]",
                "正常结论 [K1]，伪造来源 [Ka-1]",
                "正常结论 [K1]，伪造来源 [Known1]")) {

            KnowledgeAnswerUseCaseFixture fixture = new KnowledgeAnswerUseCaseFixture(
                    FakeRetrieval.withCitations(1), new FakeChatModel(answer));

            Throwable thrown = catchThrowable(
                    () -> fixture.answer(new KnowledgeAnswerCommand(QUESTION, null, null)));

            assertThat(thrown).as("answer=[%s]", answer).isInstanceOf(AiProviderException.class);
            AiProviderException providerError = (AiProviderException) thrown;
            assertThat(providerError.requestId()).as("answer=[%s]", answer).isNotBlank();
            assertThat(providerError)
                    .as("answer=[%s]", answer)
                    .hasMessage("上游 AI 服务调用失败");
            assertThat(providerError.getCause())
                    .isInstanceOfSatisfying(GroundedAnswerException.class, cause -> assertThat(cause.failure())
                            .as("answer=[%s]", answer)
                            .isEqualTo(GroundedAnswerFailure.INVALID_CITATION_FORMAT));
        }
    }

    @Test
    void aNullCommandIsTreatedAsAMissingQueryAndStillGoesThroughRetrieval() {
        FakeRetrieval retrieval = FakeRetrieval.empty();
        KnowledgeAnswerUseCaseFixture fixture = new KnowledgeAnswerUseCaseFixture(
                retrieval, new FakeChatModel("绝不该出现 [K1]。"));

        // 检索替身不校验输入，因此这里能观察到「空命令被原样交给检索」
        KnowledgeAnswerResult result = fixture.answer(null);

        assertThat(retrieval.queries()).containsExactly((String) null);
        assertThat(result.grounded()).isFalse();
    }

    // ---------- 辅助 ----------

    /**
     * 从用户消息里取出结构化数据，返回其中 {@code question} 字段的取值。
     *
     * @param userPrompt 用户消息全文
     * @return 模型看到的问题
     * @throws JsonProcessingException 数据区块里的 JSON 不合法
     */
    private static String promptQuestion(String userPrompt) throws JsonProcessingException {
        int begin = userPrompt.indexOf(KnowledgeAnswerPromptBuilder.DATA_BEGIN);
        int end = userPrompt.indexOf(KnowledgeAnswerPromptBuilder.DATA_END, begin + 1);
        assertThat(begin).as("用户消息必须包含数据区块开始标记").isGreaterThanOrEqualTo(0);
        assertThat(end).as("用户消息必须包含数据区块结束标记").isGreaterThan(begin);

        String json = userPrompt.substring(begin + KnowledgeAnswerPromptBuilder.DATA_BEGIN.length(), end).strip();
        return MAPPER.readTree(json).path("question").asText();
    }

    /** 把两个协作者装到一起，避免每个测试重复三行构造代码。 */
    private static final class KnowledgeAnswerUseCaseFixture {

        private final GroundedKnowledgeAnswerService service;

        KnowledgeAnswerUseCaseFixture(RetrieveKnowledgeUseCase retrieval, ChatModel model) {
            this.service = new GroundedKnowledgeAnswerService(retrieval, ChatClient.create(model));
        }

        KnowledgeAnswerResult answer(KnowledgeAnswerCommand command) {
            return this.service.answer(command);
        }
    }

    /**
     * 检索用例替身：只记录收到的查询（含 {@code null}），返回预置证据或抛出预置异常。
     */
    private static final class FakeRetrieval implements RetrieveKnowledgeUseCase {

        private final List<String> queries = new ArrayList<>();

        private final KnowledgeRetrievalView view;

        private final KnowledgeApplicationException failure;

        private FakeRetrieval(KnowledgeRetrievalView view, KnowledgeApplicationException failure) {
            this.view = view;
            this.failure = failure;
        }

        static FakeRetrieval withCitations(int count) {
            List<KnowledgeCitationView> citations = new ArrayList<>();
            for (int index = 1; index <= count; index++) {
                citations.add(new KnowledgeCitationView("K" + index, index, DOCUMENT_ID, 4L,
                        "VPN 故障处理手册", index - 1, DIGEST, "第 " + index + " 段正文", 0.9 - index * 0.1));
            }
            return new FakeRetrieval(new KnowledgeRetrievalView("dashscope", "text-embedding-v4", 1024, 5, 0.30,
                    List.copyOf(citations)), null);
        }

        static FakeRetrieval empty() {
            return new FakeRetrieval(new KnowledgeRetrievalView("dashscope", "text-embedding-v4", 1024, 5, 0.30,
                    List.of()), null);
        }

        static FakeRetrieval failing(KnowledgeApplicationException failure) {
            return new FakeRetrieval(null, failure);
        }

        @Override
        public KnowledgeRetrievalView retrieve(RetrieveKnowledgeQuery query) {
            this.queries.add(query == null ? null : query.query());
            if (this.failure != null) {
                throw this.failure;
            }
            return this.view;
        }

        List<String> queries() {
            return new ArrayList<>(this.queries);
        }
    }

    /**
     * 假 ChatModel：返回预置答案或抛出预置异常，并记录每一次收到的 {@link Prompt}。
     */
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
