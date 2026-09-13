package com.flowdesk.infrastructure.ticket.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowdesk.application.ticket.TicketApplicationErrorCode;
import com.flowdesk.application.ticket.TicketApplicationException;
import com.flowdesk.application.ticket.port.out.VersionedTicket;
import com.flowdesk.domain.ticket.Ticket;
import com.flowdesk.domain.ticket.TicketCategory;
import com.flowdesk.domain.ticket.TicketId;
import com.flowdesk.domain.ticket.TicketPriority;
import com.flowdesk.domain.ticket.TicketStatus;
import com.flowdesk.domain.ticket.UserId;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JDBC 工单存储集成测试：真实数据库（H2 的 PostgreSQL 兼容模式）+ 真实 Flyway 迁移 + 真实并发线程。
 *
 * <p>不做任何模拟：每次读写都经 JDBC 落到数据库，既验证 SQL 语义，也验证数据库层的约束与行锁。
 * 并发用例由两个真实线程驱动、各取独立连接，不使用 FD-0004 的人工竞争模拟。</p>
 *
 * <p>H2 在这里承担 <b>H2_INTEGRATION</b> 角色；真实 PostgreSQL 的验证状态在交付报告中单独说明。</p>
 */
class JdbcTicketRepositoryIntegrationTest {

    private static final String MIGRATION_LOCATION = "classpath:db/migration";

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    private static final AtomicInteger CONNECTIONS = new AtomicInteger();

    private static JdbcDataSource dataSource;

    private static JdbcClient jdbcClient;

    private static JdbcTicketRepository repository;

    private static MigrateResult migrateResult;

    @BeforeAll
    static void migrateAnEmptyDatabase() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:flowdesk_repository_it"
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=10000");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        migrateResult = Flyway.configure()
                .dataSource(dataSource)
                .locations(MIGRATION_LOCATION)
                .load()
                .migrate();

