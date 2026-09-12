package com.flowdesk.bootstrap.ai;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flowdesk.application.ai.AiChatUseCase;
import com.flowdesk.application.ai.AiToolSmokeUseCase;
import com.flowdesk.application.ai.ChatResult;
import com.flowdesk.application.ai.ToolCallOutcome;
import com.flowdesk.application.ai.ToolSmokeResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * AI HTTP 边界的正常响应与参数校验测试。
 *
 * <p>这里替换的是 application 层的用例接口（Web 层的直接协作者），
 * 不对 Spring AI 的 {@code ChatClient} 链式 API 做任何深打桩。</p>
 */
@WebMvcTest(controllers = AiController.class, properties = "flowdesk.ai.enabled=true")
@Import(AiControllerWebTests.StubUseCases.class)
class AiControllerWebTests {

    private static final String REQUEST_ID = "2f1c4b6e-1111-4000-8000-000000000001";

    private static final String CHAT_ANSWER = "FlowDesk 是企业智能工单与知识运营平台。";

    private static final String TOOL_ANSWER = "已按 P1 优先级转派网络与接入组处理。";

    @Autowired
    private MockMvc mockMvc;

    @TestConfiguration(proxyBeanMethods = false)
    static class StubUseCases {

        @Bean
        AiChatUseCase aiChatUseCase() {
            return command -> new ChatResult(REQUEST_ID, CHAT_ANSWER);
        }

        @Bean
        AiToolSmokeUseCase aiToolSmokeUseCase() {
            return command -> new ToolSmokeResult(REQUEST_ID, TOOL_ANSWER, true,
                    List.of(new ToolCallOutcome("lookup_support_policy", true)));
        }
    }

    @Test
    void chatReturnsRequestIdAndAnswer() throws Exception {
        mockMvc.perform(post("/api/v1/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"请用一句话介绍 FlowDesk"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID))
                .andExpect(jsonPath("$.answer").value(CHAT_ANSWER));
    }

    @Test
    void toolSmokeReturnsRealToolCallRecord() throws Exception {
        mockMvc.perform(post("/api/v1/ai/tool-smoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"issueType":"VPN_FAILURE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID))
                .andExpect(jsonPath("$.answer").value(TOOL_ANSWER))
                .andExpect(jsonPath("$.toolCalled").value(true))
                .andExpect(jsonPath("$.toolCalls[0].name").value("lookup_support_policy"))
                .andExpect(jsonPath("$.toolCalls[0].success").value(true));
    }

    @Test
    void rejectsBlankMessage() throws Exception {
        mockMvc.perform(post("/api/v1/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(AiExceptionHandler.CODE_INVALID_REQUEST));
    }

    @Test
    void rejectsMissingMessage() throws Exception {
        mockMvc.perform(post("/api/v1/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(AiExceptionHandler.CODE_INVALID_REQUEST));
    }

    @Test
    void rejectsTooLongMessage() throws Exception {
        String tooLong = "a".repeat(4001);

        mockMvc.perform(post("/api/v1/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(AiExceptionHandler.CODE_INVALID_REQUEST));
    }

    @Test
    void rejectsBlankIssueType() throws Exception {
        mockMvc.perform(post("/api/v1/ai/tool-smoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"issueType":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(AiExceptionHandler.CODE_INVALID_REQUEST));
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        mockMvc.perform(post("/api/v1/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ this is not json }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(AiExceptionHandler.CODE_INVALID_REQUEST));
    }
}
