package com.flowdesk.infrastructure.ticket.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowdesk.application.ticket.port.out.TicketSearchCriteria;
import com.flowdesk.application.ticket.port.out.TicketSearchResult;
import com.flowdesk.application.ticket.query.TicketSortDirection;
import com.flowdesk.application.ticket.query.TicketSortField;
import com.flowdesk.domain.ticket.Ticket;
import com.flowdesk.domain.ticket.TicketCategory;
import com.flowdesk.domain.ticket.TicketId;
import com.flowdesk.domain.ticket.TicketPriority;
import com.flowdesk.domain.ticket.UserId;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 列表查询的<b>快照一致性</b>集成测试：两个真实连接 + 真实提交，精确定位在
 * 「COUNT 已完成、分页查询尚未执行」的那一瞬间插入并发写入。
 *
 * <p>为什么这样构造：COUNT 与分页数据是两条独立语句。若它们不在同一个可重复读事务里，
 * 两条语句之间提交的写入就会让总数与明细互相矛盾（总数 5 却返回 6 行，或只有 4 行）。
 * 这里用阻塞在 {@code prepareStatement} 上的钩子把并发写入<b>确定性地</b>安排在两条语句之间：
 * 没有任何 {@code sleep}，所有等待都有超时，超时即失败而不是挂住。</p>
 *
 * <p>每条用例都带一个<b>控制断言</b>：事务结束后再看一次，必须能看到刚才那次并发写入 ——
 * 否则「看不见」可能只是因为写入压根没发生，测试就成了空转。</p>
 */
class JdbcTicketSearchSnapshotIntegrationTest {

    private static final String MIGRATION_LOCATION = "classpath:db/migration";

    private static final Instant BASE = Instant.parse("2026-03-01T00:00:00Z");

    /** 所有等待的超时上限：超时即失败，不允许无限等待。 */
    private static final long TIMEOUT_SECONDS = 20L;

    private static final int FIXTURE_COUNT = 5;

    private static InterceptingDataSource dataSource;

    private static JdbcClient jdbcClient;

    private static JdbcTicketRepository repository;

