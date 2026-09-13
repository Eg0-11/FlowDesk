package com.flowdesk.bootstrap.ticket;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 工单 REST 接口集成测试。
 *
 * <p>真实装配：完整 Spring 上下文 + 真实 H2（PostgreSQL 兼容模式）+ 真实 Flyway 迁移 + 真实用例服务；
 * 不使用 Mock 的用例端口，因此 HTTP 契约、乐观并发、领域规则与持久化是一次性端到端验证的。</p>
 *
 * <p>为避免依赖测试顺序，使用独立的数据库名，并在每个测试前清空 tickets 表。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:flowdesk_http_it"
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@AutoConfigureMockMvc
class TicketControllerIntegrationTest {

    private static final String BASE_PATH = "/api/v1/tickets";

    private static final String CREATE_BODY = """
            {"title":"无法登录办公系统","description":"输入正确密码后仍提示认证失败",
             "category":"ACCOUNT_ACCESS","priority":"P2","requesterId":"alice"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ApplicationContext applicationContext;

    @BeforeEach
    void clearTickets() {
        this.jdbcClient.sql("DELETE FROM tickets").update();
    }

    // ---------- ① 创建 ----------

    @Test
    void createReturns201WithLocationAndEtag() throws Exception {
        MvcResult result = this.mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.assigneeId").doesNotExist())
                .andReturn();

        JsonNode body = parse(result);
        String ticketId = body.path("id").asText();

        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION))
                .isEqualTo(BASE_PATH + "/" + ticketId);
        assertThat(rowCount(ticketId)).as("创建必须真实落库").isEqualTo(1L);
    }

    // ---------- ② 查询 ----------

    @Test
    void getReturnsEveryFieldAndTheCurrentEtag() throws Exception {
        String ticketId = createTicketId();

        this.mockMvc.perform(get(BASE_PATH + "/{id}", ticketId))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andExpect(jsonPath("$.id").value(ticketId))
                .andExpect(jsonPath("$.title").value("无法登录办公系统"))
                .andExpect(jsonPath("$.description").value("输入正确密码后仍提示认证失败"))
                .andExpect(jsonPath("$.category").value("ACCOUNT_ACCESS"))
                .andExpect(jsonPath("$.priority").value("P2"))
                .andExpect(jsonPath("$.requesterId").value("alice"))
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.createdAt").isString())
                .andExpect(jsonPath("$.updatedAt").isString())
                // 可空字段输出 JSON null
                .andExpect(jsonPath("$.assigneeId").doesNotExist())
                .andExpect(jsonPath("$.resolution").doesNotExist());
    }

    @Test
    void getExposesNullForEmptyOptionalFields() throws Exception {
        String ticketId = createTicketId();

        String raw = this.mockMvc.perform(get(BASE_PATH + "/{id}", ticketId))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(raw).contains("\"assigneeId\":null");
        assertThat(raw).contains("\"resolution\":null");
        assertThat(raw).contains("\"resolvedAt\":null");
        assertThat(raw).contains("\"closedAt\":null");
    }

    // ---------- ③④ 完整流程与 ETag 递增 ----------

    @Test
    void runsTheFullLifecycleWithMonotonicEtags() throws Exception {
        String ticketId = createTicketId();

        assertThat(etagOf(get(BASE_PATH + "/{id}", ticketId))).isEqualTo("\"0\"");

        assertThat(change(ticketId, "assign", "{\"assigneeId\":\"bob\"}", "0")).isEqualTo("\"1\"");
        assertThat(change(ticketId, "reassign", "{\"assigneeId\":\"carol\"}", "1")).isEqualTo("\"2\"");
        assertThat(change(ticketId, "start", null, "2")).isEqualTo("\"3\"");
        assertThat(change(ticketId, "resolve", "{\"resolution\":\"已重置认证状态\"}", "3")).isEqualTo("\"4\"");
        assertThat(change(ticketId, "close", null, "4")).isEqualTo("\"5\"");

        this.mockMvc.perform(get(BASE_PATH + "/{id}", ticketId))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"5\""))
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.assigneeId").value("carol"))
                .andExpect(jsonPath("$.resolution").value("已重置认证状态"))
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.resolvedAt").isString())
                .andExpect(jsonPath("$.closedAt").isString());
    }

    @Test
    void responseVersionAlwaysMatchesTheEtag() throws Exception {
        String ticketId = createTicketId();
        change(ticketId, "assign", "{\"assigneeId\":\"bob\"}", "0");

        MvcResult result = this.mockMvc.perform(get(BASE_PATH + "/{id}", ticketId)).andReturn();

        String etag = result.getResponse().getHeader(HttpHeaders.ETAG);
        JsonNode body = parse(result);
        assertThat(etag).isEqualTo("\"" + body.path("version").asLong() + "\"");
    }

    // ---------- ⑤⑥⑦ 乐观并发协议 ----------

    @Test
    void staleEtagIsRejectedWith412AndLeavesDataUnchanged() throws Exception {
        String ticketId = createTicketId();
        change(ticketId, "assign", "{\"assigneeId\":\"bob\"}", "0");

        this.mockMvc.perform(post(BASE_PATH + "/{id}/start", ticketId)
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isPreconditionFailed())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("TICKET_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.status").value(412));

        assertThat(versionOf(ticketId)).as("版本冲突不得产生任何写入").isEqualTo(1L);
        assertThat(statusOf(ticketId)).isEqualTo("ASSIGNED");
    }

    @Test
    void missingIfMatchIsRejectedWith428() throws Exception {
        String ticketId = createTicketId();

        this.mockMvc.perform(post(BASE_PATH + "/{id}/assign", ticketId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\":\"bob\"}"))
                .andExpect(status().isPreconditionRequired())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"))
                .andExpect(jsonPath("$.status").value(428));
    }

    @ParameterizedTest(name = "If-Match=[{0}]")
    @ValueSource(strings = {
            "W/\"0\"",
            "*",
            "\"0\", \"1\"",
            "0",
            "\"-1\"",
            "\"abc\"",
            "\"1.0\"",
            "\"01\"",
            "\"99999999999999999999\"",
            "\"1 2\"",
            "\" 0 \"",
            "\"0",
            "0\"",
            "\"\"",
            " ",
    })
    void invalidIfMatchIsRejectedWith400(String invalidIfMatch) throws Exception {
        String ticketId = createTicketId();

        this.mockMvc.perform(post(BASE_PATH + "/{id}/assign", ticketId)
                        .header(HttpHeaders.IF_MATCH, invalidIfMatch)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\":\"bob\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_IF_MATCH"))
                .andExpect(jsonPath("$.status").value(400));

        assertThat(versionOf(ticketId)).isZero();
    }

    @Test
    void ifMatchAllowsSurroundingWhitespace() throws Exception {
        String ticketId = createTicketId();

        this.mockMvc.perform(post(BASE_PATH + "/{id}/assign", ticketId)
                        .header(HttpHeaders.IF_MATCH, "  \"0\"  ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\":\"bob\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""));
    }

    // ---------- ⑧⑨ 业务错误 ----------

    @Test
    void unknownTicketReturns404() throws Exception {
        String unknown = UUID.randomUUID().toString();

        this.mockMvc.perform(get(BASE_PATH + "/{id}", unknown))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:flowdesk:problem:ticket-not-found"))
                .andExpect(jsonPath("$.title").value("工单不存在"))
                .andExpect(jsonPath("$.code").value("TICKET_NOT_FOUND"))
                .andExpect(jsonPath("$.instance").value(BASE_PATH + "/" + unknown));

        this.mockMvc.perform(post(BASE_PATH + "/{id}/start", unknown).header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TICKET_NOT_FOUND"));
    }

    @Test
    void illegalTransitionReturns409() throws Exception {
        String ticketId = createTicketId();

        // NEW 状态下不能直接 close
        this.mockMvc.perform(post(BASE_PATH + "/{id}/close", ticketId).header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:flowdesk:problem:illegal-status-transition"))
                .andExpect(jsonPath("$.code").value("ILLEGAL_STATUS_TRANSITION"));

        assertThat(versionOf(ticketId)).isZero();
    }

    @Test
    void sameAssigneeReturns409() throws Exception {
        String ticketId = createTicketId();
        change(ticketId, "assign", "{\"assigneeId\":\"bob\"}", "0");

        this.mockMvc.perform(post(BASE_PATH + "/{id}/reassign", ticketId)
                        .header(HttpHeaders.IF_MATCH, "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\":\"bob\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:flowdesk:problem:same-assignee"))
                .andExpect(jsonPath("$.code").value("SAME_ASSIGNEE"));

        assertThat(versionOf(ticketId)).isEqualTo(1L);
    }

    // ---------- ⑩⑪⑫ 请求校验与错误体 ----------

    @Test
    void blankAndOverlongFieldsAreRejectedWith400() throws Exception {
        assertCreateRejected("""
                {"title":"   ","description":"描述","category":"OTHER","priority":"P3","requesterId":"alice"}
                """);
        assertCreateRejected("""
                {"title":"%s","description":"描述","category":"OTHER","priority":"P3","requesterId":"alice"}
                """.formatted("t".repeat(201)));
        assertCreateRejected("""
                {"title":"标题","description":"%s","category":"OTHER","priority":"P3","requesterId":"alice"}
                """.formatted("d".repeat(4001)));
        assertCreateRejected("""
                {"title":"标题","description":"描述","category":"OTHER","priority":"P3","requesterId":"   "}
                """);
        assertCreateRejected("""
                {"title":"标题","description":"描述","category":"OTHER","priority":"P3"}
                """);
    }

    @Test
    void invalidEnumAndMalformedJsonAreRejectedWith400() throws Exception {
        assertCreateRejected("""
                {"title":"标题","description":"描述","category":"NOT_A_CATEGORY","priority":"P3","requesterId":"alice"}
                """);
        assertCreateRejected("""
                {"title":"标题","description":"描述","category":"OTHER","priority":"P9","requesterId":"alice"}
                """);
        assertCreateRejected("{ this is not json }");
    }

    @Test
    void invalidUuidPathIsRejectedWith400() throws Exception {
        this.mockMvc.perform(get(BASE_PATH + "/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        this.mockMvc.perform(post(BASE_PATH + "/{id}/start", "not-a-uuid").header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void blankAssigneeAndOverlongResolutionAreRejectedWith400() throws Exception {
        String ticketId = createTicketId();

        this.mockMvc.perform(post(BASE_PATH + "/{id}/assign", ticketId)
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        change(ticketId, "assign", "{\"assigneeId\":\"bob\"}", "0");
        change(ticketId, "start", null, "1");

        this.mockMvc.perform(post(BASE_PATH + "/{id}/resolve", ticketId)
                        .header(HttpHeaders.IF_MATCH, "\"2\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolution\":\"%s\"}".formatted("r".repeat(2001))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void everyErrorIsApplicationProblemJson() throws Exception {
        String ticketId = createTicketId();
        String unknown = UUID.randomUUID().toString();

        MvcResult missingPrecondition = this.mockMvc
                .perform(post(BASE_PATH + "/{id}/start", ticketId)).andReturn();
        MvcResult invalidIfMatch = this.mockMvc.perform(post(BASE_PATH + "/{id}/start", ticketId)
                .header(HttpHeaders.IF_MATCH, "abc")).andReturn();
        MvcResult notFound = this.mockMvc.perform(get(BASE_PATH + "/{id}", unknown)).andReturn();
        MvcResult illegalTransition = this.mockMvc.perform(post(BASE_PATH + "/{id}/close", ticketId)
                .header(HttpHeaders.IF_MATCH, "\"0\"")).andReturn();
        MvcResult invalidBody = this.mockMvc.perform(post(BASE_PATH)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();

        for (MvcResult result : new MvcResult[] { missingPrecondition, invalidIfMatch, notFound,
                illegalTransition, invalidBody }) {
            assertThat(result.getResponse().getContentType())
                    .as("所有错误响应必须是 application/problem+json")
                    .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            assertThat(parse(result).path("code").asText()).isNotBlank();
            assertThat(parse(result).path("type").asText()).startsWith("urn:flowdesk:problem:");
        }
    }

    @Test
    void errorResponsesDoNotLeakStackSqlOrRawInput() throws Exception {
        String sentinel = "SENTINELVALUE" + "t".repeat(220);

        MvcResult tooLong = this.mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"%s","description":"描述","category":"OTHER","priority":"P3","requesterId":"alice"}
                                """.formatted(sentinel)))
                .andExpect(status().isBadRequest())
                .andReturn();

        MvcResult unknownTicket = this.mockMvc
                .perform(get(BASE_PATH + "/{id}", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound())
                .andReturn();

        for (MvcResult result : new MvcResult[] { tooLong, unknownTicket }) {
            String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(body).doesNotContain(sentinel);
            assertThat(body).doesNotContain("Exception");
            assertThat(body).doesNotContain("java.");
            assertThat(body).doesNotContain("org.springframework");
            assertThat(body).doesNotContain("SELECT");
            assertThat(body).doesNotContain("tickets WHERE");
            assertThat(body).doesNotContain("H2");
        }
    }

    // ---------- ⑬ 默认 profile 仍不接模型 ----------

    @Test
    void defaultProfileStillWiresNoModelInfrastructure() {
        assertThat(this.applicationContext.getEnvironment().getProperty("flowdesk.ai.enabled", Boolean.class))
                .isFalse();
        assertThat(this.applicationContext.getBeanNamesForType(ChatModel.class))
                .as("默认 profile 不得创建对话模型，因此不可能访问 DeepSeek")
                .isEmpty();
        assertThat(this.applicationContext.getBeanNamesForType(ChatClient.class)).isEmpty();
    }

    // ---------- 辅助 ----------

    private String createTicketId() throws Exception {
        MvcResult result = this.mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andReturn();
        return parse(result).path("id").asText();
    }

    private String change(String ticketId, String action, String body, String etag) throws Exception {
        var request = post(BASE_PATH + "/{id}/" + action, ticketId).header(HttpHeaders.IF_MATCH, "\"" + etag + "\"");
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        MvcResult result = this.mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getHeader(HttpHeaders.ETAG);
    }

    private String etagOf(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder)
            throws Exception {
        return this.mockMvc.perform(builder).andExpect(status().isOk()).andReturn()
                .getResponse().getHeader(HttpHeaders.ETAG);
    }

    private void assertCreateRejected(String body) throws Exception {
        this.mockMvc.perform(post(BASE_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(scalarLong("SELECT COUNT(*) FROM tickets")).as("非法请求不得落库").isZero();
    }

    private JsonNode parse(MvcResult result) throws Exception {
        return this.objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private long rowCount(String ticketId) {
        Long count = this.jdbcClient.sql("SELECT COUNT(*) FROM tickets WHERE id = ?")
                .param(1, UUID.fromString(ticketId))
                .query(Long.class)
                .single();
        return count == null ? -1L : count;
    }

    private long versionOf(String ticketId) {
        Long version = this.jdbcClient.sql("SELECT version FROM tickets WHERE id = ?")
                .param(1, UUID.fromString(ticketId))
                .query(Long.class)
                .single();
        return version == null ? -1L : version;
    }

    private String statusOf(String ticketId) {
        return this.jdbcClient.sql("SELECT status FROM tickets WHERE id = ?")
                .param(1, UUID.fromString(ticketId))
                .query(String.class)
                .single();
    }

    private long scalarLong(String sql) {
        Long value = this.jdbcClient.sql(sql).query(Long.class).single();
        return value == null ? -1L : value;
    }
}
