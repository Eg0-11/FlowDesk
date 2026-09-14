package com.flowdesk.bootstrap.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.application.knowledge.command.ParseKnowledgeDocumentCommand;
import com.flowdesk.application.knowledge.parse.DocumentChunkingException;
import com.flowdesk.application.knowledge.parse.DocumentParsingException;
import com.flowdesk.application.knowledge.port.in.ParseKnowledgeDocumentUseCase;
import com.flowdesk.application.knowledge.view.ParsedDocumentView;
import com.flowdesk.domain.knowledge.KnowledgeDocumentStatus;
import com.flowdesk.domain.knowledge.KnowledgeParseFailureCode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
 * 解析失败码到 HTTP 的映射测试（FD-0009）。
 *
 * <p>解析失败码一共有七个，而真实文档很难把每一种都构造出来（例如加密的 DOCX）。
 * 这里把用例端口替换成一个可控替身，逐个失败码断言 HTTP 状态码、稳定错误码与
 * {@code failureCode} 扩展字段 —— 端到端链路本身由
 * {@link KnowledgeParseApiIntegrationTest} 用真实文件覆盖。</p>
 *
 * <p>替身用 {@code @Primary} 注入，不用 Mockito（项目不引入该依赖）。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:flowdesk_knowledge_parse_failure_web"
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "flowdesk.knowledge.storage.root=target/knowledge-parse-failure-web"
})
@AutoConfigureMockMvc
class KnowledgeParseFailureWebTests {

    private static final String BASE_PATH = "/api/v1/knowledge/documents";

    private static final String DOCUMENT_ID = UUID.randomUUID().toString();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StubParseUseCase stub;

    @BeforeEach
    void resetStub() {
        this.stub.outcome = null;
    }

    // ---------- 解析失败码 → 422 / 413 / 500 ----------

    @Test
    void documentsThatCannotBeParsedReturn422WithTheStableFailureCode() {
        assertProblem(KnowledgeParseFailureCode.CORRUPTED_DOCUMENT, 422, "DOCUMENT_PARSE_FAILED",
                "urn:flowdesk:problem:document-parse-failed");
        assertProblem(KnowledgeParseFailureCode.ENCRYPTED_DOCUMENT, 422, "DOCUMENT_PARSE_FAILED",
                "urn:flowdesk:problem:document-parse-failed");
        assertProblem(KnowledgeParseFailureCode.UNSUPPORTED_DOCUMENT_CONTENT, 422, "DOCUMENT_PARSE_FAILED",
                "urn:flowdesk:problem:document-parse-failed");
        assertProblem(KnowledgeParseFailureCode.EMPTY_EXTRACTED_TEXT, 422, "DOCUMENT_PARSE_FAILED",
                "urn:flowdesk:problem:document-parse-failed");
    }

    @Test
    void oversizedExtractionReturns413WithItsOwnCode() {
        assertProblem(KnowledgeParseFailureCode.EXTRACTED_TEXT_TOO_LARGE, 413, "DOCUMENT_TOO_LARGE",
                "urn:flowdesk:problem:document-too-large");
    }

    @Test
    void tooManyChunksReturns413WithItsOwnCode() {
        assertProblem(KnowledgeParseFailureCode.TOO_MANY_CHUNKS, 413, "DOCUMENT_TOO_MANY_CHUNKS",
                "urn:flowdesk:problem:document-too-many-chunks");
    }

    @Test
    void internalParserFailuresReturn500WithoutLeakingTheFailureCode() {
        this.stub.outcome = () -> {
            throw new DocumentParsingException(KnowledgeParseFailureCode.PARSER_FAILURE,
                    "sentinel-C:/secret/report.pdf");
        };

        Result result = perform();

        assertThat(result.status()).isEqualTo(500);
        assertThat(result.body())
                .contains("\"code\":\"INTERNAL_SERVER_ERROR\"")
                .contains("\"failureCode\":\"PARSER_FAILURE\"")
                .doesNotContain("sentinel")
                .doesNotContain("secret")
                .doesNotContain("Exception")
                .doesNotContain("java.");
    }

    @Test
    void chunkingFailuresAreMappedLikeParsingFailures() {
        this.stub.outcome = () -> {
            throw new DocumentChunkingException(KnowledgeParseFailureCode.TOO_MANY_CHUNKS, "切片过多");
        };

        Result result = perform();

        assertThat(result.status()).isEqualTo(413);
        assertThat(result.body())
                .contains("\"code\":\"DOCUMENT_TOO_MANY_CHUNKS\"")
                .contains("\"failureCode\":\"TOO_MANY_CHUNKS\"");
    }

