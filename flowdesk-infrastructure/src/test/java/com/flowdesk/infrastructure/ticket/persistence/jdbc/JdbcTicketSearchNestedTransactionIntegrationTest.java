package com.flowdesk.infrastructure.ticket.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * <b>嵌套调用</b>下的列表查询事务边界集成测试。
 *
 * <p>要证明的核心事实：列表查询用的是 <b>{@code REQUIRES_NEW}</b> 的独立事务，
 * 即使被调用时已经处在一个默认读写的外层事务里，它仍然：</p>
 * <ol>
 *   <li>用<b>另一条数据库连接</b>开启<b>一个新事务</b>（外层事务被挂起）；</li>
 *   <li>在新事务里观察到 {@code readOnly=true} 与 {@code TRANSACTION_REPEATABLE_READ}；</li>
 *   <li>看不到外层事务尚未提交的写入（这是「另一个事务」最直接的证据）；</li>
 *   <li>结束后外层事务被<b>恢复</b>：绑定回原来那条连接，并且仍然有效、可以继续执行 SQL；</li>
 *   <li>内外边界清晰：外层回滚不会牵连任何人，外层提交也不会吞掉搜索的独立事务。</li>
 * </ol>
 *
 * <p>所有断言都基于<b>真实连接上可观测的事实</b>（连接 id、连接设置调用、可见性），
 * 而不是断言事务模板的配置字段。并发用例不使用 {@code sleep}，所有等待都有超时。</p>
 */
class JdbcTicketSearchNestedTransactionIntegrationTest {

    private static final String MIGRATION_LOCATION = "classpath:db/migration";

    private static final Instant BASE = Instant.parse("2026-04-01T00:00:00Z");

    private static final long TIMEOUT_SECONDS = 20L;

    private static final int FIXTURE_COUNT = 5;

    /** 外层事务专用的标记：作为标题值写入，用来识别外层那行未提交的数据。 */
    private static final String OUTER_MARKER = "__outer_marker__";

    /**
     * 外层事务专用 SQL 的标记别名：用于在事件流里认出「外层那条连接」。
     *
     * <p>注意不能用标题值来找：标题是<b>绑定参数</b>，不会出现在 SQL 文本里。</p>
     */
    private static final String OUTER_SQL_TAG = "outer_marker_count";

    private static InterceptingDataSource dataSource;

    private static JdbcClient jdbcClient;

    private static JdbcTicketRepository repository;

    /** 外层事务：与生产一致——读写、默认传播、默认隔离级别。 */
    private static TransactionTemplate outerTransactions;