        // 关键：JdbcClient 与事务管理器必须共用同一个 DataSource 对象，否则 DataSourceUtils 的
        // 事务绑定键不同，JdbcClient 会另开连接、根本不在事务内 —— 那样这份测试就验证不到
        // 「SELECT FOR UPDATE 与条件 UPDATE 处于同一事务」。生产装配中两者也共用同一个 DataSource。
        DataSource sharedDataSource = counting(dataSource);
        jdbcClient = JdbcClient.create(sharedDataSource);
        TransactionTemplate transactions =
                new TransactionTemplate(new DataSourceTransactionManager(sharedDataSource));
        repository = new JdbcTicketRepository(jdbcClient, transactions);
    }

    @BeforeEach
    void clearTickets() {
        jdbcClient.sql("DELETE FROM tickets").update();
    }

    // ---------- ① 迁移 ----------

    @Test
    void flywayMigratesAnEmptyDatabase() {
        assertThat(migrateResult.migrationsExecuted).as("V1 建表 + V2 搜索索引").isEqualTo(2);
        assertThat(scalarLong("SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'tickets'"))
                .as("tickets 表必须存在")
                .isEqualTo(1);
    }

    @Test
    void createsTheExpectedIndexes() {
        List<String> indexes = jdbcClient
                .sql("SELECT index_name FROM information_schema.indexes WHERE table_name = 'tickets'")
                .query(String.class)
                .list();

        assertThat(indexes).contains("idx_tickets_status_updated_at", "idx_tickets_assignee_status",
                "idx_tickets_created_at");
        // V2（FD-0007）为列表默认排序与"我提交的工单"筛选补的索引
        assertThat(indexes).contains("idx_tickets_updated_at_id", "idx_tickets_requester_updated_at_id");
    }

    // ---------- ②③④ 插入与往返 ----------

    @Test
    void insertWritesVersionZero() {
        TicketId id = randomTicketId();

        VersionedTicket inserted = repository.insert(ticketInStatus(id, TicketStatus.NEW));

        assertThat(inserted.version()).isZero();
        assertThat(scalarLong("SELECT version FROM tickets")).isZero();
    }

    @Test
    void roundTripsEveryFieldInEveryStatus() {
        for (TicketStatus status : TicketStatus.values()) {
            TicketId id = randomTicketId();
            Ticket original = ticketInStatus(id, status);

            repository.insert(original);
            Ticket loaded = repository.findById(id).orElseThrow().ticket();

            assertThat(loaded.id()).isEqualTo(id);
            assertThat(loaded.status()).isEqualTo(status);
            assertThat(loaded.title()).isEqualTo("标题");
            assertThat(loaded.description()).isEqualTo("描述");
            assertThat(loaded.category()).isEqualTo(TicketCategory.NETWORK);
            assertThat(loaded.priority()).isEqualTo(TicketPriority.P1);
            assertThat(loaded.requesterId()).isEqualTo(UserId.of("alice"));
            assertThat(loaded.assigneeId()).isEqualTo(original.assigneeId());
            assertThat(loaded.resolution()).isEqualTo(original.resolution());
            assertThat(loaded.createdAt()).isEqualTo(original.createdAt());
            assertThat(loaded.updatedAt()).isEqualTo(original.updatedAt());
            assertThat(loaded.resolvedAt()).isEqualTo(original.resolvedAt());
            assertThat(loaded.closedAt()).isEqualTo(original.closedAt());
        }
    }

    @Test
    void roundTripsMicrosecondTimestampsWithoutLoss() {
        Instant createdAt = Instant.parse("2026-01-01T00:00:00.123456Z");
        TicketId id = randomTicketId();
        repository.insert(Ticket.create(id, "标题", "描述", TicketCategory.HARDWARE, TicketPriority.P3,
                UserId.of("alice"), createdAt));

        Ticket loaded = repository.findById(id).orElseThrow().ticket();
        assertThat(loaded.createdAt()).as("创建时间必须保留微秒").isEqualTo(createdAt);
        assertThat(loaded.updatedAt()).isEqualTo(createdAt);

        // 更新到另一个带微秒的时刻，确认写回后精度不变
        Instant updatedAt = Instant.parse("2026-01-01T00:00:01.654321Z");
        loaded.assign(UserId.of("bob"), updatedAt);
        repository.update(loaded, 0L);

        Instant reread = repository.findById(id).orElseThrow().ticket().updatedAt();
        assertThat(reread).isEqualTo(updatedAt);
        assertThat(reread.getNano() % 1000).as("必须精确到微秒、无纳秒残留").isZero();
    }

    @Test
    void findByIdReturnsEmptyOptionalForUnknownTicket() {
        Optional<VersionedTicket> found = repository.findById(randomTicketId());

        assertThat(found).isNotNull();
        assertThat(found).isEmpty();
    }

    @Test
    void insertRejectsADuplicateId() {
        TicketId id = randomTicketId();
        repository.insert(ticketInStatus(id, TicketStatus.NEW));

        assertApplicationError(() -> repository.insert(ticketInStatus(id, TicketStatus.NEW)),
                TicketApplicationErrorCode.TICKET_ALREADY_EXISTS);

        assertThat(scalarLong("SELECT COUNT(*) FROM tickets")).isEqualTo(1);
    }

    // ---------- ⑦⑧⑨ compare-and-set ----------

    @Test
    void updateIncrementsVersionByExactlyOne() {
        TicketId id = randomTicketId();
        repository.insert(ticketInStatus(id, TicketStatus.NEW));

        Ticket ticket = repository.findById(id).orElseThrow().ticket();
        ticket.assign(UserId.of("bob"), BASE.plusSeconds(60));

        VersionedTicket updated = repository.update(ticket, 0L);

        assertThat(updated.version()).isEqualTo(1);
        assertThat(updated.ticket().status()).isEqualTo(TicketStatus.ASSIGNED);
        assertThat(scalarLong("SELECT version FROM tickets")).isEqualTo(1);
    }

    @Test
    void staleVersionIsRejectedAndLeavesDataUnchanged() {
        TicketId id = randomTicketId();
        repository.insert(ticketInStatus(id, TicketStatus.NEW));
        Ticket ticket = repository.findById(id).orElseThrow().ticket();
        ticket.assign(UserId.of("bob"), BASE.plusSeconds(60));
        repository.update(ticket, 0L);

        Ticket stale = repository.findById(id).orElseThrow().ticket();
        stale.start(BASE.plusSeconds(120));

        assertApplicationError(() -> repository.update(stale, 0L),
                TicketApplicationErrorCode.TICKET_VERSION_CONFLICT);

        assertThat(scalarLong("SELECT version FROM tickets")).isEqualTo(1);
        assertThat(repository.findById(id).orElseThrow().ticket().status())
                .as("版本冲突不得产生任何写入")
                .isEqualTo(TicketStatus.ASSIGNED);
    }

    @Test
    void updateUnknownTicketIsNotFound() {
        Ticket orphan = Ticket.create(randomTicketId(), "标题", "描述", TicketCategory.OTHER, TicketPriority.P4,
                UserId.of("alice"), BASE);

        assertApplicationError(() -> repository.update(orphan, 0L), TicketApplicationErrorCode.TICKET_NOT_FOUND);
    }

    // ---------- ⑩⑪⑫ 隔离语义 ----------

    @Test
    void findByIdReturnsDistinctTicketInstances() {
        TicketId id = randomTicketId();
        repository.insert(ticketInStatus(id, TicketStatus.NEW));

        Ticket first = repository.findById(id).orElseThrow().ticket();
        Ticket second = repository.findById(id).orElseThrow().ticket();

        assertThat(first).isNotSameAs(second);
        assertThat(first).isEqualTo(second);
    }

    @Test
    void mutatingAReadResultDoesNotAffectTheDatabase() {
        TicketId id = randomTicketId();
        repository.insert(ticketInStatus(id, TicketStatus.NEW));

        Ticket loaded = repository.findById(id).orElseThrow().ticket();
        loaded.assign(UserId.of("carol"), BASE.plusSeconds(60));
        loaded.start(BASE.plusSeconds(120));

        Ticket reread = repository.findById(id).orElseThrow().ticket();
        assertThat(reread.status()).isEqualTo(TicketStatus.NEW);
        assertThat(reread.assigneeId()).isEmpty();
        assertThat(scalarLong("SELECT version FROM tickets")).isZero();
    }

    @Test
    void mutatingTheWrittenObjectAfterUpdateDoesNotAffectTheDatabase() {
        TicketId id = randomTicketId();
        repository.insert(ticketInStatus(id, TicketStatus.NEW));

        Ticket ticket = repository.findById(id).orElseThrow().ticket();
        ticket.assign(UserId.of("bob"), BASE.plusSeconds(60));
        repository.update(ticket, 0L);

        // 调用方继续修改同一个实例
        ticket.start(BASE.plusSeconds(120));
        ticket.resolve("已处理", BASE.plusSeconds(180));

        Ticket reread = repository.findById(id).orElseThrow().ticket();
        assertThat(reread.status()).as("数据库必须停在 update 时刻的快照").isEqualTo(TicketStatus.ASSIGNED);
        assertThat(reread.resolution()).isEmpty();
        assertThat(scalarLong("SELECT version FROM tickets")).isEqualTo(1);
    }

    // ---------- ⑬⑭ 数据库约束 ----------

    @Test
    void databaseRejectsIllegalStatusAndFieldCombinations() {
        // NEW 带处理人
        assertDatabaseRejects(() -> insertRaw("NEW", "bob", null, null, null));
        // ASSIGNED 缺处理人
        assertDatabaseRejects(() -> insertRaw("ASSIGNED", null, null, null, null));
        // ASSIGNED 带结论
        assertDatabaseRejects(() -> insertRaw("ASSIGNED", "bob", "已处理", null, null));
        // RESOLVED 缺结论
        assertDatabaseRejects(() -> insertRaw("RESOLVED", "bob", null, BASE.plusSeconds(10), null));
        // RESOLVED 带关闭时间
        assertDatabaseRejects(() -> insertRaw("RESOLVED", "bob", "已处理", BASE.plusSeconds(10),
                BASE.plusSeconds(20)));
        // CLOSED 缺关闭时间
        assertDatabaseRejects(() -> insertRaw("CLOSED", "bob", "已处理", BASE.plusSeconds(10), null));
        // NEW 带结论
        assertDatabaseRejects(() -> insertRaw("NEW", null, "已处理", null, null));
    }

    @Test
    void databaseRejectsInvalidTimeline() {
        // 解决时间早于创建时间
        assertDatabaseRejects(() -> insertRaw("RESOLVED", "bob", "已处理", BASE.minusSeconds(1), null));
        // 关闭时间早于解决时间
        assertDatabaseRejects(() -> insertRaw("CLOSED", "bob", "已处理", BASE.plusSeconds(20),
                BASE.plusSeconds(10)));
        // 解决时间晚于更新时间
        assertDatabaseRejects(() -> insertRaw("RESOLVED", "bob", "已处理", BASE.plusSeconds(60), null));
        // 关闭时间晚于更新时间
        assertDatabaseRejects(() -> insertRaw("CLOSED", "bob", "已处理", BASE.plusSeconds(10),
                BASE.plusSeconds(60)));
    }

    @Test
    void databaseRejectsUnknownEnumValues() {
        assertDatabaseRejects(() -> insertRawWithCategory("NOT_A_CATEGORY"));
        assertDatabaseRejects(() -> insertRawWithPriority("P9"));
        assertDatabaseRejects(() -> insertRawWithStatus("BOGUS"));
    }

    @Test
    void databaseRejectsNegativeVersion() {
        UUID id = randomUuid();
        insertRawWithCategory("OTHER");

        UUID freeId = randomUuid();
        jdbcClient.sql("INSERT INTO tickets (id, title, description, category, priority, requester_id, status, "
                        + "created_at, updated_at, version) VALUES (?, '标题', '描述', 'OTHER', 'P4', 'alice', "
                        + "'NEW', ?, ?, 0)")
                .param(1, freeId)
                .param(2, offset(BASE))
                .param(3, offset(BASE))
                .update();

        assertThat(id).isNotNull();
        assertDatabaseRejects(() -> jdbcClient.sql("UPDATE tickets SET version = -1 WHERE id = ?")
                .param(1, freeId)
                .update());
    }

    @Test
    void databaseRejectsBlankAndOverlongText() {
        // 空字符串与纯空格（SQL 标准的 TRIM 只去除空格）
        assertDatabaseRejects(() -> insertRawWithTitle(""));
        assertDatabaseRejects(() -> insertRawWithTitle("   "));
        // 超出领域上限的长度
        assertDatabaseRejects(() -> insertRawWithTitle("t".repeat(201)));
        assertDatabaseRejects(() -> insertRawWithDescription("d".repeat(4001)));
    }

    // ---------- ⑮⑯ 真实并发 ----------

    @Test
    void concurrentUpdatesAllowExactlyOneWinner() throws Exception {
        TicketId id = randomTicketId();
        repository.insert(ticketInStatus(id, TicketStatus.NEW));

        int connectionsBefore = CONNECTIONS.get();
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        // 两阶段同步：两个线程都先把 version 0 的独立快照读出来并改好，
        // 在栅栏处汇合之后，才同时发起 update —— 这样竞争的只有 CAS 本身。
        // 栅栏带超时，不会无限等待。
        CyclicBarrier bothSnapshotsReady = new CyclicBarrier(2);

        runConcurrently(() -> {
            Ticket snapshot = repository.findById(id).orElseThrow().ticket();
            snapshot.assign(UserId.of("bob"), BASE.plusSeconds(60));
            if (!awaitBarrier(bothSnapshotsReady, unexpected)) {
                return;
            }
            try {
                repository.update(snapshot, 0L);
                successes.incrementAndGet();
            }
            catch (TicketApplicationException ex) {
                if (ex.errorCode() == TicketApplicationErrorCode.TICKET_VERSION_CONFLICT) {
                    conflicts.incrementAndGet();
                }
                else {
                    unexpected.set(ex);
                }
            }
        });

        assertThat(CONNECTIONS.get() - connectionsBefore)
                .as("并发更新必须使用至少两个数据库连接")
                .isGreaterThanOrEqualTo(2);
        assertThat(unexpected.get()).isNull();
        assertThat(successes.get()).as("成功数必须恰好为 1").isEqualTo(1);
        assertThat(conflicts.get()).as("版本冲突必须恰好为 1").isEqualTo(1);
        assertThat(scalarLong("SELECT version FROM tickets")).as("最终版本必须为 1").isEqualTo(1);
        assertThat(repository.findById(id).orElseThrow().ticket().status())
                .as("最终状态必须为 ASSIGNED")
                .isEqualTo(TicketStatus.ASSIGNED);
    }

    @Test
    void concurrentInsertsAllowExactlyOneWinner() throws Exception {
        TicketId id = randomTicketId();

        int connectionsBefore = CONNECTIONS.get();
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger alreadyExists = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        // 两个线程各自构造好同 ID 的工单后在栅栏汇合，再同时插入
        CyclicBarrier bothTicketsReady = new CyclicBarrier(2);

        runConcurrently(() -> {
            Ticket candidate = ticketInStatus(id, TicketStatus.NEW);
            if (!awaitBarrier(bothTicketsReady, unexpected)) {
                return;
            }
            try {
                repository.insert(candidate);
                successes.incrementAndGet();
            }
            catch (TicketApplicationException ex) {
                if (ex.errorCode() == TicketApplicationErrorCode.TICKET_ALREADY_EXISTS) {
                    alreadyExists.incrementAndGet();
                }
                else {
                    unexpected.set(ex);
                }
            }
        });

        assertThat(CONNECTIONS.get() - connectionsBefore)
                .as("并发插入必须使用至少两个数据库连接")
                .isGreaterThanOrEqualTo(2);
        assertThat(unexpected.get()).isNull();
        assertThat(successes.get()).as("成功数必须恰好为 1").isEqualTo(1);
        assertThat(alreadyExists.get()).as("已存在必须恰好为 1").isEqualTo(1);
        assertThat(scalarLong("SELECT COUNT(*) FROM tickets")).isEqualTo(1);
    }

    // ---------- 夹具与辅助 ----------

    private static Ticket ticketInStatus(TicketId id, TicketStatus status) {
        UserId assignee = status == TicketStatus.NEW ? null : UserId.of("bob");
        String resolution = switch (status) {
            case NEW, ASSIGNED, IN_PROGRESS -> null;
            case RESOLVED, CLOSED -> "已处理";
        };
        Instant resolvedAt = switch (status) {
            case NEW, ASSIGNED, IN_PROGRESS -> null;
            case RESOLVED, CLOSED -> BASE.plusSeconds(10);
        };
        Instant closedAt = status == TicketStatus.CLOSED ? BASE.plusSeconds(20) : null;
        Instant updatedAt = closedAt != null ? closedAt : (resolvedAt != null ? resolvedAt : BASE);

        return Ticket.restore(id, "标题", "描述", TicketCategory.NETWORK, TicketPriority.P1, UserId.of("alice"),
                assignee, status, resolution, BASE, updatedAt, resolvedAt, closedAt);
    }

    private void insertRaw(String status, String assigneeId, String resolution, Instant resolvedAt, Instant closedAt) {
        jdbcClient.sql("INSERT INTO tickets (id, title, description, category, priority, requester_id, assignee_id, "
                        + "status, resolution, created_at, updated_at, resolved_at, closed_at, version) "
                        + "VALUES (?, '标题', '描述', 'OTHER', 'P4', 'alice', ?, ?, ?, ?, ?, ?, ?, 0)")
                .param(1, randomUuid())
                .param(2, assigneeId, Types.VARCHAR)
                .param(3, status)
                .param(4, resolution, Types.VARCHAR)
                .param(5, offset(BASE))
                .param(6, offset(BASE.plusSeconds(30)))
                .param(7, resolvedAt == null ? null : offset(resolvedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .param(8, closedAt == null ? null : offset(closedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    private void insertRawWithCategory(String category) {
        insertRawColumn("category", category);
    }

    private void insertRawWithPriority(String priority) {
        insertRawColumn("priority", priority);
    }

    private void insertRawWithStatus(String status) {
        insertRawColumn("status", status);
    }

    private void insertRawWithTitle(String title) {
        jdbcClient.sql("INSERT INTO tickets (id, title, description, category, priority, requester_id, status, "
                        + "created_at, updated_at, version) VALUES (?, ?, '描述', 'OTHER', 'P4', 'alice', 'NEW', ?, ?, 0)")
                .param(1, randomUuid())
                .param(2, title)
                .param(3, offset(BASE))
                .param(4, offset(BASE))
                .update();
    }

    private void insertRawWithDescription(String description) {
        jdbcClient.sql("INSERT INTO tickets (id, title, description, category, priority, requester_id, status, "
                        + "created_at, updated_at, version) VALUES (?, '标题', ?, 'OTHER', 'P4', 'alice', 'NEW', ?, ?, 0)")
                .param(1, randomUuid())
                .param(2, description)
                .param(3, offset(BASE))
                .param(4, offset(BASE))
                .update();
    }

    private void insertRawColumn(String column, String value) {
        String category = "category".equals(column) ? value : "OTHER";
        String priority = "priority".equals(column) ? value : "P4";
        String status = "status".equals(column) ? value : "NEW";

        jdbcClient.sql("INSERT INTO tickets (id, title, description, category, priority, requester_id, status, "
                        + "created_at, updated_at, version) VALUES (?, '标题', '描述', ?, ?, 'alice', ?, ?, ?, 0)")
                .param(1, randomUuid())
                .param(2, category)
                .param(3, priority)
                .param(4, status)
                .param(5, offset(BASE))
                .param(6, offset(BASE))
                .update();
    }

    private static void runConcurrently(Runnable task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            Future<?> first = pool.submit(() -> awaitAndRun(startGate, task));
            Future<?> second = pool.submit(() -> awaitAndRun(startGate, task));
            startGate.countDown();
            first.get(60, TimeUnit.SECONDS);
            second.get(60, TimeUnit.SECONDS);
        }
        finally {
            pool.shutdownNow();
        }
    }

    private static void awaitAndRun(CountDownLatch startGate, Runnable task) {
        try {
            startGate.await();
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
        task.run();
    }

    /**
     * 在栅栏处等待另一个线程，带超时；失败时把原因记进 {@code unexpected} 而不是无限等待。
     */
    private static boolean awaitBarrier(CyclicBarrier barrier, AtomicReference<Throwable> unexpected) {
        try {
            barrier.await(20, TimeUnit.SECONDS);
            return true;
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            unexpected.compareAndSet(null, ex);
            return false;
        }
        catch (BrokenBarrierException | TimeoutException ex) {
            unexpected.compareAndSet(null, ex);
            return false;
        }
    }

    private static void assertDatabaseRejects(ThrowingCallable callable) {
        assertThatThrownBy(callable).isInstanceOf(DataIntegrityViolationException.class);
    }

    private static void assertApplicationError(ThrowingCallable callable, TicketApplicationErrorCode expected) {
        assertThatThrownBy(callable)
                .isInstanceOf(TicketApplicationException.class)
                .extracting(thrown -> ((TicketApplicationException) thrown).errorCode())
                .isEqualTo(expected);
    }

    private static long scalarLong(String sql) {
        Long value = jdbcClient.sql(sql).query(Long.class).single();
        return value == null ? -1L : value;
    }

    private static OffsetDateTime offset(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static UUID randomUuid() {
        return UUID.randomUUID();
    }

    private static TicketId randomTicketId() {
        return TicketId.of(randomUuid());
    }

    /**
     * 包一层统计 {@code getConnection} 调用次数的数据源，用来证明并发用例确实使用了多个连接。
     */
    private static DataSource counting(DataSource delegate) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[] { DataSource.class },
                (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) {
                        CONNECTIONS.incrementAndGet();
                    }
                    try {
                        return method.invoke(delegate, args);
                    }
                    catch (InvocationTargetException ex) {
                        throw ex.getCause();
                    }
                });
    }
}
