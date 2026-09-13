package com.flowdesk.bootstrap.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 文本规范化与严格 UUID 的 HTTP 契约测试。
 *
 * <p>两条语义都在 HTTP 边界上验证：</p>
 * <ul>
 *   <li><b>文本规范化</b>：先 {@code strip()}、再判空白、再按规范化后的长度校验，
 *       因此「最大有效长度 + 首尾空白」合法且落库为规范化值，「strip 后仍超长」被拒绝；</li>
 *   <li><b>严格 UUID</b>：只接受规范的 36 位连字符形式（大小写均可），
 *       缩写形式、缺连字符、首尾空白一律 400。</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:flowdesk_http_it"
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@AutoConfigureMockMvc
class TicketRequestNormalizationTests {

    private static final String BASE_PATH = "/api/v1/tickets";

    private static final String UNICODE_SPACE = "\u3000";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void clearTickets() {
        this.jdbcClient.sql("DELETE FROM tickets").update();
    }

    // ---------- 文本规范化 ----------

    @Test
    void maxLengthFieldsWithAsciiWhitespaceAreAcceptedAndNormalized() throws Exception {
        String title = "t".repeat(200);
        String description = "d".repeat(4000);
        String requesterId = "u".repeat(64);

        MvcResult result = this.mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("  " + title + "  ", "   " + description + "   ",
                                "  " + requesterId + "  ")))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode body = parse(result);
        assertThat(body.path("title").asText()).isEqualTo(title).hasSize(200);
        assertThat(body.path("description").asText()).isEqualTo(description).hasSize(4000);
        assertThat(body.path("requesterId").asText()).isEqualTo(requesterId).hasSize(64);

        assertThat(persisted(body.path("id").asText(), "title")).isEqualTo(title);
        assertThat(persisted(body.path("id").asText(), "description")).isEqualTo(description);
        assertThat(persisted(body.path("id").asText(), "requester_id")).isEqualTo(requesterId);
    }

    @Test
    void maxLengthFieldsWithUnicodeWhitespaceAreAcceptedAndNormalized() throws Exception {
        String title = "t".repeat(200);
        String description = "d".repeat(4000);
        String requesterId = "u".repeat(64);

        MvcResult result = this.mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(UNICODE_SPACE + title + UNICODE_SPACE,
                                UNICODE_SPACE + description + UNICODE_SPACE,
                                UNICODE_SPACE + requesterId + UNICODE_SPACE)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode body = parse(result);
        assertThat(body.path("title").asText()).isEqualTo(title);
        assertThat(body.path("description").asText()).isEqualTo(description);
        assertThat(body.path("requesterId").asText()).isEqualTo(requesterId);
        assertThat(persisted(body.path("id").asText(), "title")).isEqualTo(title);
    }

    @Test
    void createFieldsExceedingTheLimitAfterStripAreRejected() throws Exception {
        assertCreateRejected(createBody("  " + "t".repeat(201) + "  ", "描述", "alice"));
        assertCreateRejected(createBody("标题", "  " + "d".repeat(4001) + "  ", "alice"));
        assertCreateRejected(createBody("标题", "描述", "  " + "u".repeat(65) + "  "));
        // Unicode 空白同样先被去掉，因此超长判定发生在规范化之后
        assertCreateRejected(createBody(UNICODE_SPACE + "t".repeat(201) + UNICODE_SPACE, "描述", "alice"));
    }

    @Test
    void assigneeIsNormalizedAndPersisted() throws Exception {
        String ticketId = createTicket();
        String assignee = "a".repeat(64);

        this.mockMvc.perform(post(BASE_PATH + "/{id}/assign", ticketId)
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\":\"  " + assignee + "  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeId").value(assignee));

        assertThat(persisted(ticketId, "assignee_id")).isEqualTo(assignee);
    }

    @Test
    void assigneeWithUnicodeWhitespaceIsNormalized() throws Exception {
        String ticketId = createTicket();
        String assignee = "a".repeat(64);

        this.mockMvc.perform(post(BASE_PATH + "/{id}/assign", ticketId)
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\":\"" + UNICODE_SPACE + assignee + UNICODE_SPACE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeId").value(assignee));

        assertThat(persisted(ticketId, "assignee_id")).isEqualTo(assignee);
    }

    @Test
    void resolutionIsNormalizedAndPersisted() throws Exception {
        String ticketId = createTicket();
        change(ticketId, "assign", "{\"assigneeId\":\"bob\"}", "0");
        change(ticketId, "start", null, "1");
        String resolution = "r".repeat(2000);

        this.mockMvc.perform(post(BASE_PATH + "/{id}/resolve", ticketId)
                        .header(HttpHeaders.IF_MATCH, "\"2\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolution\":\"" + UNICODE_SPACE + resolution + "  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolution").value(resolution));

        assertThat(persisted(ticketId, "resolution")).isEqualTo(resolution);
    }

    @Test
    void assigneeAndResolutionExceedingTheLimitAfterStripAreRejected() throws Exception {
        String ticketId = createTicket();

        this.mockMvc.perform(post(BASE_PATH + "/{id}/assign", ticketId)
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\":\"  " + "a".repeat(65) + "  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        change(ticketId, "assign", "{\"assigneeId\":\"bob\"}", "0");
        change(ticketId, "start", null, "1");

        this.mockMvc.perform(post(BASE_PATH + "/{id}/resolve", ticketId)
                        .header(HttpHeaders.IF_MATCH, "\"2\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolution\":\"  " + "r".repeat(2001) + "  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        assertThat(versionOf(ticketId)).isEqualTo(2L);
    }

    @Test
    void nullFieldsDoNotCauseAnExceptionOtherThanValidation() throws Exception {
        // null 字段不得因为 strip() 触发 NPE，应稳定返回 400 校验错误
        assertCreateRejected("""
                {"title":null,"description":"描述","category":"OTHER","priority":"P3","requesterId":"alice"}
                """);
        assertCreateRejected("""
                {"title":"标题","description":null,"category":"OTHER","priority":"P3","requesterId":"alice"}
                """);
        assertCreateRejected("""
                {"title":"标题","description":"描述","category":"OTHER","priority":"P3","requesterId":null}
                """);
        assertCreateRejected("""
                {"title":"标题","description":"描述","category":"OTHER","priority":"P3","requesterId":"   "}
                """);
    }

    // ---------- 严格 UUID ----------

    @ParameterizedTest(name = "ticketId=[{0}]")
    @ValueSource(strings = {
            "1-1-1-1-1",
            "111111112222333344445555555555555",
            "11111111-2222-3333-4444-555555555555 ",
            " 11111111-2222-3333-4444-555555555555",
            "11111111_2222_3333_4444_555555555555",
            "111111112222-3333-4444-555555555555",
            "11111111-2222-3333-4444-55555555555z",
            "not-a-uuid",
    })
    void malformedTicketIdInGetIsRejectedWith400(String malformedId) throws Exception {
        this.mockMvc.perform(get(BASE_PATH + "/{id}", malformedId))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.type").value("urn:flowdesk:problem:invalid-request"));
    }

    @ParameterizedTest(name = "ticketId=[{0}]")
    @ValueSource(strings = { "1-1-1-1-1", "111111112222333344445555555555555", "not-a-uuid" })
    void malformedTicketIdInStateChangeIsRejectedWith400(String malformedId) throws Exception {
        this.mockMvc.perform(post(BASE_PATH + "/{id}/start", malformedId).header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void canonicalLowerCaseAndUpperCaseTicketIdsAreBothAccepted() throws Exception {
        String ticketId = createTicket();

        this.mockMvc.perform(get(BASE_PATH + "/{id}", ticketId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ticketId));

        this.mockMvc.perform(get(BASE_PATH + "/{id}", ticketId.toUpperCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ticketId));
    }

    @Test
    void abbreviatedUuidDoesNotSilentlyResolveToATicket() throws Exception {
        // Java 的 UUID.fromString 会把 1-1-1-1-1 解析成 00000001-0001-0001-0001-000000000001；
        // 严格模式必须拒绝，而不是把它当成某个真实工单
        this.mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("标题", "描述", "alice")))
                .andExpect(status().isCreated());

        this.mockMvc.perform(get(BASE_PATH + "/{id}", "1-1-1-1-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // ---------- 辅助 ----------

    private static String createBody(String title, String description, String requesterId) {
        return """
                {"title":"%s","description":"%s","category":"OTHER","priority":"P3","requesterId":"%s"}
                """.formatted(title, description, requesterId);
    }

    private String createTicket() throws Exception {
        MvcResult result = this.mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("标题", "描述", "alice")))
                .andExpect(status().isCreated())
                .andReturn();
        return parse(result).path("id").asText();
    }

    private void change(String ticketId, String action, String body, String etag) throws Exception {
        var request = post(BASE_PATH + "/{id}/" + action, ticketId).header(HttpHeaders.IF_MATCH, "\"" + etag + "\"");
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        this.mockMvc.perform(request).andExpect(status().isOk());
    }

    private void assertCreateRejected(String body) throws Exception {
        this.mockMvc.perform(post(BASE_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private JsonNode parse(MvcResult result) throws Exception {
        return this.objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String persisted(String ticketId, String column) {
        return this.jdbcClient.sql("SELECT " + column + " FROM tickets WHERE id = ?")
                .param(1, UUID.fromString(ticketId))
                .query(String.class)
                .single();
    }

    private long versionOf(String ticketId) {
        Long version = this.jdbcClient.sql("SELECT version FROM tickets WHERE id = ?")
                .param(1, UUID.fromString(ticketId))
                .query(Long.class)
                .single();
        return version == null ? -1L : version;
    }
}