    @BeforeAll
    static void migrateAnEmptyDatabase() {
        JdbcDataSource raw = new JdbcDataSource();
        raw.setURL("jdbc:h2:mem:flowdesk_search_nested_it"
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=10000");
        raw.setUser("sa");
        raw.setPassword("");

        Flyway.configure().dataSource(raw).locations(MIGRATION_LOCATION).load().migrate();

        dataSource = new InterceptingDataSource(raw);
        jdbcClient = JdbcClient.create(dataSource);
        outerTransactions = TicketTransactionTemplates.write(dataSource);
        repository = new JdbcTicketRepository(jdbcClient, outerTransactions,
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

    // ---------- ① 嵌套：另一条连接 + 新事务 + 外层恢复 ----------

    @Test
    void nestedSearchUsesItsOwnConnectionAndRestoresTheOuterTransaction() {
        dataSource.resetConnectionCount();
        dataSource.clearTrace();
        AtomicReference<TicketSearchResult> searchResult = new AtomicReference<>();
        AtomicReference<Connection> boundBefore = new AtomicReference<>();
        AtomicReference<Connection> boundAfter = new AtomicReference<>();
        AtomicReference<Long> outerVisibleAfterSearch = new AtomicReference<>();

        outerTransactions.execute(status -> {
            // 先在外层事务里执行 SQL：这条语句会把外层连接真正绑定到当前线程
            long before = countMatchingMarker();
            assertThat(before).isZero();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            boundBefore.set(DataSourceUtils.getConnection(dataSource));

            // 外层写入一行但**不提交**
            insertMarkerRow();

            // 在已有事务里调用列表查询
            searchResult.set(repository.search(criteria()));

            boundAfter.set(DataSourceUtils.getConnection(dataSource));
            // 外层事务必须被恢复且仍然可用：它能看到自己未提交的写入
            outerVisibleAfterSearch.set(countMatchingMarker());
            return null;
        });

        TicketSearchResult result = searchResult.get();
        assertThat(result).isNotNull();

        // ① 搜索看不到外层未提交的写入 → 它在另一个事务里
        assertThat(result.totalElements()).isEqualTo(FIXTURE_COUNT);
        assertThat(result.items()).hasSize(FIXTURE_COUNT);

        // ② 外层事务被恢复：绑定回同一条连接，且仍处于活动状态
        assertThat(boundAfter.get()).as("外层必须绑定回原来那条连接").isSameAs(boundBefore.get());
        assertThat(outerVisibleAfterSearch.get()).as("外层事务恢复后仍能看到自己的未提交写入")
                .isEqualTo(1L);

        // ③ 嵌套调用期间确实开了第二条连接
        assertThat(dataSource.connectionsOpened()).as("嵌套调用需要额外一条连接")
                .isGreaterThanOrEqualTo(2);

        // ④ 搜索事务用自己的连接，并且在该连接上设置了只读与可重复读
        List<Integer> readOnlyConnections = dataSource.connectionIdsSettingReadOnly(true);
        List<Integer> repeatableReadConnections = dataSource.connectionIdsSettingIsolation(
                Connection.TRANSACTION_REPEATABLE_READ);
        assertThat(readOnlyConnections).as("恰好一条连接被设为只读").hasSize(1);
        assertThat(repeatableReadConnections).as("恰好一条连接被设为可重复读").hasSize(1);
        assertThat(readOnlyConnections.get(0)).isEqualTo(repeatableReadConnections.get(0));

        // ⑤ 外层连接与搜索连接不是同一条
        Integer outerConnectionId = dataSource.connectionIdRunning(OUTER_SQL_TAG);
        assertThat(outerConnectionId).as("必须能在事件流里认出外层连接").isNotNull();
        assertThat(readOnlyConnections.get(0)).as("搜索必须使用另一条连接")
                .isNotEqualTo(outerConnectionId);
        assertThat(dataSource.hasEvent(outerConnectionId, "setReadOnly(true)"))
                .as("外层连接不得被设成只读").isFalse();
        assertThat(dataSource.hasEvent(outerConnectionId,
                "setTransactionIsolation(" + Connection.TRANSACTION_REPEATABLE_READ + ")"))
                .as("外层连接不得被提升隔离级别").isFalse();

        // ⑥ COUNT 与分页查询都在搜索那条连接上
        int searchConnectionId = readOnlyConnections.get(0);
        assertThat(dataSource.hasStatementWithSql(searchConnectionId, "SELECT COUNT(*) FROM tickets"))
                .as("COUNT 必须跑在搜索连接上").isTrue();
        assertThat(dataSource.connectionIdRunning("ORDER BY updated_at DESC, id ASC LIMIT"))
                .as("分页查询必须跑在搜索连接上").isEqualTo(searchConnectionId);

        // ⑦ 控制断言：外层提交后，那行标记工单确实存在
        assertThat(countMatchingMarker()).isEqualTo(1L);
        assertThat(countAll()).isEqualTo(FIXTURE_COUNT + 1);
    }

    @Test
    void outerTransactionRemainsUsableAfterNestedSearch() {
        long outerWrites = outerTransactions.execute(status -> {
            insertMarkerRow();
            TicketSearchResult inner = repository.search(criteria());
            assertThat(inner.totalElements()).isEqualTo(FIXTURE_COUNT);

            // 搜索返回后，外层事务继续执行 SQL：既读自己的未提交写入，也继续写
            insertMarkerRow();
            return countMatchingMarker();
        });

        assertThat(outerWrites).as("外层事务在嵌套搜索之后仍能继续读写").isEqualTo(2L);
        assertThat(countAll()).as("外层提交后两行都在").isEqualTo(FIXTURE_COUNT + 2);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .as("外层事务已结束，线程上不应残留绑定").isFalse();
    }

    // ---------- ② 外层回滚不混淆内外边界 ----------

    @Test
    void outerRollbackDoesNotConfuseTheInnerTransactionBoundary() {
        AtomicReference<TicketSearchResult> innerResult = new AtomicReference<>();

        assertThatThrownBy(() -> outerTransactions.execute(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            insertMarkerRow();
            // 内层搜索：独立事务，正常完成
            innerResult.set(repository.search(criteria()));
            assertThat(innerResult.get().totalElements()).isEqualTo(FIXTURE_COUNT);
            // 外层主动回滚
            throw new IllegalStateException("回滚外层事务");
        })).isInstanceOf(IllegalStateException.class);

        // 外层回滚：它自己的写入不存在了
        assertThat(countMatchingMarker()).as("外层回滚后标记行必须消失").isZero();
        assertThat(countAll()).isEqualTo(FIXTURE_COUNT);

        // 内层搜索的结果仍然有效（它来自自己那个已结束的独立事务），没有被外层回滚牵连
        assertThat(innerResult.get()).isNotNull();
        assertThat(innerResult.get().totalElements()).isEqualTo(FIXTURE_COUNT);
        assertThat(innerResult.get().items()).hasSize(FIXTURE_COUNT);

        // 事务边界没有混淆：没有残留的活动事务，后续查询照常工作
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(repository.search(criteria()).totalElements()).isEqualTo(FIXTURE_COUNT);
    }

    // ---------- ③ 嵌套 + 并发写入：快照一致性 ----------

    @Test
    void nestedSearchStillSeesOneSnapshotWhenARowIsInsertedConcurrently() {
        List<UUID> before = allIds();
        AtomicReference<TicketSearchResult> innerResult = new AtomicReference<>();

        CountDownLatch pageQueryReached = new CountDownLatch(1);
        CountDownLatch writerCommitted = new CountDownLatch(1);
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        // 并发写入精确卡在「COUNT 已完成、分页查询尚未下发」之间
        dataSource.beforeNextPageQuery(() -> {
            pageQueryReached.countDown();
            awaitOrFail(writerCommitted, "并发插入未在 " + TIMEOUT_SECONDS + " 秒内提交");
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
        }, "flowdesk-nested-writer");
        writer.start();

        // 整个搜索发生在一条已经开启的外层事务之内
        outerTransactions.execute(status -> {
            insertMarkerRow();
            assertThat(countMatchingMarker()).isEqualTo(1L);
            innerResult.set(repository.search(criteria()));
            // 嵌套搜索结束后外层仍然有效
            assertThat(countMatchingMarker()).isEqualTo(1L);
            return null;
        });

        joinOrFail(writer);

        assertThat(unexpected.get()).isNull();
        TicketSearchResult result = innerResult.get();
        assertThat(result.totalElements()).as("总数来自 COUNT 那一刻的快照").isEqualTo(FIXTURE_COUNT);
        assertThat(idsOf(result)).containsExactlyInAnyOrderElementsOf(before);

        // 控制断言：并发插入确实提交了（外层已提交，其标记行也应在）
        assertThat(countAll()).isEqualTo(FIXTURE_COUNT + 2);
        assertThat(countMatchingMarker()).isEqualTo(1L);
    }

    @Test
    void nestedSearchStillSeesOneSnapshotWhenARowIsDeletedConcurrently() {
        List<UUID> before = allIds();
        UUID victim = firstRowId();
        AtomicReference<TicketSearchResult> innerResult = new AtomicReference<>();

        CountDownLatch pageQueryReached = new CountDownLatch(1);
        CountDownLatch writerCommitted = new CountDownLatch(1);
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        dataSource.beforeNextPageQuery(() -> {
            pageQueryReached.countDown();
            awaitOrFail(writerCommitted, "并发删除未在 " + TIMEOUT_SECONDS + " 秒内提交");
        });

        Thread deleter = new Thread(() -> {
            try {
                awaitOrFail(pageQueryReached, "分页查询未在 " + TIMEOUT_SECONDS + " 秒内到达");
                jdbcClient.sql("DELETE FROM tickets WHERE id = ?").param(1, victim).update();
            }
            catch (Throwable throwable) {
                unexpected.set(throwable);
            }
            finally {
                writerCommitted.countDown();
            }
        }, "flowdesk-nested-deleter");
        deleter.start();

        outerTransactions.execute(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            innerResult.set(repository.search(criteria()));
            return null;
        });

        joinOrFail(deleter);

        assertThat(unexpected.get()).isNull();
        TicketSearchResult result = innerResult.get();
        assertThat(result.totalElements()).isEqualTo(FIXTURE_COUNT);
        assertThat(idsOf(result)).containsExactlyInAnyOrderElementsOf(before);
        assertThat(idsOf(result)).as("被并发删除的行仍属于同一快照").contains(victim);

        // 控制断言：删除确实提交了
        assertThat(countAll()).isEqualTo(FIXTURE_COUNT - 1);
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
        return jdbcClient.sql("SELECT id FROM tickets ORDER BY updated_at DESC").query(UUID.class).list().get(0);
    }

    private static long countAll() {
        Long count = jdbcClient.sql("SELECT COUNT(*) FROM tickets").query(Long.class).single();
        return count == null ? -1L : count;
    }

    /** 只统计外层标记行：用于证明「外层能看到自己未提交的写入」。 */
    private static long countMatchingMarker() {
        Long count = jdbcClient.sql("SELECT COUNT(*) AS " + OUTER_SQL_TAG + " FROM tickets WHERE title = ?")
                .param(1, OUTER_MARKER)
                .query(Long.class)
                .single();
        return count == null ? -1L : count;
    }

    /**
     * 在外层事务内写入一行标记工单（标题里带 {@link #OUTER_MARKER}，便于在事件流与查询里识别）。
     */
    private static void insertMarkerRow() {
        Instant now = BASE.plusSeconds(86_400L * 50);
        jdbcClient.sql("INSERT INTO tickets (id, title, description, category, priority, requester_id, "
                        + "assignee_id, status, resolution, created_at, updated_at, resolved_at, closed_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, NULL, ?, NULL, ?, ?, NULL, NULL, 0)")
                .param(1, UUID.randomUUID())
                .param(2, OUTER_MARKER)
                .param(3, "外层事务未提交的写入")
                .param(4, TicketCategory.OTHER.name())
                .param(5, TicketPriority.P3.name())
                .param(6, "outer")
                .param(7, "NEW")
                .param(8, offset(now))
                .param(9, offset(now))
                .update();
    }

    private static Ticket newTicket() {
        return Ticket.create(TicketId.of(UUID.randomUUID()), "并发插入的工单", "由并发线程提交",
                TicketCategory.OTHER, TicketPriority.P4, UserId.of("dave"), BASE.plusSeconds(86_400L * 60));
    }

    private static void insertRow(int index) {
        Instant created = BASE.plusSeconds(86_400L * (index - 1));
        jdbcClient.sql("INSERT INTO tickets (id, title, description, category, priority, requester_id, "
                        + "assignee_id, status, resolution, created_at, updated_at, resolved_at, closed_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, NULL, ?, NULL, ?, ?, NULL, NULL, 0)")
                .param(1, UUID.randomUUID())
                .param(2, "工单 " + index)
                .param(3, "描述 " + index)
                .param(4, TicketCategory.OTHER.name())
                .param(5, TicketPriority.P3.name())
                .param(6, "alice")
                .param(7, "NEW")
                .param(8, offset(created))
                .param(9, offset(created))
                .update();
    }

    private static OffsetDateTime offset(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
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
