package com.flowdesk.bootstrap.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.application.knowledge.command.IndexKnowledgeDocumentCommand;
import com.flowdesk.application.knowledge.index.DocumentIndexingException;
import com.flowdesk.application.knowledge.port.in.IndexKnowledgeDocumentUseCase;
import com.flowdesk.domain.knowledge.KnowledgeIndexFailureCode;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 索引失败的 HTTP 映射测试（FD-0010）。
 *
 * <p>用可控的用例替身逐个失败码断言状态码、稳定错误码与 {@code failureCode} 扩展字段；
 * 端到端链路本身由 {@link KnowledgeIndexWebTests} 与默认 profile 的
 * {@link KnowledgeIndexDisabledWebTests} 覆盖。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:flowdesk_knowledge_index_failure_web"
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "flowdesk.knowledge.storage.root=target/knowledge-index-failure-web"
})
@AutoConfigureMockMvc
class KnowledgeIndexFailureWebTests {

    private static final String BASE_PATH = "/api/v1/knowledge/documents";

    private static final String DOCUMENT_ID = UUID.randomUUID().toString();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StubIndexUseCase stub;

    @BeforeEach
    void resetStub() {
        this.stub.outcome = null;
        this.stub.calls = 0;
    }

    @Test
    void applicationErrorCodesKeepTheirOwnHttpSemantics() {
        assertProblem(KnowledgeApplicationErrorCode.INVALID_INDEX_COMMAND, 400, "INVALID_REQUEST", null);
        assertProblem(KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_NOT_FOUND, 404,
                "KNOWLEDGE_DOCUMENT_NOT_FOUND", null);
        assertProblem(KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_NOT_INDEXABLE, 409,
                "KNOWLEDGE_DOCUMENT_NOT_INDEXABLE", null);
        assertProblem(KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_VERSION_CONFLICT, 412,
                "KNOWLEDGE_DOCUMENT_VERSION_CONFLICT", null);
        assertProblem(KnowledgeApplicationErrorCode.KNOWLEDGE_EMBEDDING_DISABLED, 503,
                "KNOWLEDGE_EMBEDDING_DISABLED", null);
        assertProblem(KnowledgeApplicationErrorCode.EMBEDDING_PROVIDER_ERROR, 502,
                "EMBEDDING_PROVIDER_ERROR", null);
        assertProblem(KnowledgeApplicationErrorCode.METADATA_STORAGE_FAILURE, 500,
                "INTERNAL_SERVER_ERROR", null);
    }

    @Test
    void indexingFailureCodesAreMappedTo502Or500WithTheFailureCodeReturned() {
        assertIndexingProblem(KnowledgeIndexFailureCode.EMBEDDING_PROVIDER_FAILURE, 502,
                "EMBEDDING_PROVIDER_ERROR");
        assertIndexingProblem(KnowledgeIndexFailureCode.INVALID_EMBEDDING_RESPONSE, 500,
                "INTERNAL_SERVER_ERROR");
        assertIndexingProblem(KnowledgeIndexFailureCode.VECTOR_STORAGE_FAILURE, 500,
                "INTERNAL_SERVER_ERROR");
        assertIndexingProblem(KnowledgeIndexFailureCode.CHUNK_DATA_INVALID, 500, "INTERNAL_SERVER_ERROR");
    }

    @Test
    void errorDetailIsAFixedMessageAndNeverEchoesInternals() {
        this.stub.outcome = () -> {
            throw new DocumentIndexingException(KnowledgeIndexFailureCode.EMBEDDING_PROVIDER_FAILURE,
                    "sentinel https://dashscope.aliyuncs.com/api/v1 401 key=sk-secret");
        };

        Result result = perform();

        assertThat(result.status()).isEqualTo(502);
        assertThat(result.contentType()).contains(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(result.body())
                .contains("\"type\":\"urn:flowdesk:problem:embedding-provider-error\"")
                .contains("\"title\"")
                .contains("\"detail\"")
                .contains("\"instance\":\"/api/v1/knowledge/documents/" + DOCUMENT_ID + "/index\"")
                .doesNotContain("sentinel")
                .doesNotContain("dashscope.aliyuncs.com")
                .doesNotContain("sk-secret")
                .doesNotContain("Exception")
                .doesNotContain("java.");
    }

    @Test
    void missingOrMalformedIfMatchIsRejectedBeforeTheUseCase() throws Exception {
        this.mockMvc.perform(post(BASE_PATH + "/{id}/index", DOCUMENT_ID))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isPreconditionRequired());
        assertThat(this.stub.calls).as("缺少前置条件时不得进入用例").isZero();

        this.mockMvc.perform(post(BASE_PATH + "/{id}/index", DOCUMENT_ID)
                        .header(HttpHeaders.IF_MATCH, "W/\"0\""))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isBadRequest());
        assertThat(this.stub.calls).isZero();
    }

    // ---------- 辅助 ----------

    private void assertProblem(KnowledgeApplicationErrorCode errorCode, int expectedStatus,
            String expectedCode, String ignored) {

        this.stub.outcome = () -> {
            throw new KnowledgeApplicationException(errorCode, "sentinel-internal-message");
        };

        Result result = perform();

        assertThat(result.status()).as(errorCode.name()).isEqualTo(expectedStatus);
        assertThat(result.contentType()).contains(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(result.body())
                .contains("\"code\":\"" + expectedCode + "\"")
                .doesNotContain("sentinel-internal-message");
    }

    private void assertIndexingProblem(KnowledgeIndexFailureCode failureCode, int expectedStatus,
            String expectedCode) {

        this.stub.outcome = () -> {
            throw new DocumentIndexingException(failureCode, "服务端诊断信息");
        };

        Result result = perform();

        assertThat(result.status()).as(failureCode.name()).isEqualTo(expectedStatus);
        assertThat(result.body())
                .contains("\"code\":\"" + expectedCode + "\"")
                .contains("\"failureCode\":\"" + failureCode.name() + "\"")
                .doesNotContain("服务端诊断信息");
    }

    private Result perform() {
        try {
            MvcResult result = this.mockMvc.perform(post(BASE_PATH + "/{id}/index", DOCUMENT_ID)
                            .header(HttpHeaders.IF_MATCH, "\"0\""))
                    .andReturn();
            return new Result(result.getResponse().getStatus(),
                    result.getResponse().getContentAsString(StandardCharsets.UTF_8),
                    result.getResponse().getContentType());
        }
        catch (Exception ex) {
            throw new IllegalStateException("请求执行失败", ex);
        }
    }

    /**
     * @param status      HTTP 状态码
     * @param body        响应体
     * @param contentType 响应内容类型
     */
    private record Result(int status, String body, String contentType) {
    }

    /**
     * 可替换的索引用例替身。
     */
    static final class StubIndexUseCase implements IndexKnowledgeDocumentUseCase {

        private volatile Runnable outcome;

        private int calls;

        @Override
        public com.flowdesk.application.knowledge.view.IndexedDocumentView index(
                IndexKnowledgeDocumentCommand command) {

            this.calls++;
            Runnable current = this.outcome;
            if (current != null) {
                current.run();
            }
            throw new AssertionError("替身必须由 outcome 决定结果");
        }
    }

    /**
     * 用 {@code @Primary} 覆盖真实的索引用例（其余装配保持生产一致）。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class StubConfiguration {

        @Bean
        @Primary
        StubIndexUseCase stubIndexUseCase() {
            return new StubIndexUseCase();
        }
    }
}
