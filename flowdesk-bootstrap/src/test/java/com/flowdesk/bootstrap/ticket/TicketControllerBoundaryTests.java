package com.flowdesk.bootstrap.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flowdesk.application.ticket.TicketApplicationErrorCode;
import com.flowdesk.application.ticket.TicketApplicationException;
import com.flowdesk.application.ticket.port.in.TicketQueryUseCase;
import com.flowdesk.application.ticket.port.out.TicketRepository;
import com.flowdesk.application.ticket.port.out.TicketSearchCriteria;
import com.flowdesk.application.ticket.port.out.TicketSearchResult;
import com.flowdesk.application.ticket.port.out.VersionedTicket;
import com.flowdesk.application.ticket.query.GetTicketQuery;
import com.flowdesk.application.ticket.query.SearchTicketsQuery;
import com.flowdesk.application.ticket.service.TicketApplicationService;
import com.flowdesk.application.ticket.view.TicketPageView;
import com.flowdesk.application.ticket.view.TicketView;
import com.flowdesk.domain.ticket.Ticket;
import com.flowdesk.domain.ticket.TicketId;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 列表接口的<b>输入端口边界</b>测试。
 *
 * <p>要证明的两件事：</p>
 * <ol>
 *   <li>HTTP 层只在调用输入端口<b>之前</b>做线格式解析（整数、枚举名保持原样），
 *       规范化与校验<b>只发生在应用用例内部一次</b>；</li>
 *   <li>用例抛出的 {@code INVALID_QUERY} 被精确翻译成列表接口承诺的
 *       {@code INVALID_REQUEST}，其余错误码原样交给既有映射，不被误改。</li>
 * </ol>
 *
 * <p>用 {@code @Primary} 的记账替身包裹真实 Bean（不用 Mockito）：请求来源、端口调用次数与
 * 存储调用次数都是可断言的客观事实。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:flowdesk_http_it"
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@AutoConfigureMockMvc
class TicketControllerBoundaryTests {

    private static final String BASE_PATH = "/api/v1/tickets";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private CountingQueryUseCase queryUseCase;

    @Autowired
    private CountingTicketRepository repository;

    @BeforeEach
    void resetCounters() {
        this.jdbcClient.sql("DELETE FROM tickets").update();
        this.queryUseCase.reset();
        this.repository.reset();
    }

    // ---------- ① 合法请求只调用一次查询输入端口 ----------

    @Test
    void validRequestCallsTheQueryPortExactlyOnce() throws Exception {
        this.mockMvc.perform(get(BASE_PATH).param("status", "NEW").param("size", "5"))
                .andExpect(status().isOk());

        assertThat(this.queryUseCase.searchCalls()).as("输入端口只能被调用一次").isEqualTo(1);
        assertThat(this.queryUseCase.getCalls()).isZero();
        assertThat(this.repository.searchCalls()).as("列表查询只走一次存储查询").isEqualTo(1);
        assertThat(this.repository.totalCalls()).as("列表查询不得走单条读取或写入").isEqualTo(1);
    }

    @Test
    void defaultRequestAlsoCallsTheQueryPortExactlyOnce() throws Exception {
        this.mockMvc.perform(get(BASE_PATH)).andExpect(status().isOk());

        assertThat(this.queryUseCase.searchCalls()).isEqualTo(1);
        assertThat(this.repository.searchCalls()).isEqualTo(1);
    }

    // ---------- ② 非法查询不触碰 Repository ----------

    @Test
    void invalidQueryTouchesNeitherTheRepositoryNorIsRejectedBeforeThePort() throws Exception {
        this.mockMvc.perform(get(BASE_PATH).param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        // 关键：用例被调用了恰好一次 —— 说明校验发生在用例内部，
        // 而不是 HTTP 层先跑一遍再决定要不要调用端口
        assertThat(this.queryUseCase.searchCalls()).isEqualTo(1);
        // 存储完全没有被触碰
        assertThat(this.repository.totalCalls()).isZero();
    }

    @Test
    void invalidQueryIsRejectedForEveryRuleWithoutTouchingTheRepository() throws Exception {
        String[] invalidQueries = { "page=-1", "size=0", "size=101", "status=NOPE", "category=NOPE",
                "priority=P5", "sortBy=id", "direction=ASC" };

        for (String query : invalidQueries) {
            this.queryUseCase.reset();
            this.repository.reset();

            this.mockMvc.perform(get(BASE_PATH + "?" + query))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

            assertThat(this.queryUseCase.searchCalls()).as(query).isEqualTo(1);
            assertThat(this.repository.totalCalls()).as(query).isZero();
        }
    }

    @Test
    void wireFormatFailuresAreRejectedBeforeThePort() throws Exception {
        // 非整数属于线格式问题：HTTP 层直接拒绝，不进入用例
        this.mockMvc.perform(get(BASE_PATH).param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        assertThat(this.queryUseCase.searchCalls()).isZero();
        assertThat(this.repository.totalCalls()).isZero();
    }

    // ---------- ③ 只有 INVALID_QUERY 被翻译 ----------

    @Test
    void onlyInvalidQueryIsTranslatedIntoInvalidRequest() throws Exception {
        this.queryUseCase.failWith(new TicketApplicationException(
                TicketApplicationErrorCode.INVALID_QUERY, "page 不能小于 0"));

        this.mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:flowdesk:problem:invalid-request"))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.detail").value("page 不能小于 0"));
    }

