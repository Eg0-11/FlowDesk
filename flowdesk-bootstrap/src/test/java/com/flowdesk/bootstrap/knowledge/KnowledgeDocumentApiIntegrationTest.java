package com.flowdesk.bootstrap.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
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

/**
 * 知识文档 HTTP 接口集成测试。
 *
 * <p>存储根目录指向模块的 {@code target} 下，测试之间清空，因此既验证了 HTTP 契约，
 * 也验证了「文件真的落在配置的存储根目录里」。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:flowdesk_knowledge_http_it"
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "flowdesk.knowledge.storage.root=target/knowledge-http-it",
        "flowdesk.knowledge.upload.max-size=1MB"
})
@AutoConfigureMockMvc
class KnowledgeDocumentApiIntegrationTest {

    private static final String BASE_PATH = "/api/v1/knowledge/documents";

    private static final Path STORAGE_ROOT = Path.of("target", "knowledge-http-it");

    private static final byte[] TEXT_CONTENT = "知识库文档内容\n".getBytes(StandardCharsets.UTF_8);

    private static final byte[] PDF_CONTENT = "%PDF-1.7\nbody".getBytes(StandardCharsets.US_ASCII);

    private static final byte[] DOCX_CONTENT = { 0x50, 0x4B, 0x03, 0x04, 0x14, 0x00 };

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void clearState() throws IOException {
        this.jdbcClient.sql("DELETE FROM knowledge_documents").update();
        deleteRecursively(STORAGE_ROOT);
    }

    // ---------- ① 上传成功 ----------

    @Test
    void uploadReturns201WithLocationAndTheDocumentedFields() throws Exception {
        MvcResult result = upload("季度运维报告", textFile("notes.txt", TEXT_CONTENT))
                .andExpect(status().isCreated())
                .andExpect(header().exists(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.title").value("季度运维报告"))
                .andExpect(jsonPath("$.originalFilename").value("notes.txt"))
                .andExpect(jsonPath("$.format").value("TEXT"))
                .andExpect(jsonPath("$.mediaType").value("text/plain"))
                .andExpect(jsonPath("$.sizeBytes").value(TEXT_CONTENT.length))
                .andExpect(jsonPath("$.sha256").value(sha256Hex(TEXT_CONTENT)))
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andReturn();

        JsonNode body = parse(result);
        assertThat(body.path("createdAt").asText()).as("新上传时两个时间相等")
                .isEqualTo(body.path("updatedAt").asText());
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION))
                .isEqualTo(BASE_PATH + "/" + body.path("id").asText());
    }

    @Test
    void uploadWritesTheOriginalFileUnderTheConfiguredStorageRoot() throws Exception {
        JsonNode body = parse(upload("标题", textFile("notes.txt", TEXT_CONTENT))
                .andExpect(status().isCreated()).andReturn());

        Path documentsDirectory = STORAGE_ROOT.resolve("documents");
        assertThat(Files.isDirectory(documentsDirectory)).as("存储根目录下应产生内容目录").isTrue();
        try (Stream<Path> files = Files.list(documentsDirectory)) {
            var stored = files.toList();
            assertThat(stored).hasSize(1);
            assertThat(Files.readAllBytes(stored.get(0))).isEqualTo(TEXT_CONTENT);
            assertThat(stored.get(0).getFileName().toString())
                    .as("落盘对象只由服务端生成的内容键命名，与原始文件名无关")
                    .doesNotContain("notes.txt")
                    .startsWith("kdoc-");
        }
        assertThat(tempFiles()).as("成功后不留临时文件").isEmpty();
        assertThat(body.path("id").asText()).isNotEmpty();
    }

    @Test
    void acceptsEverySupportedFormatWithValidContent() throws Exception {
        upload("pdf", new MockMultipartFile("file", "report.pdf", "application/pdf", PDF_CONTENT))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.format").value("PDF"))
                .andExpect(jsonPath("$.mediaType").value("application/pdf"));

        upload("docx", new MockMultipartFile("file", "report.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", DOCX_CONTENT))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.format").value("DOCX"));

        upload("md", new MockMultipartFile("file", "notes.md", "text/markdown", TEXT_CONTENT))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.format").value("MARKDOWN"));

        // Content-Type 为空时按扩展名与内容识别
        upload("txt", new MockMultipartFile("file", "notes.txt", null, TEXT_CONTENT))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.format").value("TEXT"));

        // application/octet-stream 同样视为未声明
        upload("txt2", new MockMultipartFile("file", "notes.txt", "application/octet-stream", TEXT_CONTENT))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mediaType").value("text/plain"));
    }

    @Test
    void unrecognizedExtensionIsReportedAs415() throws Exception {
        upload("压缩包", new MockMultipartFile("file", "archive.zip", "application/zip", TEXT_CONTENT))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_DOCUMENT_TYPE"))
                .andExpect(jsonPath("$.type").value("urn:flowdesk:problem:unsupported-document-type"));
    }

    // ---------- ② 查询 ----------

    @Test
    void getReturnsTheSameMetadataAsTheUpload() throws Exception {
        JsonNode created = parse(upload("季度运维报告", textFile("notes.txt", TEXT_CONTENT))
                .andExpect(status().isCreated()).andReturn());

        MvcResult fetched = this.mockMvc.perform(get(BASE_PATH + "/{id}", created.path("id").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("季度运维报告"))
                .andExpect(jsonPath("$.sha256").value(sha256Hex(TEXT_CONTENT)))
                .andExpect(jsonPath("$.version").value(0))
                .andReturn();

        assertThat(parse(fetched)).isEqualTo(created);
    }

    @Test
    void getUnknownDocumentReturns404() throws Exception {
        this.mockMvc.perform(get(BASE_PATH + "/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_DOCUMENT_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void getMalformedDocumentIdReturns400() throws Exception {
        for (String malformed : new String[] { "1-1-1-1-1", "not-a-uuid", "11111111222233334444555555555555" }) {
            this.mockMvc.perform(get(BASE_PATH + "/{id}", malformed))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    // ---------- ③ 非法输入 ----------

    @Test
    void missingTitleOrFileReturns400() throws Exception {
        this.mockMvc.perform(multipart(BASE_PATH)
                        .file(textFile("notes.txt", TEXT_CONTENT)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        this.mockMvc.perform(multipart(BASE_PATH).param("title", "标题"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void blankTitleReturns400() throws Exception {
        upload("   ", textFile("notes.txt", TEXT_CONTENT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void emptyFileReturns400() throws Exception {
        upload("标题", new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void contentMismatchReturns415() throws Exception {
        // 扩展名说是 PDF，内容却不是：文件头校验必须拦住
        upload("假 PDF", new MockMultipartFile("file", "fake.pdf", "application/pdf", TEXT_CONTENT))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_DOCUMENT_TYPE"));

        // DOCX 必须是 ZIP 容器
        upload("假 DOCX", new MockMultipartFile("file", "fake.docx", null, TEXT_CONTENT))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_DOCUMENT_TYPE"));

        // 文本必须是合法 UTF-8
        upload("坏 UTF-8", new MockMultipartFile("file", "broken.txt", "text/plain",
                new byte[] { (byte) 0xC3, (byte) 0x28 }))
                .andExpect(status().isUnsupportedMediaType());

        // 文本不得包含 NUL
        upload("含 NUL", new MockMultipartFile("file", "nul.txt", "text/plain", new byte[] { 'a', 0, 'b' }))
                .andExpect(status().isUnsupportedMediaType());

        // 明确声明的类型与格式不一致
        upload("类型不符", new MockMultipartFile("file", "notes.txt", "application/pdf", TEXT_CONTENT))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void rejectedUploadsLeaveNothingBehind() throws Exception {
        upload("假 PDF", new MockMultipartFile("file", "fake.pdf", "application/pdf", TEXT_CONTENT))
                .andExpect(status().isUnsupportedMediaType());

        assertThat(countDocuments()).as("失败的上传不能留下元数据").isZero();
        assertThat(tempFiles()).as("失败的上传不能留下临时文件").isEmpty();
        assertThat(storedObjects()).as("失败的上传不能留下内容对象").isEmpty();
    }

    // ---------- ④ 文件名安全 ----------

    @Test
    void windowsStyleFileNameIsReducedToItsBaseName() throws Exception {
        upload("标题", new MockMultipartFile("file", "C:\\fakepath\\季度报告.txt", "text/plain",
                TEXT_CONTENT))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.originalFilename").value("季度报告.txt"));
    }

    @Test
    void pathTraversalFileNameCannotInfluenceStorage() throws Exception {
        JsonNode body = parse(upload("标题", new MockMultipartFile("file", "../../../etc/passwd.txt",
                "text/plain", TEXT_CONTENT))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.originalFilename").value("passwd.txt"))
                .andReturn());

        assertThat(body.path("id").asText()).isNotEmpty();
        // 存储根目录之外没有产生任何文件
        Path targetDirectory = STORAGE_ROOT.getParent();
        try (Stream<Path> files = Files.walk(targetDirectory)) {
            var outsideRoot = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().contains("passwd"))
                    .toList();
            assertThat(outsideRoot).isEmpty();
        }
    }

    @Test
    void controlCharactersInFileNameAreRejected() throws Exception {
        upload("标题", new MockMultipartFile("file", "bad\u0000name.txt", "text/plain", TEXT_CONTENT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // ---------- ⑤ 不泄漏内部信息 ----------

    @Test
    void responsesNeverContainContentKeyOrStoragePaths() throws Exception {
        MvcResult created = upload("季度运维报告", textFile("notes.txt", TEXT_CONTENT))
                .andExpect(status().isCreated()).andReturn();
        String createdBody = created.getResponse().getContentAsString(StandardCharsets.UTF_8);

        String documentId = parse(created).path("id").asText();
        MvcResult fetched = this.mockMvc.perform(get(BASE_PATH + "/{id}", documentId))
                .andExpect(status().isOk()).andReturn();
        String fetchedBody = fetched.getResponse().getContentAsString(StandardCharsets.UTF_8);

        for (String body : new String[] { createdBody, fetchedBody }) {
            assertThat(body)
                    .doesNotContain("contentKey")
                    .doesNotContain("content_key")
                    .doesNotContain("kdoc-")
                    .doesNotContain("target")
                    .doesNotContain("knowledge-http-it")
                    .doesNotContain(STORAGE_ROOT.toAbsolutePath().toString())
                    .doesNotContain("tmp");
        }
    }

    @Test
    void errorResponsesNeverEchoSubmittedValuesOrInternals() throws Exception {
        String sentinel = "sentinel-../../evil";
        MvcResult result = upload("标题", new MockMultipartFile("file", sentinel + ".zip", "application/zip",
                TEXT_CONTENT))
                .andExpect(status().isUnsupportedMediaType())
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body)
                .doesNotContain(sentinel)
                .doesNotContain("passwd")
                .doesNotContain("SELECT")
                .doesNotContain("knowledge_documents")
                .doesNotContain("Exception")
                .doesNotContain("java.");
    }

    @Test
    void unacceptableAcceptReturns406() throws Exception {
        this.mockMvc.perform(get(BASE_PATH + "/{id}", UUID.randomUUID()).accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"));
    }

    @Test
    void multipartBodyIsParsedEvenWhenAcceptIsWildcard() throws Exception {
        upload("标题", textFile("notes.txt", TEXT_CONTENT))
                .andExpect(status().isCreated());
    }

    // ---------- 辅助 ----------

    private org.springframework.test.web.servlet.ResultActions upload(String title,
            MockMultipartFile file) throws Exception {

        return this.mockMvc.perform(multipart(BASE_PATH).file(file).param("title", title));
    }

    private static MockMultipartFile textFile(String fileName, byte[] content) {
        return new MockMultipartFile("file", fileName, "text/plain", content);
    }

    private JsonNode parse(MvcResult result) throws Exception {
        return this.objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private long countDocuments() {
        Long count = this.jdbcClient.sql("SELECT COUNT(*) FROM knowledge_documents").query(Long.class).single();
        return count == null ? -1L : count;
    }

    private static java.util.List<Path> tempFiles() throws IOException {
        Path tempRoot = STORAGE_ROOT.resolve("tmp");
        if (!Files.isDirectory(tempRoot)) {
            return java.util.List.of();
        }
        try (Stream<Path> files = Files.list(tempRoot)) {
            return files.toList();
        }
    }

    private static java.util.List<Path> storedObjects() throws IOException {
        Path documentsDirectory = STORAGE_ROOT.resolve("documents");
        if (!Files.isDirectory(documentsDirectory)) {
            return java.util.List.of();
        }
        try (Stream<Path> files = Files.list(documentsDirectory)) {
            return files.toList();
        }
    }

    private static String sha256Hex(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
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
