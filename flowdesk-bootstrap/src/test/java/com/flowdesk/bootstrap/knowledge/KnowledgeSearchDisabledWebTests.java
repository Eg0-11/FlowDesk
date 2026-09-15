package com.flowdesk.bootstrap.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flowdesk.application.knowledge.port.out.KnowledgeQueryEmbeddingPort;
import com.flowdesk.application.knowledge.port.out.KnowledgeVectorSearchPort;
import com.flowdesk.infrastructure.knowledge.embedding.DisabledKnowledgeEmbedding;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 默认 profile 下的检索接口测试（RAG 4/6）。
 *
 * <p>默认环境<b>没有</b>启用向量化，因此这里验证「关闭」路径的完整语义：
 * 用例与接口仍然装配（有效请求得到 <b>503 {@code KNOWLEDGE_EMBEDDING_DISABLED}</b>），
 * 但<b>不调用模型、不访问向量表</b>，也不需要任何 API Key 或 PostgreSQL。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:flowdesk_knowledge_search_disabled_it"
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "flowdesk.knowledge.storage.root=target/knowledge-search-disabled-it"
})
@AutoConfigureMockMvc
class KnowledgeSearchDisabledWebTests {

    private static final String SEARCH_PATH = "/api/v1/knowledge/search";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectProvider<KnowledgeQueryEmbeddingPort> queryPorts;

    @Autowired
    private ObjectProvider<KnowledgeVectorSearchPort> searchPorts;

    @Autowired
    private ObjectProvider<org.springframework.ai.embedding.EmbeddingModel> embeddingModels;

    @Autowired
    private com.flowdesk.application.knowledge.port.in.RetrieveKnowledgeUseCase retrieveUseCase;

    @Test
    void theUseCaseAndThePortsAreAssembledWithDisabledPlaceholders() {
        assertThat(this.retrieveUseCase)
                .as("关闭状态下检索用例仍然存在，503 才是稳定契约而不是「Bean 缺失」")
                .isNotNull();
        assertThat(this.queryPorts.getIfAvailable()).isInstanceOf(DisabledKnowledgeEmbedding.QueryPort.class);
        assertThat(this.searchPorts.getIfAvailable()).isInstanceOf(DisabledKnowledgeEmbedding.Search.class);
        assertThat(this.embeddingModels.getIfAvailable())
                .as("默认环境不创建 EmbeddingModel，也不需要 API Key")
                .isNull();
    }

    @Test
    void aValidSearchReturns503WithoutTouchingAnything() throws Exception {
        MvcResult result = this.mockMvc.perform(post(SEARCH_PATH)
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
    }

    @Test
    void anInvalidSearchIsStillRejectedWith400BeforeTheDisabledCheck() throws Exception {
        // 输入校验先于开关检查：请求本身不合法时不返回 503，而是 400
        this.mockMvc.perform(post(SEARCH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"VPN\",\"topK\":999}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.detail").value("检索请求不合法"));
    }
}