    @Test
    void otherApplicationErrorCodesKeepTheirExistingHttpContract() throws Exception {
        // INVALID_COMMAND 必须仍然是 400 INVALID_COMMAND：不能被列表接口的翻译逻辑误改
        this.queryUseCase.failWith(new TicketApplicationException(
                TicketApplicationErrorCode.INVALID_COMMAND, "命令不合法"));
        this.mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_COMMAND"))
                .andExpect(jsonPath("$.type").value("urn:flowdesk:problem:invalid-command"));

        this.queryUseCase.failWith(new TicketApplicationException(
                TicketApplicationErrorCode.TICKET_NOT_FOUND, "工单不存在"));
        this.mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TICKET_NOT_FOUND"));

        this.queryUseCase.failWith(new TicketApplicationException(
                TicketApplicationErrorCode.TICKET_VERSION_CONFLICT, "版本冲突"));
        this.mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("TICKET_VERSION_CONFLICT"));

        this.queryUseCase.failWith(new TicketApplicationException(
                TicketApplicationErrorCode.TICKET_ALREADY_EXISTS, "已存在"));
        this.mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_ALREADY_EXISTS"));
    }

    @Test
    void unexpectedFailureFromTheQueryPortStillFallsBackToFiveHundred() throws Exception {
        this.queryUseCase.failWith(new IllegalStateException("boom"));

        this.mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"));
    }

    @Test
    void translatedErrorNeverEchoesTheSubmittedValue() throws Exception {
        String body = this.mockMvc.perform(listRequest("sortBy", "sentinel-port-boundary"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("sentinel-port-boundary");
    }

    private MockHttpServletRequestBuilder listRequest(String name, String value) {
        return get(BASE_PATH).param(name, value);
    }

    // ---------- 替身 ----------

    /**
     * 记账的查询输入端口替身：委托真实用例，只统计调用次数并支持预设失败。
     */
    static final class CountingQueryUseCase implements TicketQueryUseCase {

        private final TicketApplicationService delegate;

        private final AtomicInteger searchCalls = new AtomicInteger();

        private final AtomicInteger getCalls = new AtomicInteger();

        private volatile RuntimeException failure;

        CountingQueryUseCase(TicketApplicationService delegate) {
            this.delegate = delegate;
        }

        @Override
        public TicketView get(GetTicketQuery query) {
            this.getCalls.incrementAndGet();
            return this.delegate.get(query);
        }

        @Override
        public TicketPageView search(SearchTicketsQuery query) {
            this.searchCalls.incrementAndGet();
            RuntimeException preset = this.failure;
            if (preset != null) {
                this.failure = null;
                throw preset;
            }
            return this.delegate.search(query);
        }

        void failWith(RuntimeException exception) {
            this.failure = exception;
        }

        void reset() {
            this.searchCalls.set(0);
            this.getCalls.set(0);
            this.failure = null;
        }

        int searchCalls() {
            return this.searchCalls.get();
        }

        int getCalls() {
            return this.getCalls.get();
        }
    }

    /**
     * 记账的存储替身：委托真实适配器，统计每类调用的次数。
     */
    static final class CountingTicketRepository implements TicketRepository {

        private final TicketRepository delegate;

        private final AtomicInteger searchCalls = new AtomicInteger();

        private final AtomicInteger totalCalls = new AtomicInteger();

        CountingTicketRepository(TicketRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<VersionedTicket> findById(TicketId ticketId) {
            this.totalCalls.incrementAndGet();
            return this.delegate.findById(ticketId);
        }

        @Override
        public VersionedTicket insert(Ticket ticket) {
            this.totalCalls.incrementAndGet();
            return this.delegate.insert(ticket);
        }

        @Override
        public VersionedTicket update(Ticket ticket, long expectedVersion) {
            this.totalCalls.incrementAndGet();
            return this.delegate.update(ticket, expectedVersion);
        }

        @Override
        public TicketSearchResult search(TicketSearchCriteria criteria) {
            this.searchCalls.incrementAndGet();
            this.totalCalls.incrementAndGet();
            return this.delegate.search(criteria);
        }

        void reset() {
            this.searchCalls.set(0);
            this.totalCalls.set(0);
        }

        int searchCalls() {
            return this.searchCalls.get();
        }

        int totalCalls() {
            return this.totalCalls.get();
        }
    }

    /**
     * 只在本测试上下文里生效的替身装配：真实 Bean 仍存在，注入点拿到 {@code @Primary} 替身。
     */
    @TestConfiguration
    static class CountingConfiguration {

        @Bean
        @Primary
        CountingTicketRepository countingTicketRepository(
                @Qualifier("ticketRepository") TicketRepository delegate) {

            return new CountingTicketRepository(delegate);
        }

        @Bean
        @Primary
        CountingQueryUseCase countingQueryUseCase(TicketApplicationService delegate) {
            return new CountingQueryUseCase(delegate);
        }
    }
}
