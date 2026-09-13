package com.flowdesk.bootstrap.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 四类框架错误的统一响应测试，以及「兜底不截获既有映射」的回归测试。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:flowdesk_http_it"
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@AutoConfigureMockMvc
class TicketFrameworkErrorTests {

    private static final String BASE_PATH = "/api/v1/tickets";

    private static final String SENTINEL = "SENTINEL-INTERNAL-DETAIL-XYZ";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    /**
     * 仅在本次测试上下文中注册一个必定抛异常的端点，用来验证 500 兜底。
     */
    @TestConfiguration
    static class FailingEndpointConfiguration {

        @Bean
        FailingEndpoint failingEndpoint() {
            return new FailingEndpoint();
        }
    }

    /** 测试专用端点：抛出一个带哨兵文案的未预期异常。 */
    @RestController
    static class FailingEndpoint {

        @GetMapping("/api/v1/test-support/failing")
        String fail() {
            throw new IllegalStateException(SENTINEL + " at com.flowdesk.SomeInternalClass.method");
        }
    }

    @Test
    void unmatchedPathReturns404EndpointNotFound() throws Exception {
        MvcResult result = this.mockMvc.perform(get("/api/v1/no-such-endpoint"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:flowdesk:problem:endpoint-not-found"))
                .andExpect(jsonPath("$.code").value("ENDPOINT_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.instance").value("/api/v1/no-such-endpoint"))
                .andReturn();

        assertThat(body(result)).doesNotContain("NoResourceFoundException");
    }

    @Test
    void wrongMethodReturns405AndKeepsTheAllowHeader() throws Exception {
        this.mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(HttpHeaders.ALLOW, containsString("POST")))
                .andExpect(jsonPath("$.type").value("urn:flowdesk:problem:method-not-allowed"))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.status").value(405));

        this.mockMvc.perform(delete(BASE_PATH + "/{id}", UUID.randomUUID().toString()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, containsString("GET")))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void unsupportedContentTypeReturns415() throws Exception {
        this.mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("title=标题"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:flowdesk:problem:unsupported-media-type"))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.status").value(415));
    }

    @Test
    void unexpectedExceptionReturns500WithoutLeakingAnything() throws Exception {
        MvcResult result = this.mockMvc.perform(get("/api/v1/test-support/failing"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:flowdesk:problem:internal-server-error"))
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value("服务暂时不可用，请稍后重试"))
                .andReturn();

        String body = body(result);
        assertThat(body).doesNotContain(SENTINEL);
        assertThat(body).doesNotContain("IllegalStateException");
        assertThat(body).doesNotContain("SomeInternalClass");
        assertThat(body).doesNotContain("java.");
        assertThat(body).doesNotContain("org.springframework");
        assertThat(body).doesNotContain("at com.");
    }

    /**
     * 回归：兜底处理器优先级最低，绝不截获既有的业务映射。
     */
    @Test
    void fallbackDoesNotInterceptExistingErrorMappings() throws Exception {
        String unknown = UUID.randomUUID().toString();
        String ticketId = createTicket();

        // 工单不存在 → 404 TICKET_NOT_FOUND（不是 500）
        this.mockMvc.perform(get(BASE_PATH + "/{id}", unknown))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TICKET_NOT_FOUND"));

        // 缺少 If-Match → 428 PRECONDITION_REQUIRED
        this.mockMvc.perform(post(BASE_PATH + "/{id}/start", ticketId))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));

        // 非法 If-Match → 400 INVALID_IF_MATCH
        this.mockMvc.perform(post(BASE_PATH + "/{id}/start", ticketId).header(HttpHeaders.IF_MATCH, "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IF_MATCH"));

        // Bean Validation → 400 INVALID_REQUEST
        this.mockMvc.perform(post(BASE_PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        // 非法 JSON → 400 INVALID_REQUEST
        this.mockMvc.perform(post(BASE_PATH).contentType(MediaType.APPLICATION_JSON).content("{ oops }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        // 非法状态转换 → 409 ILLEGAL_STATUS_TRANSITION
        this.mockMvc.perform(post(BASE_PATH + "/{id}/close", ticketId).header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_STATUS_TRANSITION"));

        // 版本冲突 → 412 TICKET_VERSION_CONFLICT
        this.mockMvc.perform(post(BASE_PATH + "/{id}/start", ticketId).header(HttpHeaders.IF_MATCH, "\"7\""))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("TICKET_VERSION_CONFLICT"));
    }

    // ---------- 辅助 ----------

    private String createTicket() throws Exception {
        MvcResult result = this.mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"标题","description":"描述","category":"OTHER","priority":"P3",
                                 "requesterId":"alice"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = this.objectMapper.readTree(body(result));
        return body.path("id").asText();
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
