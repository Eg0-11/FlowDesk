package com.flowdesk.bootstrap.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 列表 / 搜索接口的 HTTP 契约测试。
 *
 * <p>夹具直接写库（绕过 HTTP），以便精确控制 {@code created_at}/{@code updated_at}：
 * 默认排序、排序字段、分页元数据都依赖时间，只有写库才能确定性地验证。</p>
 *
 * <pre>
 * 标题                            创建(日) 更新(日) 优先级 状态        分类            请求人 处理人
 * 无法登录办公系统                  01      05      P1     NEW         ACCOUNT_ACCESS  alice  —
 * VPN 连接超时                     02      03      P2     ASSIGNED    NETWORK         bob    bob
 * 三楼打印机离线                    03      04      P3     IN_PROGRESS HARDWARE        alice  carol
 * 邮件客户端闪退                    04      06      P4     RESOLVED    SOFTWARE        dave   bob
 * 报表系统权限申请 50% under_score   05      07      P2     CLOSED      OTHER           alice  bob
 * </pre>
 *
 * <p>所有查询参数都用 {@code param(...)} 而不是拼进 URL：既不依赖 URL 编码细节，
 * 也让中文与空值能原样送达服务端。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:flowdesk_http_it"
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@AutoConfigureMockMvc
class TicketSearchApiIntegrationTest {

    private static final String BASE_PATH = "/api/v1/tickets";

    private static final Instant DAY_ONE = Instant.parse("2026-02-01T00:00:00Z");

    private static final String LOGIN = "无法登录办公系统";

    private static final String VPN = "VPN 连接超时";

    private static final String PRINTER = "三楼打印机离线";

    private static final String MAIL = "邮件客户端闪退";

    private static final String PERMISSION = "报表系统权限申请 50% under_score";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ApplicationContext applicationContext;

    @BeforeEach
    void insertFixtures() {
        this.jdbcClient.sql("DELETE FROM tickets").update();

        insert(1, LOGIN, "输入正确密码后仍提示认证失败", "ACCOUNT_ACCESS", "P1", "alice", "NEW", null, 1, 5);
        insert(2, VPN, "外网网络无法建立隧道", "NETWORK", "P2", "bob", "ASSIGNED", "bob", 2, 3);
        insert(3, PRINTER, "打印机显示离线无法打印", "HARDWARE", "P3", "alice", "IN_PROGRESS", "carol", 3, 4);
        insert(4, MAIL, "打开附件时闪退", "SOFTWARE", "P4", "dave", "RESOLVED", "bob", 4, 6);
        insert(5, PERMISSION, "需要访问报表系统", "OTHER", "P2", "alice", "CLOSED", "bob", 5, 7);
    }

    // ---------- ① 默认响应 ----------

    @Test
    void defaultRequestReturnsTheFullEnvelopeSortedByUpdatedAtDescending() throws Exception {
        MvcResult result = getList();

        assertThat(metaOf(result, "page")).isZero();
        assertThat(metaOf(result, "size")).isEqualTo(20);
        assertThat(metaOf(result, "totalElements")).isEqualTo(5);
        assertThat(metaOf(result, "totalPages")).isEqualTo(1);
        assertThat(parse(result).path("hasNext").asBoolean()).isFalse();
        assertThat(parse(result).path("hasPrevious").asBoolean()).isFalse();
        assertThat(parse(result).path("sort").path("field").asText()).isEqualTo("updatedAt");
        assertThat(parse(result).path("sort").path("direction").asText()).isEqualTo("desc");

        // 更新顺序：05,04,01,03,02
        assertThat(titlesOf(result)).containsExactly(PERMISSION, MAIL, LOGIN, PRINTER, VPN);
    }

    @Test
    void defaultRequestIsJsonWithTheDocumentedFieldNames() throws Exception {
        this.mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items[0].id").isNotEmpty())
                .andExpect(jsonPath("$.items[0].title").isNotEmpty())
                .andExpect(jsonPath("$.items[0].status").isNotEmpty())
                .andExpect(jsonPath("$.items[0].version").isNumber())
                .andExpect(jsonPath("$.items[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$.page").isNumber())
                .andExpect(jsonPath("$.size").isNumber())
                .andExpect(jsonPath("$.totalElements").isNumber())
                .andExpect(jsonPath("$.totalPages").isNumber())
                .andExpect(jsonPath("$.hasNext").isBoolean())
                .andExpect(jsonPath("$.hasPrevious").isBoolean())
                .andExpect(jsonPath("$.sort.field").isNotEmpty())
                .andExpect(jsonPath("$.sort.direction").isNotEmpty());
    }

