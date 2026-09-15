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
import com.flowdesk.application.knowledge.index.DocumentIndexingException;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentEmbeddingStore;
import com.flowdesk.application.knowledge.port.out.KnowledgeEmbeddingPort;
import com.flowdesk.application.knowledge.port.out.VersionedKnowledgeDocument;
import com.flowdesk.domain.knowledge.EmbeddingDescriptor;
import com.flowdesk.domain.knowledge.KnowledgeDocument;
import com.flowdesk.domain.knowledge.KnowledgeDocumentChunkEmbedding;
import com.flowdesk.domain.knowledge.KnowledgeIndexFailureCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 索引接口的 HTTP 契约测试（FD-0010）。
 *
 * <p>这里把<b>向量生成</b>与<b>向量写入</b>两个出站端口替换为替身，但保留真实的
 * 用例服务、仓储、切片存储与文档状态机 —— 因此「领取 CAS → 分批读取真实切片 →
 * 分批调用模型 → 一次性完成」的整条链路是被真实执行过的。
 * 真正的 pgvector 原子写入由 Testcontainers 集成测试覆盖（无 Docker 时跳过）。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:flowdesk_knowledge_index_web_it"
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "flowdesk.knowledge.storage.root=target/knowledge-index-web-it",
        "flowdesk.knowledge.upload.max-size=1MB",
        "flowdesk.knowledge.embedding.enabled=true",
        "flowdesk.knowledge.embedding.batch-size=2",
        // FD-0010-R1：启用向量化就必须有 DashScope Key（启动期校验），这里给一个假 Key。
        // 真正的模型调用被下面的 StubEmbeddingPort 覆盖，因此不会发出任何网络请求。
        "spring.ai.dashscope.api-key=test-fake-key-not-a-real-secret",
        // 让测试用的校验 Bean 覆盖生产 Bean（否则生产校验会因 H2 直接拒绝启动）
        "spring.main.allow-bean-definition-overriding=true"
})
@AutoConfigureMockMvc
class KnowledgeIndexWebTests {

    private static final String BASE_PATH = "/api/v1/knowledge/documents";

    private static final Path STORAGE_ROOT = Path.of("target", "knowledge-index-web-it");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private StubEmbeddingPort embeddingPort;

    @Autowired
    private StubEmbeddingStore embeddingStore;

    @BeforeEach
    void clearState() throws IOException {
        this.jdbcClient.sql("DELETE FROM knowledge_document_chunks").update();
        this.jdbcClient.sql("DELETE FROM knowledge_documents").update();
        deleteRecursively(STORAGE_ROOT);
        this.embeddingPort.reset();
        this.embeddingStore.reset();
    }

