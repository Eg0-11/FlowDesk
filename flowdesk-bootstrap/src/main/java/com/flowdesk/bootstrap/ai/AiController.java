package com.flowdesk.bootstrap.ai;

import com.flowdesk.application.ai.AiChatUseCase;
import com.flowdesk.application.ai.AiToolSmokeUseCase;
import com.flowdesk.application.ai.ChatCommand;
import com.flowdesk.application.ai.ChatResult;
import com.flowdesk.application.ai.ToolCallOutcome;
import com.flowdesk.application.ai.ToolSmokeCommand;
import com.flowdesk.application.ai.ToolSmokeResult;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * FlowDesk AI HTTP 接口。
 *
 * <p>本类只做三件事：校验请求、调用 application 用例、把结果映射成响应体。
 * 它不注入 {@code ChatClient}、不注入工具、也不认识任何具体编排实现，
 * 因此 AI 实现的替换不会影响 HTTP 契约。</p>
 *
 * <p>与 AI 服务保持一致：仅在 {@code flowdesk.ai.enabled=true} 时注册，
 * 默认 profile 下这两个端点不存在。</p>
 */
@RestController
@RequestMapping("/api/v1/ai")
@ConditionalOnProperty(name = "flowdesk.ai.enabled", havingValue = "true")
public class AiController {

    private final AiChatUseCase aiChatUseCase;

    private final AiToolSmokeUseCase aiToolSmokeUseCase;

    /**
     * @param aiChatUseCase      普通聊天用例
     * @param aiToolSmokeUseCase 工具调用冒烟用例
     */
    public AiController(AiChatUseCase aiChatUseCase, AiToolSmokeUseCase aiToolSmokeUseCase) {
        this.aiChatUseCase = aiChatUseCase;
        this.aiToolSmokeUseCase = aiToolSmokeUseCase;
    }

    /**
     * 普通聊天：不注册任何工具。
     *
     * @param request 聊天请求
     * @return 含 requestId 与模型回答的响应
     */
    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        ChatResult result = aiChatUseCase.chat(new ChatCommand(request.message()));
        return new ChatResponse(result.requestId(), result.answer());
    }

    /**
     * 工具调用冒烟：单次请求内注册本地只读工具并验证完整闭环。
     *
     * @param request 冒烟请求
     * @return 含 requestId、最终回答与真实工具调用记录的响应
     */
    @PostMapping("/tool-smoke")
    public ToolSmokeResponse toolSmoke(@Valid @RequestBody ToolSmokeRequest request) {
        ToolSmokeResult result = aiToolSmokeUseCase.toolSmoke(new ToolSmokeCommand(request.issueType()));
        List<ToolCallView> toolCalls = result.toolCalls().stream()
                .map(this::toView)
                .toList();
        return new ToolSmokeResponse(result.requestId(), result.answer(), result.toolCalled(), toolCalls);
    }

    private ToolCallView toView(ToolCallOutcome outcome) {
        return new ToolCallView(outcome.name(), outcome.success());
    }
}
