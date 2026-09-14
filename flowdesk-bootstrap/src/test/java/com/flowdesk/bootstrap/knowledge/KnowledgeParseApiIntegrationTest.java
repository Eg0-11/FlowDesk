package com.flowdesk.bootstrap.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * 文档解析 HTTP 链路集成测试（FD-0009）。
 *
 * <p>走完整链路：{@code POST /parse} → 读原文 → 真实 Tika 解析 → 规范化 → 确定性切片 →
 * 原子落库 → 状态与版本更新。断言既有 HTTP 契约（状态码、ETag、错误码、不泄漏），
 * 也有数据库里可观察的事实（状态、版本、切片行）。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:flowdesk_knowledge_parse_it"
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "flowdesk.knowledge.storage.root=target/knowledge-parse-it",
        "flowdesk.knowledge.upload.max-size=1MB"
})
@AutoConfigureMockMvc
class KnowledgeParseApiIntegrationTest {

    private static final String BASE_PATH = "/api/v1/knowledge/documents";

    private static final Path STORAGE_ROOT = Path.of("target", "knowledge-parse-it");

    private static final String SENTENCE = "季度运维报告：所有工单均已关闭。";

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

    // ---------- ① 成功路径 ----------

    @Test
    void parsingATextDocumentReturns200WithETagVersionAndChunkCount() throws Exception {
        JsonNode uploaded = uploadText("季度运维报告", SENTENCE);

        MvcResult result = parse(uploaded.path("id").asText(), "\"0\"")
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"2\""))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.documentId").value(uploaded.path("id").asText()))
                .andExpect(jsonPath("$.title").value("季度运维报告"))
                .andExpect(jsonPath("$.status").value("PARSED"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.chunkCount").value(1))
                .andExpect(jsonPath("$.parsedAt").isNotEmpty())
                .andReturn();

        JsonNode body = body(result);
        assertThat(body.has("failureCode")).as("成功响应里不该出现失败码字段").isFalse();

        // 库里的事实必须与响应一致
        assertThat(statusOf(uploaded.path("id").asText())).isEqualTo("PARSED");
        assertThat(versionOf(uploaded.path("id").asText())).isEqualTo(2L);
        assertThat(chunkCountOf(uploaded.path("id").asText())).isEqualTo(1L);
        assertThat(storedChunkContents(uploaded.path("id").asText())).containsExactly(SENTENCE);
    }

    @Test
    void parsingAMarkdownDocumentWorksTheSameWay() throws Exception {
        JsonNode uploaded = upload("季度运维报告", "报告.md", "text/markdown", "# 标题\n\n正文内容。".getBytes(StandardCharsets.UTF_8));

        parse(uploaded.path("id").asText(), "\"0\"")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARSED"))
                .andExpect(jsonPath("$.chunkCount").value(1));

        assertThat(storedChunkContents(uploaded.path("id").asText()))
                .as("Markdown 保留标记，只做规范化与切片")
                .containsExactly("# 标题\n\n正文内容。");
    }

    @Test
    void parsingARealDocxExtractsBothParagraphs() throws Exception {
        JsonNode uploaded = upload("季度运维报告", "report.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                KnowledgeParseFixtures.docx());

        parse(uploaded.path("id").asText(), "\"0\"")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARSED"));

        List<String> chunks = storedChunkContents(uploaded.path("id").asText());
        assertThat(String.join("\n", chunks))
                .contains(KnowledgeParseFixtures.DOCX_FIRST_PARAGRAPH)
                .contains(KnowledgeParseFixtures.DOCX_SECOND_PARAGRAPH);
    }

    @Test
    void parsingARealPdfExtractsItsText() throws Exception {
        JsonNode uploaded = upload("季度运维报告", "report.pdf", "application/pdf",
                KnowledgeParseFixtures.pdf(KnowledgeParseFixtures.PDF_TEXT));

        parse(uploaded.path("id").asText(), "\"0\"")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARSED"));

        assertThat(String.join("", storedChunkContents(uploaded.path("id").asText())))
                .contains(KnowledgeParseFixtures.PDF_TEXT);
    }

    @Test
    void theDocumentShowsUpAsParsedInTheMetadataEndpoint() throws Exception {
        JsonNode uploaded = uploadText("季度运维报告", SENTENCE);
        parse(uploaded.path("id").asText(), "\"0\"").andExpect(status().isOk());

        this.mockMvc.perform(get(BASE_PATH + "/{id}", uploaded.path("id").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARSED"))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void chunkRowsAreIndexedFromZeroAndCoverTheWholeText() throws Exception {
        String longText = "第一段内容。\n\n".repeat(300);
        JsonNode uploaded = uploadText("长文档", longText);

        MvcResult result = parse(uploaded.path("id").asText(), "\"0\"").andExpect(status().isOk()).andReturn();
        int chunkCount = body(result).path("chunkCount").asInt();

        assertThat(chunkCount).isGreaterThan(1);
        List<Integer> indexes = this.jdbcClient
                .sql("SELECT chunk_index FROM knowledge_document_chunks WHERE document_id = ? ORDER BY chunk_index")
                .param(1, uploaded.path("id").asText())
                .query(Integer.class)
                .list();
        assertThat(indexes).as("序号必须从 0 连续递增").hasSize(chunkCount);
        for (int index = 0; index < chunkCount; index++) {
            assertThat(indexes.get(index)).isEqualTo(index);
        }
        assertThat(storedChunkContents(uploaded.path("id").asText()))
                .allSatisfy(chunk -> assertThat(chunk).isNotBlank());
    }

    // ---------- ② 前置条件与状态机 ----------

    @Test
    void missingIfMatchReturns428() throws Exception {
        JsonNode uploaded = uploadText("季度运维报告", SENTENCE);

        this.mockMvc.perform(post(BASE_PATH + "/{id}/parse", uploaded.path("id").asText()))
                .andExpect(status().isPreconditionRequired())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"))
                .andExpect(jsonPath("$.type").value("urn:flowdesk:problem:precondition-required"));

        assertThat(statusOf(uploaded.path("id").asText())).as("缺少前置条件时不得发生任何状态变化")
                .isEqualTo("UPLOADED");
    }

    @Test
    void malformedIfMatchReturns400() throws Exception {
        JsonNode uploaded = uploadText("季度运维报告", SENTENCE);

        for (String malformed : new String[] { "0", "W/\"0\"", "*", "\"01\"", "\"-1\"", "\"abc\"", "\"\"" }) {
            parse(uploaded.path("id").asText(), malformed)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_IF_MATCH"));
        }
        assertThat(versionOf(uploaded.path("id").asText())).isZero();
    }

    @Test
    void staleIfMatchReturns412WithoutWritingAnything() throws Exception {
        JsonNode uploaded = uploadText("季度运维报告", SENTENCE);

        parse(uploaded.path("id").asText(), "\"1\"")
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_DOCUMENT_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.type").value("urn:flowdesk:problem:knowledge-document-version-conflict"));

        assertThat(statusOf(uploaded.path("id").asText())).isEqualTo("UPLOADED");
        assertThat(versionOf(uploaded.path("id").asText())).isZero();
    }

    @Test
    void parsingAnAlreadyParsedDocumentReturns409() throws Exception {
        JsonNode uploaded = uploadText("季度运维报告", SENTENCE);
        parse(uploaded.path("id").asText(), "\"0\"").andExpect(status().isOk());

        parse(uploaded.path("id").asText(), "\"2\"")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_DOCUMENT_NOT_PARSABLE"));

        assertThat(versionOf(uploaded.path("id").asText())).as("被拒绝的重复解析不得改变版本").isEqualTo(2L);
    }

    @Test
    void unknownDocumentReturns404() throws Exception {
        parse(UUID.randomUUID().toString(), "\"0\"")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_DOCUMENT_NOT_FOUND"));
    }

    @Test
    void malformedDocumentIdReturns400() throws Exception {
        parse("1-1-1-1-1", "\"0\"")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void unacceptableAcceptReturns406ForParse() throws Exception {
        JsonNode uploaded = uploadText("季度运维报告", SENTENCE);

        this.mockMvc.perform(post(BASE_PATH + "/{id}/parse", uploaded.path("id").asText())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"));
    }

    // ---------- ③ 解析失败 ----------

    @Test
    void aCorruptedDocumentReturns422AndLeavesTheDocumentRetryable() throws Exception {
        JsonNode uploaded = upload("季度运维报告", "broken.pdf", "application/pdf", KnowledgeParseFixtures.corruptedPdf());

        parse(uploaded.path("id").asText(), "\"0\"")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("DOCUMENT_PARSE_FAILED"))
                .andExpect(jsonPath("$.failureCode").value("CORRUPTED_DOCUMENT"))
                .andExpect(jsonPath("$.type").value("urn:flowdesk:problem:document-parse-failed"))
                .andExpect(jsonPath("$.status").value(422));

        assertThat(statusOf(uploaded.path("id").asText()))
                .as("失败的解析必须落成可重试的状态，而不是停在 PARSING")
                .isEqualTo("PARSE_FAILED");
        assertThat(failureCodeOf(uploaded.path("id").asText())).isEqualTo("CORRUPTED_DOCUMENT");
        assertThat(chunkCountOf(uploaded.path("id").asText())).as("失败的解析不得留下切片").isZero();
    }

    @Test
    void aFailedDocumentCanBeRepairedAndParsedAgain() throws Exception {
        JsonNode uploaded = upload("季度运维报告", "broken.pdf", "application/pdf", KnowledgeParseFixtures.corruptedPdf());
        parse(uploaded.path("id").asText(), "\"0\"").andExpect(status().isUnprocessableEntity());

        // 运维把原始文件换成一份完好的 PDF（内容键不变，只替换对象内容）
        String contentKey = this.jdbcClient.sql("SELECT content_key FROM knowledge_documents WHERE id = ?")
                .param(1, uploaded.path("id").asText()).query(String.class).single();
        Files.write(STORAGE_ROOT.resolve("documents").resolve(contentKey),
                KnowledgeParseFixtures.pdf(KnowledgeParseFixtures.PDF_TEXT));

        // 失败文档的版本是 2（领取 +1、补偿 +1）
        parse(uploaded.path("id").asText(), "\"2\"")
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"4\""))
                .andExpect(jsonPath("$.status").value("PARSED"));

        assertThat(failureCodeOf(uploaded.path("id").asText())).isNull();
        assertThat(String.join("", storedChunkContents(uploaded.path("id").asText())))
                .contains(KnowledgeParseFixtures.PDF_TEXT);
    }

    // ---------- ④ 不泄漏内部信息 ----------

    @Test
    void neitherSuccessNorErrorResponsesLeakContentKeysPathsOrParserDetails() throws Exception {
        JsonNode uploaded = upload("季度运维报告", "broken.pdf", "application/pdf", KnowledgeParseFixtures.corruptedPdf());

        String success = parse(uploadTextReturningId(), "\"0\"").andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        String failure = parse(uploaded.path("id").asText(), "\"0\"").andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);

        for (String body : new String[] { success, failure }) {
            assertThat(body)
                    .doesNotContain("contentKey")
                    .doesNotContain("content_key")
                    .doesNotContain("kdoc-")
                    .doesNotContain("knowledge-parse-it")
                    .doesNotContain(STORAGE_ROOT.toAbsolutePath().toString())
                    .doesNotContain("Exception")
                    .doesNotContain("org.apache")
                    .doesNotContain("PDFBox")
                    .doesNotContain("Tika")
                    .doesNotContain("chunks")
                    .doesNotContain("\"content\"")
                    .doesNotContain("SELECT");
        }

        // 成功响应里也不能出现原文
        assertThat(success).doesNotContain(SENTENCE);
    }

    @Test
    void chunkContentIsNeverExposedThroughAnyEndpoint() throws Exception {
        JsonNode uploaded = uploadText("季度运维报告", SENTENCE);
        parse(uploaded.path("id").asText(), "\"0\"").andExpect(status().isOk());

        String fetched = this.mockMvc.perform(get(BASE_PATH + "/{id}", uploaded.path("id").asText()))
                .andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(fetched)
                .as("切片内容属于下一阶段（向量化）的内部输入，不通过 HTTP 暴露")
                .doesNotContain(SENTENCE)
                .doesNotContain("chunks");
    }

    // ---------- 辅助 ----------

    private JsonNode uploadText(String title, String text) throws Exception {
        return upload(title, "notes.txt", "text/plain", text.getBytes(StandardCharsets.UTF_8));
    }

    /** 上传一份可成功解析的文档，只返回其标识（用于「成功响应体」断言）。 */
    private String uploadTextReturningId() throws Exception {
        return upload("季度运维报告", "notes.txt", "text/plain", SENTENCE.getBytes(StandardCharsets.UTF_8))
                .path("id").asText();
    }

    private JsonNode upload(String title, String fileName, String contentType, byte[] content) throws Exception {
        MvcResult result = this.mockMvc.perform(multipart(BASE_PATH)
                        .file(new MockMultipartFile("file", fileName, contentType, content))
                        .param("title", title))
                .andExpect(status().isCreated())
                .andReturn();
        return body(result);
    }

    private ResultActions parse(String documentId, String ifMatch) throws Exception {
        return this.mockMvc.perform(post(BASE_PATH + "/{id}/parse", documentId)
                .header(HttpHeaders.IF_MATCH, ifMatch));
    }

    private JsonNode body(MvcResult result) throws Exception {
        return this.objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
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

    private String failureCodeOf(String documentId) {
        return this.jdbcClient.sql("SELECT parse_failure_code FROM knowledge_documents WHERE id = ?")
                .param(1, documentId).query(String.class).optional().orElse(null);
    }

    private long chunkCountOf(String documentId) {
        Long count = this.jdbcClient.sql("SELECT COUNT(*) FROM knowledge_document_chunks WHERE document_id = ?")
                .param(1, documentId).query(Long.class).single();
        return count == null ? -1L : count;
    }

    private List<String> storedChunkContents(String documentId) {
        return this.jdbcClient.sql("SELECT content FROM knowledge_document_chunks WHERE document_id = ? "
                + "ORDER BY chunk_index")
                .param(1, documentId).query(String.class).list();
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