    @Test
    void indexingAParsedDocumentReturns200WithETagAndTheDocumentedFields() throws Exception {
        String documentId = parsedDocument();

        this.mockMvc.perform(post(BASE_PATH + "/{id}/index", documentId)
                        .header(HttpHeaders.IF_MATCH, "\"2\""))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"4\""))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.documentId").value(documentId))
                .andExpect(jsonPath("$.title").value("季度运维报告"))
                .andExpect(jsonPath("$.status").value("INDEXED"))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.chunkCount").value(1))
                .andExpect(jsonPath("$.embeddingProvider").value("dashscope"))
                .andExpect(jsonPath("$.embeddingModel").value("text-embedding-v4"))
                .andExpect(jsonPath("$.embeddingDimensions").value(1024))
                .andExpect(jsonPath("$.indexedAt").isNotEmpty());

        // 真实链路：领取 +1、完成 +1；模型与写入各调用一次
        assertThat(versionOf(documentId)).as("领取 CAS 已经真实落库（2 → 3）").isEqualTo(3L);
        assertThat(statusOf(documentId)).isEqualTo("INDEXING");
        assertThat(this.embeddingPort.batches()).hasSize(1);
        assertThat(this.embeddingStore.calls()).isEqualTo(1);
    }

    @Test
    void theResponseNeverContainsVectorsChunkTextOrStorageCoordinates() throws Exception {
        String documentId = parsedDocument();

        MvcResult result = this.mockMvc.perform(post(BASE_PATH + "/{id}/index", documentId)
                        .header(HttpHeaders.IF_MATCH, "\"2\""))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body)
                .doesNotContain("vector")
                .doesNotContain("embedding\"")
                .doesNotContain("[1.0,")
                .doesNotContain("chunk-")
                .doesNotContain("contentKey")
                .doesNotContain("kdoc-")
                .doesNotContain("target")
                .doesNotContain("api-key")
                .doesNotContain("DASHSCOPE");
    }

    @Test
    void metadataShowsTheIndexingStateAndOmitsFieldsThatDoNotApplyYet() throws Exception {
        String documentId = parsedDocument();
        this.mockMvc.perform(post(BASE_PATH + "/{id}/index", documentId)
                        .header(HttpHeaders.IF_MATCH, "\"2\""))
                .andExpect(status().isOk());

        // 替身写入不落库，因此文档仍停在 INDEXING：这正是 NON_NULL 行为的观察点
        this.mockMvc.perform(get(BASE_PATH + "/{id}", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INDEXING"))
                .andExpect(jsonPath("$.parsedAt").isNotEmpty())
                .andExpect(jsonPath("$.embeddingProvider").value("dashscope"))
                .andExpect(jsonPath("$.embeddingModel").value("text-embedding-v4"))
                .andExpect(jsonPath("$.embeddingDimensions").value(1024))
                .andExpect(jsonPath("$.indexedAt").doesNotExist());
    }

    @Test
    void staleIfMatchIsRejectedWith412AndNoSideEffects() throws Exception {
        String documentId = parsedDocument();

        this.mockMvc.perform(post(BASE_PATH + "/{id}/index", documentId)
                        .header(HttpHeaders.IF_MATCH, "\"1\""))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_DOCUMENT_VERSION_CONFLICT"));

        assertThat(versionOf(documentId)).isEqualTo(2L);
        assertThat(this.embeddingPort.batches()).isEmpty();
    }

    @Test
    void aDocumentThatIsNotParsedReturns409() throws Exception {
        String documentId = uploadText("季度运维报告");

        this.mockMvc.perform(post(BASE_PATH + "/{id}/index", documentId)
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_DOCUMENT_NOT_INDEXABLE"))
                .andExpect(jsonPath("$.type").value("urn:flowdesk:problem:knowledge-document-not-indexable"));

        assertThat(this.embeddingPort.batches()).isEmpty();
    }

    @Test
    void unknownDocumentReturns404AndMalformedIdReturns400() throws Exception {
        this.mockMvc.perform(post(BASE_PATH + "/{id}/index", java.util.UUID.randomUUID())
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_DOCUMENT_NOT_FOUND"));

        this.mockMvc.perform(post(BASE_PATH + "/{id}/index", "1-1-1-1-1")
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void aProviderFailureReturns502WithTheStableFailureCode() throws Exception {
        String documentId = parsedDocument();
        this.embeddingPort.failWith(new DocumentIndexingException(
                KnowledgeIndexFailureCode.EMBEDDING_PROVIDER_FAILURE, "上游超时 sentinel-http-429"));

        MvcResult result = this.mockMvc.perform(post(BASE_PATH + "/{id}/index", documentId)
                        .header(HttpHeaders.IF_MATCH, "\"2\""))
                .andExpect(status().isBadGateway())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("EMBEDDING_PROVIDER_ERROR"))
                .andExpect(jsonPath("$.failureCode").value("EMBEDDING_PROVIDER_FAILURE"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain("sentinel")
                .doesNotContain("429")
                .doesNotContain("Timeout");

        // 失败补偿：文档必须落到可重试的 INDEX_FAILED
        assertThat(statusOf(documentId)).isEqualTo("INDEX_FAILED");
        assertThat(indexFailureCodeOf(documentId)).isEqualTo("EMBEDDING_PROVIDER_FAILURE");
        assertThat(this.embeddingStore.calls()).isZero();
    }

    @Test
    void anInvalidModelResponseReturns500AndLeavesTheDocumentRetryable() throws Exception {
        String documentId = parsedDocument();
        this.embeddingPort.failWith(new DocumentIndexingException(
                KnowledgeIndexFailureCode.INVALID_EMBEDDING_RESPONSE, "维度不符"));

        this.mockMvc.perform(post(BASE_PATH + "/{id}/index", documentId)
                        .header(HttpHeaders.IF_MATCH, "\"2\""))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.failureCode").value("INVALID_EMBEDDING_RESPONSE"));

        assertThat(statusOf(documentId)).isEqualTo("INDEX_FAILED");
        assertThat(this.embeddingStore.calls()).isZero();
    }

    @Test
    void aStorageFailureReturns500WithTheVectorStorageFailureCode() throws Exception {
        String documentId = parsedDocument();
        // 与真实适配器一致：向量写入失败带稳定失败码，数据库异常只作为 cause
        this.embeddingStore.failWith(new DocumentIndexingException(
                KnowledgeIndexFailureCode.VECTOR_STORAGE_FAILURE, "向量写入失败 sentinel-server-message",
                new java.sql.SQLException("INSERT INTO knowledge_document_chunk_embeddings "
                        + "VALUES (... sentinel-sql ...) jdbc:postgresql://user:sentinel-password@host:5432/db")));

        MvcResult result = this.mockMvc.perform(post(BASE_PATH + "/{id}/index", documentId)
                        .header(HttpHeaders.IF_MATCH, "\"2\""))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.failureCode").value("VECTOR_STORAGE_FAILURE"))
                .andReturn();

        // 响应必须是固定安全文案：SQL、连接串、驱动信息与异常类名都不得出现
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain("sentinel")
                .doesNotContain("INSERT")
                .doesNotContain("jdbc:")
                .doesNotContain("postgresql")
                .doesNotContain("SQLException");

        assertThat(statusOf(documentId)).isEqualTo("INDEX_FAILED");
        assertThat(indexFailureCodeOf(documentId)).isEqualTo("VECTOR_STORAGE_FAILURE");
    }

    @Test
    void anUnexpectedStorageExceptionStillCarriesTheVectorStorageFailureCode() throws Exception {
        // FD-0010 的缺口：完成阶段的非预期异常曾被包装成 METADATA_STORAGE_FAILURE，
        // 于是响应是一个没有 failureCode 的 500。现在必须带失败码。
        String documentId = parsedDocument();
        this.embeddingStore.failWith(new IllegalStateException("pgvector 写入失败 sentinel-raw"));

        MvcResult result = this.mockMvc.perform(post(BASE_PATH + "/{id}/index", documentId)
                        .header(HttpHeaders.IF_MATCH, "\"2\""))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.failureCode").value("VECTOR_STORAGE_FAILURE"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain("sentinel");

        assertThat(statusOf(documentId)).as("补偿后必须可重试").isEqualTo("INDEX_FAILED");
        assertThat(indexFailureCodeOf(documentId)).isEqualTo("VECTOR_STORAGE_FAILURE");
    }

    @Test
    void aChunkDataFailureReturns500WithTheChunkDataInvalidFailureCode() throws Exception {
        String documentId = parsedDocument();
        this.embeddingStore.failWith(new DocumentIndexingException(
                KnowledgeIndexFailureCode.CHUNK_DATA_INVALID, "切片摘要不一致 sentinel-digest"));

        MvcResult result = this.mockMvc.perform(post(BASE_PATH + "/{id}/index", documentId)
                        .header(HttpHeaders.IF_MATCH, "\"2\""))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.failureCode").value("CHUNK_DATA_INVALID"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain("sentinel");

        assertThat(statusOf(documentId)).isEqualTo("INDEX_FAILED");
        assertThat(indexFailureCodeOf(documentId)).isEqualTo("CHUNK_DATA_INVALID");
    }

    @Test
    void aVersionConflictFromTheCompletionPortStays412WithoutMarkingTheDocumentFailed() throws Exception {
        String documentId = parsedDocument();
        this.embeddingStore.failWith(new com.flowdesk.application.knowledge.KnowledgeApplicationException(
                com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode
                        .KNOWLEDGE_DOCUMENT_VERSION_CONFLICT,
                "文档版本不匹配"));

        this.mockMvc.perform(post(BASE_PATH + "/{id}/index", documentId)
                        .header(HttpHeaders.IF_MATCH, "\"2\""))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_DOCUMENT_VERSION_CONFLICT"));

        // 版本冲突不得给别人盖失败戳
        assertThat(statusOf(documentId)).as("不得被补偿成 INDEX_FAILED").isEqualTo("INDEXING");
        assertThat(indexFailureCodeOf(documentId)).isNull();
    }

    @Test
    void aFailedDocumentCanBeRetriedOverHttp() throws Exception {
        String documentId = parsedDocument();
        this.embeddingPort.failOnce(new DocumentIndexingException(
                KnowledgeIndexFailureCode.EMBEDDING_PROVIDER_FAILURE, "上游 5xx"));

        this.mockMvc.perform(post(BASE_PATH + "/{id}/index", documentId)
                        .header(HttpHeaders.IF_MATCH, "\"2\""))
                .andExpect(status().isBadGateway());
        assertThat(statusOf(documentId)).isEqualTo("INDEX_FAILED");

        // INDEX_FAILED 的版本是 4（领取 +1、补偿 +1）
        this.mockMvc.perform(post(BASE_PATH + "/{id}/index", documentId)
                        .header(HttpHeaders.IF_MATCH, "\"4\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INDEXED"));
    }

    // ---------- 辅助 ----------

    private String uploadText(String title) throws Exception {
        MvcResult result = this.mockMvc.perform(multipart(BASE_PATH)
                        .file(new MockMultipartFile("file", "notes.txt", "text/plain",
                                "知识库文档内容\n".getBytes(StandardCharsets.UTF_8)))
                        .param("title", title))
                .andExpect(status().isCreated())
                .andReturn();
        return body(result).path("id").asText();
    }

    /** 上传 + 真实解析：返回处于 PARSED（版本 2）的文档标识。 */
    private String parsedDocument() throws Exception {
        String documentId = uploadText("季度运维报告");
        this.mockMvc.perform(post(BASE_PATH + "/{id}/parse", documentId)
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isOk());
        return documentId;
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

    private String indexFailureCodeOf(String documentId) {
        return this.jdbcClient.sql("SELECT index_failure_code FROM knowledge_documents WHERE id = ?")
                .param(1, documentId).query(String.class).optional().orElse(null);
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

    /**
     * 用替身替换两个出站端口，并放行「启用向量化」的启动期校验（测试环境是 H2）。
     *
     * <p>真实 pgvector 写入无法在 H2 上执行（也没有 pgvector 类型），因此这里只验证
     * HTTP 契约与应用层链路；<b>真实原子写入由 PostgreSQL Testcontainers 集成测试覆盖</b>。</p>
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class StubPortsConfiguration {

        @Bean
        @Primary
        Boolean knowledgeEmbeddingConsistency() {
            // 覆盖生产校验：本测试用 H2 且只测 HTTP 契约
            return Boolean.TRUE;
        }

        @Bean
        @Primary
        org.springframework.ai.embedding.EmbeddingModel stubEmbeddingModel() {
            // 生产装配在「已启用向量化」时会构造真实适配器，因此这里必须提供一个 EmbeddingModel；
            // 但真正被用例调用的是下面被 @Primary 覆盖的 StubEmbeddingPort
            return new org.springframework.ai.embedding.EmbeddingModel() {

                @Override
                public org.springframework.ai.embedding.EmbeddingResponse call(
                        org.springframework.ai.embedding.EmbeddingRequest request) {
                    return new org.springframework.ai.embedding.EmbeddingResponse(java.util.List.of());
                }

                @Override
                public float[] embed(org.springframework.ai.document.Document document) {
                    return new float[EmbeddingDescriptor.REQUIRED_DIMENSIONS];
                }
            };
        }

        @Bean
        @Primary
        StubEmbeddingPort stubEmbeddingPort() {
            return new StubEmbeddingPort();
        }

        @Bean
        @Primary
        StubEmbeddingStore stubEmbeddingStore() {
            return new StubEmbeddingStore();
        }
    }

    /**
     * 向量生成替身：记录批次，可注入失败。
     */
    static final class StubEmbeddingPort implements KnowledgeEmbeddingPort {

        private final List<List<String>> batches = new java.util.ArrayList<>();

        private RuntimeException stickyFailure;

        private RuntimeException onceFailure;

        @Override
        public List<float[]> embedAll(List<String> texts, EmbeddingDescriptor descriptor) {
            this.batches.add(List.copyOf(texts));
            if (this.stickyFailure != null) {
                throw this.stickyFailure;
            }
            if (this.onceFailure != null) {
                RuntimeException failure = this.onceFailure;
                this.onceFailure = null;
                throw failure;
            }
            List<float[]> vectors = new java.util.ArrayList<>(texts.size());
            for (int index = 0; index < texts.size(); index++) {
                float[] vector = new float[EmbeddingDescriptor.REQUIRED_DIMENSIONS];
                Arrays.fill(vector, 0.5f);
                vectors.add(vector);
            }
            return List.copyOf(vectors);
        }

        void failWith(RuntimeException failure) {
            this.stickyFailure = failure;
        }

        void failOnce(RuntimeException failure) {
            this.onceFailure = failure;
        }

        void reset() {
            this.batches.clear();
            this.stickyFailure = null;
            this.onceFailure = null;
        }

        List<List<String>> batches() {
            return List.copyOf(this.batches);
        }
    }

    /**
     * 向量写入替身：记录调用，可注入失败；成功时不写库（H2 没有 vector 类型）。
     */
    static final class StubEmbeddingStore implements KnowledgeDocumentEmbeddingStore {

        private final AtomicBoolean called = new AtomicBoolean();

        private int calls;

        private RuntimeException failure;

        @Override
        public VersionedKnowledgeDocument completeIndexing(KnowledgeDocument indexedDocument,
                long expectedVersion, List<KnowledgeDocumentChunkEmbedding> embeddings) {

            this.called.set(true);
            this.calls++;
            if (this.failure != null) {
                throw this.failure;
            }
            return new VersionedKnowledgeDocument(indexedDocument, expectedVersion + 1L);
        }

        void failWith(RuntimeException failure) {
            this.failure = failure;
        }

        void reset() {
            this.called.set(false);
            this.calls = 0;
            this.failure = null;
        }

        int calls() {
            return this.calls;
        }
    }
}
