package com.flowdesk.bootstrap.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * 上传大小限制的<b>真实容器</b>验证。
 *
 * <p>MockMvc 走的是模拟 multipart，不经过 Servlet 容器的大小校验，因此这里用真实端口 +
 * 真实 multipart 请求，把两条限制路径都跑通：</p>
 * <ol>
 *   <li><b>容器侧</b>：{@code spring.servlet.multipart.max-file-size} 在进入 Controller
 *       <b>之前</b>就拒绝（Spring 抛出的 {@code MaxUploadSizeExceededException} 必须被映射为 413，
 *       而不是掉进 500 兜底）；</li>
 *   <li><b>应用侧</b>：{@code flowdesk.knowledge.upload.max-size} 按<b>实际读取到的字节数</b>判断 ——
 *       它能拦住「容器放行、但内容确实过大」的请求，这正是不能只信 Content-Length 的原因。</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:flowdesk_knowledge_limit_it"
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "flowdesk.knowledge.storage.root=target/knowledge-limit-it",
        "flowdesk.knowledge.upload.max-size=1KB",
        "spring.servlet.multipart.max-file-size=4KB",
        "spring.servlet.multipart.max-request-size=8KB"
})
class KnowledgeUploadLimitWebTests {

    private static final Path STORAGE_ROOT = Path.of("target", "knowledge-limit-it");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void clearState() throws IOException {
        this.jdbcClient.sql("DELETE FROM knowledge_documents").update();
        deleteRecursively(STORAGE_ROOT);
    }

    @Test
    void smallUploadSucceedsAndIsReadable() {
        ResponseEntity<String> created = upload("小文件", "small.txt", repeat("a", 512));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getFirst(HttpHeaders.LOCATION)).isNotBlank();
        assertThat(created.getBody()).contains("\"format\":\"TEXT\"").doesNotContain("contentKey");

        ResponseEntity<String> fetched = this.restTemplate.getForEntity(
                created.getHeaders().getFirst(HttpHeaders.LOCATION), String.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).contains("\"sizeBytes\":512");
    }

    @Test
    void applicationLimitRejectsContentThatTheContainerAccepted() {
        // 2KB：容器上限 4KB 会放行，但超过应用上限 1KB，必须由「实际读取字节数」拦住
        ResponseEntity<String> response = upload("略大", "medium.txt", repeat("b", 2048));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getBody())
                .contains("\"code\":\"DOCUMENT_TOO_LARGE\"")
                .contains("\"status\":413");
        assertThat(countDocuments()).as("被拒绝的上传不能留下元数据").isZero();
    }

    @Test
    void servletLimitIsMappedTo413BeforeReachingTheController() {
        // 6KB：超过容器上限 4KB，Spring 在进入 Controller 之前就会抛异常
        ResponseEntity<String> response = upload("超大", "large.txt", repeat("c", 6144));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody())
                .as("必须映射为统一的 413 契约，而不是 500 兜底")
                .contains("\"code\":\"DOCUMENT_TOO_LARGE\"")
                .contains("\"type\":\"urn:flowdesk:problem:document-too-large\"");
        assertThat(countDocuments()).isZero();
    }

    @Test
    void rejectedUploadsLeaveNoFilesBehind() throws IOException {
        upload("超大", "large.txt", repeat("c", 6144));
        upload("略大", "medium.txt", repeat("b", 2048));

        assertThat(countDocuments()).isZero();
        Path tempRoot = STORAGE_ROOT.resolve("tmp");
        if (Files.isDirectory(tempRoot)) {
            try (Stream<Path> files = Files.list(tempRoot)) {
                assertThat(files.toList()).as("被拒绝的上传必须清理临时文件").isEmpty();
            }
        }
        Path documentsRoot = STORAGE_ROOT.resolve("documents");
        if (Files.isDirectory(documentsRoot)) {
            try (Stream<Path> files = Files.list(documentsRoot)) {
                assertThat(files.toList()).isEmpty();
            }
        }
    }

    // ---------- 辅助 ----------

    private ResponseEntity<String> upload(String title, String fileName, String content) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("title", title);
        body.add("file", new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {

            @Override
            public String getFilename() {
                return fileName;
            }

            @Override
            public long contentLength() {
                return content.getBytes(StandardCharsets.UTF_8).length;
            }
        });

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return this.restTemplate.exchange("/api/v1/knowledge/documents", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    private static String repeat(String value, int times) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int index = 0; index < times; index++) {
            out.writeBytes(value.getBytes(StandardCharsets.UTF_8));
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private long countDocuments() {
        Long count = this.jdbcClient.sql("SELECT COUNT(*) FROM knowledge_documents").query(Long.class).single();
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
