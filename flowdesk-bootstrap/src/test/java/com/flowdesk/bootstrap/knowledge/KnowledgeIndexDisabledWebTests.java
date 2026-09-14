package com.flowdesk.bootstrap.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowdesk.infrastructure.knowledge.embedding.DisabledKnowledgeEmbedding;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 默认 profile 下的索引接口测试（FD-0010）。
 *
 * <p>默认环境<b>没有</b>启用向量化，因此这里验证的是那条「关闭」路径的完整语义：
 * 接口返回 <b>503 {@code KNOWLEDGE_EMBEDDING_DISABLED}</b>，并且<b>不读取、不修改任何文档</b>；
 * 同时确认没有 EmbeddingModel、没有向量表可用。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:flowdesk_knowledge_index_disabled_it"
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "flowdesk.knowledge.storage.root=target/knowledge-index-disabled-it"
})
@AutoConfigureMockMvc
class KnowledgeIndexDisabledWebTests {

    private static final String BASE_PATH = "/api/v1/knowledge/documents";

    private static final Path STORAGE_ROOT = Path.of("target", "knowledge-index-disabled-it");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private org.springframework.beans.factory.ObjectProvider<org.springframework.ai.embedding.EmbeddingModel>
            embeddingModels;

    @Autowired
    private org.springframework.beans.factory.ObjectProvider<
            com.flowdesk.application.knowledge.port.out.KnowledgeEmbeddingPort> embeddingPorts;

    @Autowired
    private org.springframework.beans.factory.ObjectProvider<
            com.flowdesk.application.knowledge.port.out.KnowledgeDocumentEmbeddingStore> embeddingStores;

    @BeforeEach
    void clearState() throws IOException {
        this.jdbcClient.sql("DELETE FROM knowledge_document_chunks").update();
        this.jdbcClient.sql("DELETE FROM knowledge_documents").update();
        deleteRecursively(STORAGE_ROOT);
    }

    @Test
    void noEmbeddingModelExistsInTheDefaultProfile() {
        assertThat(this.embeddingModels.getIfAvailable())
                .as("默认环境不得创建 EmbeddingModel（否则会要求 API Key、也可能发起网络请求）")
                .isNull();
        assertThat(this.embeddingPorts.getIfAvailable())
                .as("端口仍然存在，但由「拒绝一切」的占位实现提供，从而保持 HTTP 契约稳定")
                .isInstanceOf(DisabledKnowledgeEmbedding.Port.class);
        assertThat(this.embeddingStores.getIfAvailable())
                .isInstanceOf(DisabledKnowledgeEmbedding.Store.class);
    }

    @Test
    void indexingReturns503WithoutTouchingTheDocument() throws Exception {
        String documentId = uploadText("季度运维报告");

        MvcResult result = this.mockMvc.perform(post(BASE_PATH + "/{id}/index", documentId)
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_EMBEDDING_DISABLED"))
                .andExpect(jsonPath("$.type").value("urn:flowdesk:problem:knowledge-embedding-disabled"))
                .andExpect(jsonPath("$.status").value(503))
                .andReturn();

        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .as("错误响应不得泄漏任何内部细节")
                .doesNotContain("contentKey")
                .doesNotContain("kdoc-")
                .doesNotContain("Exception")
                .doesNotContain("java.");

        assertThat(statusOf(documentId)).as("关闭状态下不得修改文档").isEqualTo("UPLOADED");
        assertThat(versionOf(documentId)).as("版本不得变化").isZero();
        assertThat(indexedAtOf(documentId)).isNull();
    }

    @Test
    void missingOrMalformedIfMatchIsStillRejectedBeforeTheDisabledCheck() throws Exception {
        String documentId = uploadText("季度运维报告");

        this.mockMvc.perform(post(BASE_PATH + "/{id}/index", documentId))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));

        this.mockMvc.perform(post(BASE_PATH + "/{id}/index", documentId)
                        .header(HttpHeaders.IF_MATCH, "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IF_MATCH"));
    }

    @Test
    void metadataResponseOmitsFieldsThatDoNotApplyYet() throws Exception {
        String documentId = uploadText("季度运维报告");

        MvcResult result = this.mockMvc.perform(get(BASE_PATH + "/{id}", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andExpect(jsonPath("$.parsedAt").doesNotExist())
                .andExpect(jsonPath("$.indexedAt").doesNotExist())
                .andExpect(jsonPath("$.embeddingProvider").doesNotExist())
                .andExpect(jsonPath("$.embeddingModel").doesNotExist())
                .andExpect(jsonPath("$.embeddingDimensions").doesNotExist())
                .andReturn();

        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .as("NON_NULL：不相关字段不输出，也不输出空对象")
                .doesNotContain("null");
    }

    private String uploadText(String title) throws Exception {
        MvcResult result = this.mockMvc.perform(multipart(BASE_PATH)
                        .file(new MockMultipartFile("file", "notes.txt", "text/plain",
                                "知识库文档内容\n".getBytes(StandardCharsets.UTF_8)))
                        .param("title", title))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = this.objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return body.path("id").asText();
    }

    private String statusOf(String documentId) {
        return this.jdbcClient.sql("SELECT status FROM knowledge_documents WHERE id = ?")
                .param(1, documentId).query(String.class).single();
    }

    private long versionOf(String documentId) {
        Long version = this.jdbcClient.sql("SELECT version FROM knowledge_documents WHERE id = ?")
                .param(1, documentId).query(Long.class).single();
        return version == null ? -1L : version;
    }

    private java.time.OffsetDateTime indexedAtOf(String documentId) {
        return this.jdbcClient.sql("SELECT indexed_at FROM knowledge_documents WHERE id = ?")
                .param(1, documentId).query(java.time.OffsetDateTime.class).optional().orElse(null);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