    @Test
    void itemsReuseTheTicketResponseShapeWithTheCurrentVersion() throws Exception {
        String ticketId = ticketIdOf(LOGIN);
        this.mockMvc.perform(post(BASE_PATH + "/{id}/assign", ticketId)
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\":\"zoe\"}"))
                .andExpect(status().isOk());

        JsonNode item = findItem(parse(getList()), LOGIN);

        assertThat(item.path("version").asLong()).isEqualTo(1L);
        assertThat(item.path("assigneeId").asText()).isEqualTo("zoe");
        assertThat(item.path("status").asText()).isEqualTo("ASSIGNED");

        // 列表项的 version 必须与单条查询一致
        MvcResult single = this.mockMvc.perform(get(BASE_PATH + "/{id}", ticketId))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(item.path("id").asText()).isEqualTo(ticketId);
        assertThat(item.path("version").asLong()).isEqualTo(parse(single).path("version").asLong());
    }

    @Test
    void listResponseCarriesNoCollectionEtag() throws Exception {
        this.mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.ETAG))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));
    }

    @Test
    void emptyResultIsNotAnError() throws Exception {
        this.jdbcClient.sql("DELETE FROM tickets").update();

        MvcResult result = getList();

        assertThat(titlesOf(result)).isEmpty();
        assertThat(metaOf(result, "totalElements")).isZero();
        assertThat(metaOf(result, "totalPages")).isZero();
        assertThat(parse(result).path("hasNext").asBoolean()).isFalse();
        assertThat(parse(result).path("hasPrevious").asBoolean()).isFalse();
    }

    @Test
    void itemsAreAlwaysAnArrayNotNull() throws Exception {
        this.jdbcClient.sql("DELETE FROM tickets").update();

        this.mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    // ---------- ② 筛选 ----------

    @Test
    void filtersByStatus() throws Exception {
        assertThat(titlesOf(getList("status", "NEW"))).containsExactly(LOGIN);
        assertThat(titlesOf(getList("status", "CLOSED"))).containsExactly(PERMISSION);
        assertThat(titlesOf(getList("status", "ASSIGNED"))).containsExactly(VPN);
        assertThat(titlesOf(getList("status", "IN_PROGRESS"))).containsExactly(PRINTER);
        assertThat(titlesOf(getList("status", "RESOLVED"))).containsExactly(MAIL);
    }

    @Test
    void filtersByCategory() throws Exception {
        assertThat(titlesOf(getList("category", "NETWORK"))).containsExactly(VPN);
        assertThat(titlesOf(getList("category", "HARDWARE"))).containsExactly(PRINTER);
        assertThat(titlesOf(getList("category", "OTHER"))).containsExactly(PERMISSION);
    }

    @Test
    void filtersByPriority() throws Exception {
        assertThat(titlesOf(getList("priority", "P1"))).containsExactly(LOGIN);
        // P2 有两条：默认按 updatedAt 倒序，PERMISSION(07) 在 VPN(03) 之前
        assertThat(titlesOf(getList("priority", "P2"))).containsExactly(PERMISSION, VPN);
        assertThat(titlesOf(getList("priority", "P4"))).containsExactly(MAIL);
    }

    @Test
    void filtersByRequesterAndAssignee() throws Exception {
        assertThat(titlesOf(getList("requesterId", "alice"))).containsExactly(PERMISSION, LOGIN, PRINTER);
        assertThat(titlesOf(getList("assigneeId", "bob"))).containsExactly(PERMISSION, MAIL, VPN);
        assertThat(titlesOf(getList("assigneeId", "carol"))).containsExactly(PRINTER);
        assertThat(titlesOf(getList("assigneeId", "nobody"))).isEmpty();
    }

    @Test
    void stripsSurroundingWhitespaceInUserIdentifiers() throws Exception {
        assertThat(titlesOf(getList("requesterId", "  alice  "))).containsExactly(PERMISSION, LOGIN, PRINTER);
        assertThat(titlesOf(getList("assigneeId", "\tbob\t"))).containsExactly(PERMISSION, MAIL, VPN);
    }

    @Test
    void filtersByKeywordInTitleAndDescription() throws Exception {
        assertThat(titlesOf(getList("keyword", "登录"))).containsExactly(LOGIN);
        assertThat(titlesOf(getList("keyword", "认证"))).as("命中描述").containsExactly(LOGIN);
        assertThat(titlesOf(getList("keyword", "网络"))).containsExactly(VPN);
        assertThat(titlesOf(getList("keyword", "vpn"))).as("大小写不敏感").containsExactly(VPN);
    }

    @Test
    void keywordTreatsLikeWildcardsAsOrdinaryCharacters() throws Exception {
        // 若 % 是通配符，这里会返回全部 5 条
        assertThat(titlesOf(getList("keyword", "%"))).containsExactly(PERMISSION);
        // 若 _ 是通配符，它会匹配任意单字符，同样会命中多条
        assertThat(titlesOf(getList("keyword", "_"))).containsExactly(PERMISSION);
        assertThat(titlesOf(getList("keyword", "under_score"))).containsExactly(PERMISSION);
    }

    @Test
    void combinesEveryFilterWithAnd() throws Exception {
        assertThat(titlesOf(getList("status", "CLOSED", "category", "OTHER", "priority", "P2",
                "requesterId", "alice"))).containsExactly(PERMISSION);

        assertThat(titlesOf(getList("status", "NEW", "requesterId", "dave"))).isEmpty();

        assertThat(titlesOf(getList("category", "NETWORK", "keyword", "网络", "assigneeId", "bob")))
                .containsExactly(VPN);

        assertThat(titlesOf(getList("status", "NEW", "category", "NETWORK"))).isEmpty();
    }

    @Test
    void filtersAndPagingCompose() throws Exception {
        MvcResult result = getList("requesterId", "alice", "page", "1", "size", "2");

        assertThat(titlesOf(result)).containsExactly(PRINTER);
        assertThat(metaOf(result, "totalElements")).isEqualTo(3);
        assertThat(metaOf(result, "totalPages")).isEqualTo(2);
    }

    // ---------- ③ 分页 ----------

    @Test
    void pagingMetadataIsCorrectForEveryPage() throws Exception {
        MvcResult first = getList("page", "0", "size", "2");
        assertThat(titlesOf(first)).containsExactly(PERMISSION, MAIL);
        assertThat(metaOf(first, "totalPages")).isEqualTo(3);
        assertThat(parse(first).path("hasNext").asBoolean()).isTrue();
        assertThat(parse(first).path("hasPrevious").asBoolean()).isFalse();

        MvcResult middle = getList("page", "1", "size", "2");
        assertThat(titlesOf(middle)).containsExactly(LOGIN, PRINTER);
        assertThat(metaOf(middle, "totalPages")).isEqualTo(3);
        assertThat(parse(middle).path("hasNext").asBoolean()).isTrue();
        assertThat(parse(middle).path("hasPrevious").asBoolean()).isTrue();

        MvcResult last = getList("page", "2", "size", "2");
        assertThat(titlesOf(last)).containsExactly(VPN);
        assertThat(metaOf(last, "totalPages")).isEqualTo(3);
        assertThat(parse(last).path("hasNext").asBoolean()).isFalse();
        assertThat(parse(last).path("hasPrevious").asBoolean()).isTrue();
    }

    @Test
    void pageBeyondTheLastOneReturns200WithEmptyItems() throws Exception {
        MvcResult result = getList("page", "9", "size", "2");

        assertThat(titlesOf(result)).isEmpty();
        assertThat(metaOf(result, "page")).isEqualTo(9);
        assertThat(metaOf(result, "totalElements")).isEqualTo(5);
        assertThat(metaOf(result, "totalPages")).isEqualTo(3);
        assertThat(parse(result).path("hasNext").asBoolean()).isFalse();
    }

    @Test
    void acceptsBoundaryPageValues() throws Exception {
        assertThat(titlesOf(getList("page", "0", "size", "1"))).hasSize(1);
        assertThat(titlesOf(getList("page", "0", "size", "100"))).hasSize(5);
        assertThat(titlesOf(getList("page", "4", "size", "1"))).containsExactly(VPN);
    }

    // ---------- ④ 排序 ----------

    @Test
    void sortsByCreatedAtInBothDirections() throws Exception {
        assertThat(titlesOf(getList("sortBy", "createdAt", "direction", "asc")))
                .containsExactly(LOGIN, VPN, PRINTER, MAIL, PERMISSION);
        assertThat(titlesOf(getList("sortBy", "createdAt", "direction", "desc")))
                .containsExactly(PERMISSION, MAIL, PRINTER, VPN, LOGIN);
    }

    @Test
    void sortsByUpdatedAtInBothDirections() throws Exception {
        assertThat(titlesOf(getList("sortBy", "updatedAt", "direction", "asc")))
                .containsExactly(VPN, PRINTER, LOGIN, MAIL, PERMISSION);
        assertThat(titlesOf(getList("sortBy", "updatedAt", "direction", "desc")))
                .containsExactly(PERMISSION, MAIL, LOGIN, PRINTER, VPN);
    }

    @Test
    void sortsByPriorityInBusinessOrder() throws Exception {
        assertThat(titlesOf(getList("sortBy", "priority", "direction", "asc")))
                .containsExactly(LOGIN, VPN, PERMISSION, PRINTER, MAIL);
        assertThat(titlesOf(getList("sortBy", "priority", "direction", "desc")))
                .containsExactly(MAIL, PRINTER, VPN, PERMISSION, LOGIN);
    }

    @Test
    void sortsByStatusInLifecycleOrder() throws Exception {
        assertThat(titlesOf(getList("sortBy", "status", "direction", "asc")))
                .containsExactly(LOGIN, VPN, PRINTER, MAIL, PERMISSION);
        assertThat(titlesOf(getList("sortBy", "status", "direction", "desc")))
                .containsExactly(PERMISSION, MAIL, PRINTER, VPN, LOGIN);
    }

    @Test
    void echoesTheEffectiveSort() throws Exception {
        this.mockMvc.perform(get(BASE_PATH).param("sortBy", "priority").param("direction", "asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sort.field").value("priority"))
                .andExpect(jsonPath("$.sort.direction").value("asc"));

        this.mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sort.field").value("updatedAt"))
                .andExpect(jsonPath("$.sort.direction").value("desc"));
    }

    // ---------- ⑤ 非法参数 ----------

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({
            "page, -1",
            "page, abc",
            "page, 1.5",
            "size, 0",
            "size, 101",
            "size, abc",
            "size, -1",
            "status, PENDING",
            "status, new",
            "category, BILLING",
            "priority, P5",
            "priority, p1",
            "sortBy, id",
            "sortBy, updated_at",
            "sortBy, createdAt DESC",
            "direction, ASC",
            "direction, descending",
            "direction, ''",
    })
    void invalidParametersReturn400WithTheUnifiedProblemDetail(String parameter, String value) throws Exception {
        this.mockMvc.perform(listRequest(parameter, value))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:flowdesk:problem:invalid-request"))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.instance").value(BASE_PATH));
    }

    @Test
    void enumValuesWithSurroundingWhitespaceAreRejected() throws Exception {
        // 枚举取值不做 strip：客户端的 " NEW" 就是非法输入，必须显式报错而不是被静默纠正
        for (String padded : new String[] { " NEW", "NEW ", " NEW " }) {
            this.mockMvc.perform(listRequest("status", padded))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        this.mockMvc.perform(listRequest("sortBy", " createdAt"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void emptyPageAndSizeValuesAreRejected() throws Exception {
        this.mockMvc.perform(listRequest("page", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        this.mockMvc.perform(listRequest("size", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = { "requesterId", "assigneeId" })
    void blankUserIdentifierIsRejected(String parameter) throws Exception {
        for (String blank : new String[] { "", "   ", "\u3000" }) {
            this.mockMvc.perform(listRequest(parameter, blank))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    @Test
    void overlongUserIdentifierIsRejected() throws Exception {
        this.mockMvc.perform(listRequest("requesterId", "u".repeat(65)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        this.mockMvc.perform(listRequest("assigneeId", "u".repeat(65)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void blankOrOverlongKeywordIsRejected() throws Exception {
        for (String invalid : new String[] { "", "   ", "\u3000", "k".repeat(201) }) {
            this.mockMvc.perform(listRequest("keyword", invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    @Test
    void acceptsKeywordAndUserIdAtTheirExactLimits() throws Exception {
        assertThat(titlesOf(getList("keyword", "k".repeat(200)))).isEmpty();
        assertThat(titlesOf(getList("requesterId", "u".repeat(64)))).isEmpty();
    }

    @Test
    void invalidParametersNeverLeakTheSubmittedValue() throws Exception {
        MvcResult result = this.mockMvc.perform(listRequest("sortBy", "sentinel-injection-attempt"))
                .andExpect(status().isBadRequest())
                .andReturn();

        String body = body(result);
        assertThat(body).doesNotContain("sentinel-injection-attempt");
        assertThat(body).doesNotContain("SELECT");
        assertThat(body).doesNotContain("Exception");
        assertThat(body).doesNotContain("java.");
    }

    @Test
    void rejectedQueryDoesNotTouchTheData() throws Exception {
        this.mockMvc.perform(listRequest("page", "-1")).andExpect(status().isBadRequest());

        assertThat(countTickets()).isEqualTo(5);
    }

    // ---------- ⑥ 内容协商与运行环境 ----------

    @Test
    void unacceptableAcceptOnTheListReturns406() throws Exception {
        this.mockMvc.perform(get(BASE_PATH).accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:flowdesk:problem:not-acceptable"))
                .andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG));
    }

    @Test
    void acceptableAcceptVariantsSucceed() throws Exception {
        this.mockMvc.perform(get(BASE_PATH).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(5));
        this.mockMvc.perform(get(BASE_PATH).accept(MediaType.ALL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(5));
        this.mockMvc.perform(get(BASE_PATH).accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("application/json")));
    }

    @Test
    void searchWorksWithoutAnyAiInfrastructureInTheDefaultProfile() throws Exception {
        // 默认 profile 下不存在任何可发起模型调用的组件（见 AiDisabledContextTests），
        // 因此本用例能通过本身就证明列表查询不依赖 DeepSeek
        assertThat(this.applicationContext.getBeanNamesForType(ChatModel.class)).isEmpty();
        assertThat(this.applicationContext.getEnvironment().getProperty("spring.ai.model.chat")).isEqualTo("none");

        assertThat(titlesOf(getList())).hasSize(5);
    }

    // ---------- 辅助 ----------

    private MockHttpServletRequestBuilder listRequest(String... nameValuePairs) {
        MockHttpServletRequestBuilder request = get(BASE_PATH);
        for (int index = 0; index < nameValuePairs.length; index += 2) {
            request = request.param(nameValuePairs[index], nameValuePairs[index + 1]);
        }
        return request;
    }

    private MvcResult getList(String... nameValuePairs) throws Exception {
        return this.mockMvc.perform(listRequest(nameValuePairs)).andExpect(status().isOk()).andReturn();
    }

    private List<String> titlesOf(MvcResult result) throws Exception {
        List<String> titles = new ArrayList<>();
        for (JsonNode item : parse(result).path("items")) {
            titles.add(item.path("title").asText());
        }
        return titles;
    }

    private long metaOf(MvcResult result, String field) throws Exception {
        return parse(result).path(field).asLong();
    }

    private static JsonNode findItem(JsonNode body, String title) {
        for (JsonNode item : body.path("items")) {
            if (title.equals(item.path("title").asText())) {
                return item;
            }
        }
        throw new AssertionError("响应中没有标题为「" + title + "」的工单");
    }

    private JsonNode parse(MvcResult result) throws Exception {
        return this.objectMapper.readTree(body(result));
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String ticketIdOf(String title) {
        return this.jdbcClient.sql("SELECT id FROM tickets WHERE title = ?")
                .param(1, title)
                .query(UUID.class)
                .single()
                .toString();
    }

    private long countTickets() {
        Long count = this.jdbcClient.sql("SELECT COUNT(*) FROM tickets").query(Long.class).single();
        return count == null ? -1L : count;
    }

    /**
     * 直接写库构造夹具，以便精确控制时间列与状态字段组合（必须满足 V1 的 CHECK 约束）。
     */
    private void insert(int index, String title, String description, String category, String priority,
            String requesterId, String status, String assigneeId, int createdDay, int updatedDay) {

        boolean resolved = "RESOLVED".equals(status) || "CLOSED".equals(status);
        boolean closed = "CLOSED".equals(status);
        Instant created = DAY_ONE.plusSeconds(86_400L * (createdDay - 1));
        Instant updated = DAY_ONE.plusSeconds(86_400L * (updatedDay - 1));

        this.jdbcClient.sql("INSERT INTO tickets (id, title, description, category, priority, requester_id, "
                        + "assignee_id, status, resolution, created_at, updated_at, resolved_at, closed_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)")
                .param(1, UUID.fromString(String.format("00000000-0000-0000-0000-%012d", index)))
                .param(2, title)
                .param(3, description)
                .param(4, category)
                .param(5, priority)
                .param(6, requesterId)
                .param(7, assigneeId, Types.VARCHAR)
                .param(8, status)
                .param(9, resolved ? "已处理" : null, Types.VARCHAR)
                .param(10, offset(created))
                .param(11, offset(updated))
                .param(12, resolved ? offset(updated) : null, Types.TIMESTAMP_WITH_TIMEZONE)
                .param(13, closed ? offset(updated) : null, Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    private static OffsetDateTime offset(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
