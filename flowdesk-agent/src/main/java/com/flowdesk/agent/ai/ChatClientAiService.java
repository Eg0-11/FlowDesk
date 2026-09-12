package com.flowdesk.agent.ai;

import com.flowdesk.application.ai.AiChatUseCase;
import com.flowdesk.application.ai.AiProviderException;
import com.flowdesk.application.ai.AiRequestException;
import com.flowdesk.application.ai.AiToolSmokeUseCase;
import com.flowdesk.application.ai.ChatCommand;
import com.flowdesk.application.ai.ChatResult;
import com.flowdesk.application.ai.IssueType;
import com.flowdesk.application.ai.ToolCallOutcome;
import com.flowdesk.application.ai.ToolSmokeCommand;
import com.flowdesk.application.ai.ToolSmokeResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

/**
 * 基于 Spring AI {@link ChatClient} 的 AI 用例实现。
 *
 * <p>两条路径刻意分开：</p>
 * <ul>
 *   <li>普通聊天：只发送用户消息，<b>不调用 {@code .tools(...)}</b>，模型看不到任何工具。</li>
 *   <li>工具冒烟：仅在本次请求上注册 {@link SupportPolicyTools}，并通过 {@code .toolContext(...)}
 *       传入 requestId 与请求级记录器，验证“模型调用工具 → 工具执行 → 模型汇总”的完整闭环。</li>
 * </ul>
 *
 * <p>{@code toolCalled} 完全来自 {@link ToolInvocationRecorder} 中真实的 Java 工具执行记录，
 * 不根据模型回答的文字推断，也没有任何硬编码。</p>
 */
public class ChatClientAiService implements AiChatUseCase, AiToolSmokeUseCase {

    private static final Logger log = LoggerFactory.getLogger(ChatClientAiService.class);

    private static final String TOOL_SMOKE_PROMPT_TEMPLATE = """
            工单类型：%s。
            请先调用 %s 工具查询该类型的支持策略，再严格依据工具返回的优先级、处理组与首个处理动作，
            用中文给出一段不超过三句话的处置建议。不要补充工具未返回的信息。
            """;

    private final ChatClient deepSeekChatClient;

    private final SupportPolicyTools supportPolicyTools;

    /**
     * @param deepSeekChatClient 由 infrastructure 提供的命名 ChatClient
     * @param supportPolicyTools 本地只读工具集
     */
    public ChatClientAiService(ChatClient deepSeekChatClient, SupportPolicyTools supportPolicyTools) {
        this.deepSeekChatClient = deepSeekChatClient;
        this.supportPolicyTools = supportPolicyTools;
    }

    @Override
    public ChatResult chat(ChatCommand command) {
        String message = requireValidMessage(command);
        String requestId = UUID.randomUUID().toString();
        long startedAt = System.nanoTime();
        String answer;
        try {
            answer = requireAnswer(deepSeekChatClient.prompt()
                    .user(message)
                    .call()
                    .content(), requestId);
        } catch (AiProviderException ex) {
            logFailure("ai.chat", requestId, startedAt);
            throw ex;
        } catch (RuntimeException ex) {
            logFailure("ai.chat", requestId, startedAt);
            throw new AiProviderException(requestId, ex);
        }
        log.info("ai.chat completed requestId={} tool=none success=true durationMs={}", requestId, elapsedMillis(startedAt));
        return new ChatResult(requestId, answer);
    }

    @Override
    public ToolSmokeResult toolSmoke(ToolSmokeCommand command) {
        IssueType issueType = IssueType.require(command == null ? null : command.issueType());
        String requestId = UUID.randomUUID().toString();
        ToolInvocationRecorder recorder = new ToolInvocationRecorder();
        long startedAt = System.nanoTime();
        String answer;
        try {
            answer = requireAnswer(deepSeekChatClient.prompt()
                    .user(TOOL_SMOKE_PROMPT_TEMPLATE.formatted(issueType.name(), SupportPolicyTools.TOOL_NAME))
                    .tools(supportPolicyTools)
                    .toolContext(Map.of(
                            ToolInvocationRecorder.CONTEXT_KEY, recorder,
                            ToolInvocationRecorder.REQUEST_ID_KEY, requestId))
                    .call()
                    .content(), requestId);
        } catch (AiProviderException ex) {
            logFailure("ai.tool-smoke", requestId, startedAt);
            throw ex;
        } catch (RuntimeException ex) {
            logFailure("ai.tool-smoke", requestId, startedAt);
            throw new AiProviderException(requestId, ex);
        }

        List<ToolCallOutcome> toolCalls = recorder.invocations().stream()
                .map(invocation -> new ToolCallOutcome(invocation.name(), invocation.success()))
                .toList();
        boolean toolCalled = recorder.anySucceeded();
        log.info("ai.tool-smoke completed requestId={} tool={} success=true toolCalled={} durationMs={}",
                requestId, SupportPolicyTools.TOOL_NAME, toolCalled, elapsedMillis(startedAt));
        return new ToolSmokeResult(requestId, answer, toolCalled, toolCalls);
    }

    /**
     * 校验并规范化用户消息；失败时抛出 {@link AiRequestException}（HTTP 400），
     * 该异常在进入模型调用前就抛出，因此不会被包装成上游错误。
     */
    private String requireValidMessage(ChatCommand command) {
        String raw = command == null ? null : command.message();
        if (raw == null) {
            throw new AiRequestException("message 不能为空");
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            throw new AiRequestException("message 不能为空");
        }
        if (trimmed.length() > ChatCommand.MAX_MESSAGE_LENGTH) {
            throw new AiRequestException("message 长度不能超过 " + ChatCommand.MAX_MESSAGE_LENGTH + " 个字符");
        }
        return trimmed;
    }

    /**
     * 上游返回空内容视为上游异常，避免把空回答包装成一次成功响应。
     */
    private String requireAnswer(String content, String requestId) {
        if (content == null || content.isBlank()) {
            throw new AiProviderException(requestId, null);
        }
        return content;
    }

    /**
     * 只记录 requestId、工具名、结果状态与耗时，不记录提示词、工具参数、工具结果与模型思维内容。
     */
    private void logFailure(String operation, String requestId, long startedAt) {
        log.warn("{} failed requestId={} tool={} success=false durationMs={}",
                operation, requestId, SupportPolicyTools.TOOL_NAME, elapsedMillis(startedAt));
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }
}