    // ---------- 应用层错误码 → 400 / 404 / 409 / 412 / 500 ----------

    @Test
    void applicationErrorCodesKeepTheirOwnHttpSemantics() {
        assertApplicationProblem(KnowledgeApplicationErrorCode.INVALID_PARSE_COMMAND, 400, "INVALID_REQUEST");
        assertApplicationProblem(KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_NOT_FOUND, 404,
                "KNOWLEDGE_DOCUMENT_NOT_FOUND");
        assertApplicationProblem(KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_NOT_PARSABLE, 409,
                "KNOWLEDGE_DOCUMENT_NOT_PARSABLE");
        assertApplicationProblem(KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_VERSION_CONFLICT, 412,
                "KNOWLEDGE_DOCUMENT_VERSION_CONFLICT");
        assertApplicationProblem(KnowledgeApplicationErrorCode.DOCUMENT_CONTENT_UNREADABLE, 500,
                "INTERNAL_SERVER_ERROR");
        assertApplicationProblem(KnowledgeApplicationErrorCode.METADATA_STORAGE_FAILURE, 500,
                "INTERNAL_SERVER_ERROR");
    }

    @Test
    void everyProblemResponseCarriesTheSharedProblemFieldsAndNeverEchoesInternals() {
        this.stub.outcome = () -> {
            throw new KnowledgeApplicationException(
                    KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_VERSION_CONFLICT,
                    "sentinel-internal-message");
        };

        Result result = perform();

        assertThat(result.status()).isEqualTo(412);
        assertThat(result.contentType()).contains(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(result.body())
                .contains("\"type\":\"urn:flowdesk:problem:knowledge-document-version-conflict\"")
                .contains("\"title\"")
                .contains("\"status\":412")
                .contains("\"detail\"")
                .contains("\"instance\":\"/api/v1/knowledge/documents/" + DOCUMENT_ID + "/parse\"")
                .contains("\"code\"")
                .doesNotContain("sentinel-internal-message");
    }

    // ---------- 辅助 ----------

    private void assertProblem(KnowledgeParseFailureCode failureCode, int expectedStatus, String expectedCode,
            String expectedType) {

        this.stub.outcome = () -> {
            throw new DocumentParsingException(failureCode, "服务端诊断信息");
        };

        Result result = perform();

        assertThat(result.status()).as(failureCode.name()).isEqualTo(expectedStatus);
        assertThat(result.contentType()).contains(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(result.body())
                .contains("\"code\":\"" + expectedCode + "\"")
                .contains("\"type\":\"" + expectedType + "\"")
                .contains("\"failureCode\":\"" + failureCode.name() + "\"")
                .doesNotContain("服务端诊断信息");
    }

    private void assertApplicationProblem(KnowledgeApplicationErrorCode errorCode, int expectedStatus,
            String expectedCode) {

        this.stub.outcome = () -> {
            throw new KnowledgeApplicationException(errorCode, "服务端诊断信息");
        };

        Result result = perform();

        assertThat(result.status()).as(errorCode.name()).isEqualTo(expectedStatus);
        assertThat(result.body())
                .contains("\"code\":\"" + expectedCode + "\"")
                .doesNotContain("服务端诊断信息");
    }

    private Result perform() {
        try {
            MvcResult result = this.mockMvc.perform(post(BASE_PATH + "/{id}/parse", DOCUMENT_ID)
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
     * 可替换的解析用例替身。
     */
    static final class StubParseUseCase implements ParseKnowledgeDocumentUseCase {

        private volatile Runnable outcome;

        @Override
        public ParsedDocumentView parse(ParseKnowledgeDocumentCommand command) {
            Runnable current = this.outcome;
            if (current != null) {
                current.run();
            }
            return new ParsedDocumentView(command.documentId().value(), "季度运维报告",
                    KnowledgeDocumentStatus.PARSED, command.expectedVersion() + 2L, 1L, Instant.now(), null);
        }
    }

    /**
     * 用 {@code @Primary} 覆盖真实的解析服务（其余装配保持生产一致）。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class StubConfiguration {

        @Bean
        @Primary
        StubParseUseCase stubParseUseCase() {
            return new StubParseUseCase();
        }
    }
}
