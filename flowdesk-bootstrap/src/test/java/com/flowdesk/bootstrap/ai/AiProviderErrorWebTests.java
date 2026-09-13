package com.flowdesk.bootstrap.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flowdesk.application.ai.AiChatUseCase;
import com.flowdesk.application.ai.AiProviderException;
import com.flowdesk.application.ai.AiToolSmokeUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import com.flowdesk.bootstrap.web.FlowDeskProblems;

/**
 * 上游模型失败的 HTTP 契约：必须映射为 502，且响应体不得泄露供应商异常细节。
 */
@WebMvcTest(controllers = AiController.class, properties = "flowdesk.ai.enabled=true")
@Import(AiProviderErrorWebTests.StubUseCases.class)
class AiProviderErrorWebTests {

    private static final String REQUEST_ID = "2f1c4b6e-2222-4000-8000-000000000002";

    /** 绝不能出现在客户端响应里的内部细节。 */
    static final String PROVIDER_SECRET = "provider-internal-detail-should-never-leak";

    @Autowired
    private MockMvc mockMvc;

    @TestConfiguration(proxyBeanMethods = false)
    static class StubUseCases {

        @Bean
        AiChatUseCase aiChatUseCase() {
            return command -> {
                throw new AiProviderException(REQUEST_ID,
                        new IllegalStateException(PROVIDER_SECRET + " (stacktrace-marker)"));
            };
        }

        @Bean
        AiToolSmokeUseCase aiToolSmokeUseCase() {
            return command -> {
                throw new AiProviderException(REQUEST_ID,
                        new IllegalStateException(PROVIDER_SECRET + " (stacktrace-marker)"));
            };
        }
    }

    @Test
    void mapsProviderFailureToBadGateway() throws Exception {
        mockMvc.perform(post("/api/v1/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"请用一句话介绍 FlowDesk"}
                                """))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value(FlowDeskProblems.CODE_AI_PROVIDER_ERROR))
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID));
    }

    @Test
    void toolSmokeMapsProviderFailureToBadGateway() throws Exception {
        mockMvc.perform(post("/api/v1/ai/tool-smoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"issueType":"VPN_FAILURE"}
                                """))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value(FlowDeskProblems.CODE_AI_PROVIDER_ERROR));
    }

    @Test
    void doesNotLeakProviderExceptionDetails() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"请用一句话介绍 FlowDesk"}
                                """))
                .andExpect(status().isBadGateway())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(PROVIDER_SECRET);
        assertThat(body).doesNotContain("IllegalStateException");
        assertThat(body).doesNotContain("stacktrace-marker");
        assertThat(body).doesNotContain("at com.");
    }
}