    @BeforeAll
    static void migrateAnEmptyDatabase() {
        JdbcDataSource raw = new JdbcDataSource();
        raw.setURL("jdbc:h2:mem:flowdesk_search_snapshot_it"
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=10000");
        raw.setUser("sa");
        raw.setPassword("");

        Flyway.configure().dataSource(raw).locations(MIGRATION_LOCATION).load().migrate();

        dataSource = new InterceptingDataSource(raw);
        jdbcClient = JdbcClient.create(dataSource);
        repository = new JdbcTicketRepository(jdbcClient, TicketTransactionTemplates.write(dataSource),
                TicketTransactionTemplates.readOnly(dataSource));
    }

    @BeforeEach
    void insertFixtures() {
        dataSource.reset();
        jdbcClient.sql("DELETE FROM tickets").update();
        for (int index = 1; index <= FIXTURE_COUNT; index++) {
            insertRow(index);
        }
    }

    // ---------- ① 并发插入 ----------

    @Test
    void totalAndPageComeFromTheSameSnapshotWhenARowIsInsertedConcurrently() {
        List<UUID> before = allIds();
        dataSource.resetConnectionCount();

        CountDownLatch pageQueryReached = new CountDownLatch(1);
        CountDownLatch writerCommitted = new CountDownLatch(1);
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        // 钩子挂在「分页查询即将执行」这一点上：COUNT 已经跑完，分页语句还没发出去
        dataSource.beforeNextPageQuery(() -> {
            pageQueryReached.countDown();
            awaitOrFail(writerCommitted, "并发写入未在 " + TIMEOUT_SECONDS + " 秒内提交");
        });

        Thread writer = new Thread(() -> {
            try {
                awaitOrFail(pageQueryReached, "分页查询未在 " + TIMEOUT_SECONDS + " 秒内到达");
                repository.insert(newTicket());
            }
            catch (Throwable throwable) {
                unexpected.set(throwable);
            }
            finally {
                writerCommitted.countDown();
            }
        }, "flowdesk-concurrent-writer");
        writer.start();

        TicketSearchResult result = repository.search(criteria());
        joinOrFail(writer);

        assertThat(unexpected.get()).as("并发写入线程不得失败").isNull();
        assertThat(dataSource.connectionsOpened()).as("搜索事务与并发写入必须用两个真实连接")
                .isGreaterThanOrEqualTo(2);

        // 一致性：总数与明细都来自 COUNT 那一刻的快照
        assertThat(result.totalElements()).isEqualTo(FIXTURE_COUNT);
        assertThat(idsOf(result)).containsExactlyInAnyOrderElementsOf(before);

        // 控制断言：写入确实提交了，所以上面的「看不见」是快照隔离，而不是什么都没发生
        assertThat(countAll()).isEqualTo(FIXTURE_COUNT + 1);
        assertThat(repository.search(criteria()).totalElements()).isEqualTo(FIXTURE_COUNT + 1);
    }

    // ---------- ② 并发删除 ----------

    @Test
    void totalAndPageComeFromTheSameSnapshotWhenARowIsDeletedConcurrently() {
        List<UUID> before = allIds();
        UUID victim = firstRowId();

        CountDownLatch pageQueryReached = new CountDownLatch(1);
        CountDownLatch writerCommitted = new CountDownLatch(1);
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        dataSource.beforeNextPageQuery(() -> {
            pageQueryReached.countDown();
            awaitOrFail(writerCommitted, "并发删除未在 " + TIMEOUT_SECONDS + " 秒内提交");
        });

        Thread writer = new Thread(() -> {
            try {
                awaitOrFail(pageQueryReached, "分页查询未在 " + TIMEOUT_SECONDS + " 秒内到达");
                // 自动提交：这条 DELETE 在搜索事务之外、独立连接上立即生效
                jdbcClient.sql("DELETE FROM tickets WHERE id = ?").param(1, victim).update();
            }
            catch (Throwable throwable) {
                unexpected.set(throwable);
            }
            finally {
                writerCommitted.countDown();
            }
        }, "flowdesk-concurrent-deleter");
        writer.start();

        TicketSearchResult result = repository.search(criteria());
        joinOrFail(writer);

        assertThat(unexpected.get()).as("并发删除线程不得失败").isNull();

        // 删除对搜索事务不可见：总数仍是 5，且被删掉的那一行仍然出现在明细里
        assertThat(result.totalElements()).isEqualTo(FIXTURE_COUNT);
        assertThat(idsOf(result)).containsExactlyInAnyOrderElementsOf(before);
        assertThat(idsOf(result)).as("被并发删除的行仍属于同一快照").contains(victim);

        // 控制断言：删除确实提交了
        assertThat(countAll()).isEqualTo(FIXTURE_COUNT - 1);
        assertThat(repository.search(criteria()).totalElements()).isEqualTo(FIXTURE_COUNT - 1);
    }

    // ---------- ③ 事务属性 ----------

    @Test
    void searchRunsInAReadOnlyRepeatableReadTransaction() {
        String thread = Thread.currentThread().getName();
        dataSource.clearTrace();

        repository.search(criteria());

        List<String> entries = dataSource.traceFor(thread);
        assertThat(entries).as("搜索事务必须把连接设为只读").contains("setReadOnly(true)");
        assertThat(entries).as("搜索事务必须使用 REPEATABLE_READ")
                .contains("setTransactionIsolation(" + Connection.TRANSACTION_REPEATABLE_READ + ")");
    }

    @Test
    void writeTransactionsKeepDefaultIsolationAndAreNotReadOnly() {
        String thread = Thread.currentThread().getName();

        dataSource.clearTrace();
        repository.insert(newTicket());
        List<String> insertEntries = dataSource.traceFor(thread);
        assertThat(insertEntries).as("写事务不得被改成只读").doesNotContain("setReadOnly(true)");
        assertThat(insertEntries).as("写事务不得被提升隔离级别")
                .doesNotContain("setTransactionIsolation(" + Connection.TRANSACTION_REPEATABLE_READ + ")");

        UUID target = firstRowId();
        dataSource.clearTrace();
        Ticket ticket = repository.findById(TicketId.of(target)).orElseThrow().ticket();
        // 上面刚插入的工单 updated_at 是第 40 天，这里必须取更晚的时刻
        ticket.assign(UserId.of("carol"), BASE.plusSeconds(86_400L * 90));
        repository.update(ticket, 0L);
        List<String> updateEntries = dataSource.traceFor(thread);
        assertThat(updateEntries).doesNotContain("setReadOnly(true)");
        assertThat(updateEntries)
                .doesNotContain("setTransactionIsolation(" + Connection.TRANSACTION_REPEATABLE_READ + ")");

        // 控制断言：写路径确实生效
        assertThat(repository.findById(TicketId.of(target)).orElseThrow().version()).isEqualTo(1L);
    }

    // ---------- 辅助 ----------

    private static TicketSearchCriteria criteria() {
        return new TicketSearchCriteria(0, 100, null, null, null, null, null, null,
                TicketSortField.UPDATED_AT, TicketSortDirection.DESC);
    }

    private static List<UUID> idsOf(TicketSearchResult result) {
        return result.items().stream().map(item -> item.ticket().id().value()).toList();
    }

    private static List<UUID> allIds() {
        return jdbcClient.sql("SELECT id FROM tickets ORDER BY id").query(UUID.class).list();
    }

    private static UUID firstRowId() {
        UUID id = jdbcClient.sql("SELECT id FROM tickets ORDER BY updated_at DESC").query(UUID.class).list()
                .get(0);
        assertThat(id).isNotNull();
        return id;
    }

    private static long countAll() {
        Long count = jdbcClient.sql("SELECT COUNT(*) FROM tickets").query(Long.class).single();
        return count == null ? -1L : count;
    }

    private static Ticket newTicket() {
        return Ticket.create(TicketId.of(UUID.randomUUID()), "并发插入的工单", "由并发线程提交",
                TicketCategory.OTHER, TicketPriority.P4, UserId.of("dave"), BASE.plusSeconds(86_400L * 40));
    }

    private static void insertRow(int index) {
        Instant created = BASE.plusSeconds(86_400L * (index - 1));
        Ticket ticket = Ticket.create(TicketId.of(UUID.randomUUID()), "工单 " + index, "描述 " + index,
                TicketCategory.OTHER, TicketPriority.P3, UserId.of("alice"), created);
        jdbcClient.sql("INSERT INTO tickets (id, title, description, category, priority, requester_id, "
                        + "assignee_id, status, resolution, created_at, updated_at, resolved_at, closed_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, NULL, ?, NULL, ?, ?, NULL, NULL, 0)")
                .param(1, ticket.id().value())
                .param(2, ticket.title())
                .param(3, ticket.description())
                .param(4, ticket.category().name())
                .param(5, ticket.priority().name())
                .param(6, ticket.requesterId().value())
                .param(7, ticket.status().name())
                .param(8, created.atOffset(java.time.ZoneOffset.UTC))
                .param(9, created.atOffset(java.time.ZoneOffset.UTC))
                .update();
    }

    private static void awaitOrFail(CountDownLatch latch, String message) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException(message);
            }
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待被中断：" + message, ex);
        }
    }

    private static void joinOrFail(Thread thread) {
        try {
            thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS * 2));
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待并发线程结束被中断", ex);
        }
        assertThat(thread.isAlive()).as("并发线程必须已结束").isFalse();
    }

}
