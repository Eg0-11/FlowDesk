package com.flowdesk.bootstrap.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * AI 已启用但向量化未启用时的问答接口契约（RAG 5/6）。
 *
 * <p>这是最容易被忽略的一种组合：模型客户端<b>存在且可调用</b>，但检索侧关闭。
 * 契约必须是「请求根本不走到模型」—— 输入不合法先得 400，输入合法则得到检索自己的
 * 503 {@code KNOWLEDGE_EMBEDDING_DISABLED}，两种情况都不产生任何模型请求。</p>
 */
@SpringBootTest(properties = {
        "spring.profiles.active=deepseek",
        "DEEPSEEK_API_KEY=test-fake-key-not-a-real-secret",
        "spring.datasource.url=jdbc:h2:mem:flowdesk_knowledge_answer_no_embedding_it"
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "flowdesk.knowledge.storage.root=target/knowledge-answer-no-embedding-it"
})
@AutoConfigureMockMvc
class KnowledgeAnswerEmbeddingDisabledWebTests {

    private static final String ANSWER_PATH = "/api/v1/ai/knowledge-answer";

    private static SyntheticOpenAiEndpoint endpoint;

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void pointAtSyntheticEndpoint(DynamicPropertyRegistry registry) throws IOException {
        if (endpoint == null) {
            endpoint = SyntheticOpenAiEndpoint.start(SyntheticOpenAiEndpoint.Behaviour.STATIC_ANSWER);
        }
        registry.add("spring.ai.openai.base-url", endpoint::baseUrl);
    }

    @AfterAll
    static void stopEndpoint() {
        if (endpoint != null) {
            endpoint.stop();
        }
    }

    @Test
    void aValidQuestionReturns503AndNeverReachesTheModel() throws Exception {
        endpoint.willAnswer("这次回答绝不该出现 [K1]。");
        endpoint.clearRequests();

        MvcResult result = this.mockMvc.perform(post(ANSWER_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"VPN 无法连接应该如何处理？\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_EMBEDDING_DISABLED"))
                .andExpect(jsonPath("$.type").value("urn:flowdesk:problem:knowledge-embedding-disabled"))
                .andExpect(jsonPath("$.status").value(503))
                .andReturn();

        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .as("错误响应不得泄漏 query 或内部细节")
                .doesNotContain("VPN 无法连接")
                .doesNotContain("Exception")
                .doesNotContain("java.");
        assertThat(endpoint.requests())
                .as("向量化未启用时不得调用模型 —— 没有证据就不该有答案")
                .isEmpty();
    }

    @Test
    void anInvalidQuestionIsStillRejectedWith400AndNeverReachesTheModel() throws Exception {
        endpoint.clearRequests();

        this.mockMvc.perform(post(ANSWER_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"VPN\",\"topK\":999}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.detail").value("检索请求不合法"));

        assertThat(endpoint.requests()).isEmpty();
    }
}
