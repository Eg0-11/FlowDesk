package com.flowdesk.bootstrap.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 解析规模上限的 HTTP 行为测试（FD-0009）。
 *
 * <p>用一组刻意调小的配置启动上下文，验证两类「文档太大」在真实链路上都被拒绝，
 * 而且文档落在可重试的失败态、不留下半个切片：</p>
 * <ul>
 *   <li>提取文本超过 {@code max-extracted-code-points} → 413 {@code DOCUMENT_TOO_LARGE}；</li>
 *   <li>切片数量超过 {@code max-chunks} → 413 {@code DOCUMENT_TOO_MANY_CHUNKS}。</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:flowdesk_knowledge_parse_limit_it"
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "flowdesk.knowledge.storage.root=target/knowledge-parse-limit-it",
        "flowdesk.knowledge.upload.max-size=1MB",
        "flowdesk.knowledge.chunking.chunk-size=10",
        "flowdesk.knowledge.chunking.overlap=0",
        "flowdesk.knowledge.chunking.max-chunks=2",
        "flowdesk.knowledge.chunking.max-extracted-code-points=30"
})
@AutoConfigureMockMvc
class KnowledgeParseLimitWebTests {

    private static final String BASE_PATH = "/api/v1/knowledge/documents";

    private static final Path STORAGE_ROOT = Path.of("target", "knowledge-parse-limit-it");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void clearState() throws IOException {
        this.jdbcClient.sql("DELETE FROM knowledge_document_chunks").update();
        this.jdbcClient.sql("DELETE FROM knowledge_documents").update();
        deleteRecursively(STORAGE_ROOT);
    }

    @Test
    void textLongerThanTheExtractionLimitReturns413AndWritesNoChunks() throws Exception {
        // 31 个 code point > 上限 30
        String uploadedId = uploadText("一".repeat(31));

        parse(uploadedId, "\"0\"")
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("DOCUMENT_TOO_LARGE"))
                .andExpect(jsonPath("$.failureCode").value("EXTRACTED_TEXT_TOO_LARGE"))
                .andExpect(jsonPath("$.type").value("urn:flowdesk:problem:document-too-large"));

        assertThat(statusOf(uploadedId)).isEqualTo("PARSE_FAILED");
        assertThat(chunkCountOf(uploadedId)).isZero();
    }

    @Test
    void reachingMaxChunksExactlyIsAccepted() throws Exception {
        // 20 个字符、每片 10 个：正好 2 片（等于上限），必须成功
        String uploadedId = uploadText("一".repeat(20));

        parse(uploadedId, "\"0\"")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARSED"))
                .andExpect(jsonPath("$.chunkCount").value(2));

        assertThat(chunkCountOf(uploadedId)).isEqualTo(2L);
    }

    @Test
    void moreChunksThanAllowedReturns413WithItsOwnCode() throws Exception {
        // 30 个字符、每片 10 个：会产生 3 片 > 上限 2
        String uploadedId = uploadText("二".repeat(30));

        parse(uploadedId, "\"0\"")
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("DOCUMENT_TOO_MANY_CHUNKS"))
                .andExpect(jsonPath("$.failureCode").value("TOO_MANY_CHUNKS"))
                .andExpect(jsonPath("$.type").value("urn:flowdesk:problem:document-too-many-chunks"));

        assertThat(statusOf(uploadedId)).isEqualTo("PARSE_FAILED");
        assertThat(failureCodeOf(uploadedId)).isEqualTo("TOO_MANY_CHUNKS");
        assertThat(chunkCountOf(uploadedId))
                .as("切片数超限时必须整体放弃，而不是写入前两片")
                .isZero();
    }

    @Test
    void aTooLargeDocumentCanStillBeReadBackAndRetriedAfterFixingTheContent() throws Exception {
        String uploadedId = uploadText("三".repeat(31));
        parse(uploadedId, "\"0\"").andExpect(status().isPayloadTooLarge());

        // 换成一份短文档后重试（失败态是刻意设计成可重试的）
        String contentKey = this.jdbcClient.sql("SELECT content_key FROM knowledge_documents WHERE id = ?")
                .param(1, uploadedId).query(String.class).single();
        Files.writeString(STORAGE_ROOT.resolve("documents").resolve(contentKey), "短文档。", StandardCharsets.UTF_8);

        parse(uploadedId, "\"2\"")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARSED"))
                .andExpect(jsonPath("$.chunkCount").value(1));

        assertThat(failureCodeOf(uploadedId)).isNull();
    }

    private String uploadText(String text) throws Exception {
        MvcResult result = this.mockMvc.perform(multipart(BASE_PATH)
                        .file(new MockMultipartFile("file", "notes.txt", "text/plain",
                                text.getBytes(StandardCharsets.UTF_8)))
                        .param("title", "上限测试"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = this.objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return body.path("id").asText();
    }

    private org.springframework.test.web.servlet.ResultActions parse(String documentId, String ifMatch)
            throws Exception {

        return this.mockMvc.perform(post(BASE_PATH + "/{id}/parse", documentId)
                .header(HttpHeaders.IF_MATCH, ifMatch));
    }

    private String statusOf(String documentId) {
        return this.jdbcClient.sql("SELECT status FROM knowledge_documents WHERE id = ?")
                .param(1, documentId).query(String.class).single();
    }

    private String failureCodeOf(String documentId) {
        return this.jdbcClient.sql("SELECT parse_failure_code FROM knowledge_documents WHERE id = ?")
                .param(1, documentId).query(String.class).optional().orElse(null);
    }

    private long chunkCountOf(String documentId) {
        Long count = this.jdbcClient.sql("SELECT COUNT(*) FROM knowledge_document_chunks WHERE document_id = ?")
                .param(1, documentId).query(Long.class).single();
        return count == null ? -1L : count;
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
