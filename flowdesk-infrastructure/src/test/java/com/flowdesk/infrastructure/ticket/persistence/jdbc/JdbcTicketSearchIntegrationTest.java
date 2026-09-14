package com.flowdesk.infrastructure.ticket.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowdesk.application.ticket.port.out.TicketSearchCriteria;
import com.flowdesk.application.ticket.port.out.TicketSearchResult;
import com.flowdesk.application.ticket.port.out.VersionedTicket;
import com.flowdesk.application.ticket.query.TicketSortDirection;
import com.flowdesk.application.ticket.query.TicketSortField;
import com.flowdesk.domain.ticket.Ticket;
import com.flowdesk.domain.ticket.TicketCategory;
import com.flowdesk.domain.ticket.TicketId;
import com.flowdesk.domain.ticket.TicketPriority;
import com.flowdesk.domain.ticket.TicketStatus;
import com.flowdesk.domain.ticket.UserId;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 列表 / 条件搜索的 JDBC 集成测试：真实数据库（H2 的 PostgreSQL 兼容模式）+ 真实 Flyway 迁移。
 *
 * <p>覆盖筛选、AND 组合、关键字包含搜索与大小写不敏感、LIKE 通配符转义、四种排序的
 * 升降序、并列值的 {@code id ASC} 兜底、分页边界与分页元数据、版本正确性，
 * 以及「一次查询只发两条语句」这一 N+1 反向证据。</p>
 *
 * <p>夹具刻意设计成：{@code createdAt} 顺序与 {@code updatedAt} 顺序<b>不同</b>，
 * 且优先级、状态都有并列值 —— 这样「排序字段搞混」和「并列顺序不稳定」都会被抓到。</p>
 */
class JdbcTicketSearchIntegrationTest {

    private static final String MIGRATION_LOCATION = "classpath:db/migration";

    private static final Instant DAY_ONE = Instant.parse("2026-01-01T00:00:00Z");

    /** 统计 {@code prepareStatement} 次数，用来证明一次查询不会变成 N+1。 */
    private static final AtomicInteger STATEMENTS = new AtomicInteger();

    private static MigrateResult migrateResult;

    private static JdbcClient jdbcClient;

    private static JdbcTicketRepository repository;

    /** 标签 → 工单标识，断言时用标签读起来更直观。 */
    private final Map<String, TicketId> ids = new LinkedHashMap<>();

    private final Map<UUID, String> labels = new LinkedHashMap<>();

    @BeforeAll
    static void migrateAnEmptyDatabase() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:flowdesk_search_it"
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        migrateResult = Flyway.configure()
                .dataSource(dataSource)
                .locations(MIGRATION_LOCATION)
                .load()
                .migrate();

        DataSource shared = counting(dataSource);
        jdbcClient = JdbcClient.create(shared);
        repository = new JdbcTicketRepository(jdbcClient, TicketTransactionTemplates.write(shared),
                TicketTransactionTemplates.readOnly(shared));
    }

    /**
     * 夹具（8 条工单）：
     *
     * <pre>
     * 标签 创建(日) 更新(日) 优先级 状态          分类            请求人 处理人
     * A    01      10      P1     NEW           ACCOUNT_ACCESS  alice  —
     * B    02      05      P2     ASSIGNED      NETWORK         bob    bob
     * C    03      03      P3     IN_PROGRESS   HARDWARE        alice  carol
     * D    04      06      P4     RESOLVED      SOFTWARE        dave   bob
     * E    05      07      P2     CLOSED        OTHER           alice  bob
     * F    06      08      P1     NEW           HARDWARE        dave   —
     * G    07      09      P3     NEW           NETWORK         bob    —
     * H    08      11      P4     NEW           SOFTWARE        dave   —
     * </pre>
     *
     * <p>创建顺序 A→H 与更新顺序 C,B,D,E,F,G,A,H 不同，因此排序字段混用必然失败。</p>
     */
    @BeforeEach
    void insertFixtures() {
        jdbcClient.sql("DELETE FROM tickets").update();
        this.ids.clear();
        this.labels.clear();

        insert("A", "无法登录办公系统", "输入正确密码后仍提示认证失败", TicketCategory.ACCOUNT_ACCESS,
                TicketPriority.P1, "alice", TicketStatus.NEW, null, 1, 10);
        insert("B", "VPN 连接超时", "外网网络无法建立隧道", TicketCategory.NETWORK,
                TicketPriority.P2, "bob", TicketStatus.ASSIGNED, "bob", 2, 5);
        insert("C", "三楼打印机离线", "打印机显示离线无法打印", TicketCategory.HARDWARE,
                TicketPriority.P3, "alice", TicketStatus.IN_PROGRESS, "carol", 3, 3);
        insert("D", "邮件客户端闪退", "打开附件时闪退", TicketCategory.SOFTWARE,
                TicketPriority.P4, "dave", TicketStatus.RESOLVED, "bob", 4, 6);
        insert("E", "报表系统权限申请", "需要访问报表系统", TicketCategory.OTHER,
                TicketPriority.P2, "alice", TicketStatus.CLOSED, "bob", 5, 7);
        insert("F", "100% 磁盘占用告警", "服务器磁盘 _full_ 告警", TicketCategory.HARDWARE,
                TicketPriority.P1, "dave", TicketStatus.NEW, null, 6, 8);
        insert("G", "网络抖动", "偶发丢包 !important", TicketCategory.NETWORK,
                TicketPriority.P3, "bob", TicketStatus.NEW, null, 7, 9);
        insert("H", "特殊字符校验", "包含百分号下划线与感叹号 %_!", TicketCategory.SOFTWARE,
                TicketPriority.P4, "dave", TicketStatus.NEW, null, 8, 11);
    }

    // ---------- ① 迁移与索引 ----------

    @Test
    void flywayAppliesAllMigrations() {
        assertThat(migrateResult.migrationsExecuted)
                .as("V1 建表 + V2 搜索索引 + V3 知识文档表 + V4 解析字段与切片表 + V5 索引生命周期字段")
                .isEqualTo(5);
        assertThat(jdbcClient
                .sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'knowledge_documents'")
                .query(Long.class)
                .single())
                .as("V3 建立的知识文档表与工单表共存")
                .isEqualTo(1L);
    }

    @Test
    void searchIndexesExist() {
        List<String> indexes = jdbcClient
                .sql("SELECT index_name FROM information_schema.indexes WHERE table_name = 'tickets'")
                .query(String.class)
                .list();

        assertThat(indexes).contains("idx_tickets_updated_at_id", "idx_tickets_requester_updated_at_id");
    }

    // ---------- ② 无条件分页 ----------

    @Test
    void withoutFiltersReturnsEverythingNewestUpdatedFirst() {
        TicketSearchResult result = search(criteria(0, 20));

        assertThat(result.totalElements()).isEqualTo(8);
        assertThat(labelsOf(result.items())).containsExactly("H", "A", "G", "F", "E", "D", "B", "C");
    }

    @Test
    void defaultSortIsUpdatedAtDescending() {
        TicketSearchResult result = repository.search(new TicketSearchCriteria(0, 20, null, null, null, null,
                null, null, TicketSortField.UPDATED_AT, TicketSortDirection.DESC));

        assertThat(labelsOf(result.items())).containsExactly("H", "A", "G", "F", "E", "D", "B", "C");
    }

    // ---------- ③ 每个筛选条件 ----------

    @Test
    void filtersByStatus() {
        assertThat(labelsOf(search(criteria(0, 20, TicketStatus.NEW, null, null, null, null, null)).items()))
                .containsExactlyInAnyOrder("A", "F", "G", "H");
        assertThat(labelsOf(search(criteria(0, 20, TicketStatus.CLOSED, null, null, null, null, null)).items()))
                .containsExactly("E");
        assertThat(labelsOf(search(criteria(0, 20, TicketStatus.ASSIGNED, null, null, null, null, null)).items()))
                .containsExactly("B");
    }

    @Test
    void filtersByCategory() {
        assertThat(labelsOf(search(criteria(0, 20, null, TicketCategory.NETWORK, null, null, null, null)).items()))
                .containsExactlyInAnyOrder("B", "G");
        assertThat(labelsOf(search(criteria(0, 20, null, TicketCategory.SOFTWARE, null, null, null, null)).items()))
                .containsExactlyInAnyOrder("D", "H");
    }

    @Test
    void filtersByPriority() {
        assertThat(labelsOf(search(criteria(0, 20, null, null, TicketPriority.P1, null, null, null)).items()))
                .containsExactlyInAnyOrder("A", "F");
        assertThat(labelsOf(search(criteria(0, 20, null, null, TicketPriority.P4, null, null, null)).items()))
                .containsExactlyInAnyOrder("D", "H");
    }

    @Test
    void filtersByRequester() {
        assertThat(labelsOf(search(criteria(0, 20, null, null, null, "alice", null, null)).items()))
                .containsExactlyInAnyOrder("A", "C", "E");
        assertThat(search(criteria(0, 20, null, null, null, "nobody", null, null)).items()).isEmpty();
    }

    @Test
    void filtersByAssignee() {
        assertThat(labelsOf(search(criteria(0, 20, null, null, null, null, "bob", null)).items()))
                .containsExactlyInAnyOrder("B", "D", "E");
        assertThat(labelsOf(search(criteria(0, 20, null, null, null, null, "carol", null)).items()))
                .containsExactly("C");
    }

    @Test
    void unassignedTicketsAreNotMatchedByAnAssigneeFilter() {
        // NULL 不等于任何值：不存在"未分配"这个可搜索取值（本阶段不提供该筛选）
        assertThat(search(criteria(0, 20, null, null, null, null, "dave", null)).items()).isEmpty();
    }

    // ---------- ④ 多条件 AND ----------

    @Test
    void combinesFiltersWithAnd() {
        assertThat(labelsOf(search(criteria(0, 20, TicketStatus.NEW, TicketCategory.NETWORK, null, null, null,
                null)).items())).containsExactlyInAnyOrder("G");

        assertThat(labelsOf(search(criteria(0, 20, TicketStatus.NEW, null, TicketPriority.P1, null, null,
                null)).items())).containsExactlyInAnyOrder("A", "F");

        assertThat(labelsOf(search(criteria(0, 20, null, null, null, "alice", "bob", null)).items()))
                .containsExactly("E");
    }

    @Test
    void andCombinationCanProduceAnEmptyResult() {
        // alice 名下没有 NETWORK 分类的工单
        TicketSearchResult result = search(criteria(0, 20, null, TicketCategory.NETWORK, null, "alice", null,
                null));

        assertThat(result.items()).isEmpty();
        assertThat(result.totalElements()).isZero();
    }

    @Test
    void combinesFiltersWithKeywordAndSorting() {
        TicketSearchResult result = repository.search(new TicketSearchCriteria(0, 20, TicketStatus.NEW, null,
                null, "dave", null, "磁盘", TicketSortField.CREATED_AT, TicketSortDirection.ASC));

        assertThat(labelsOf(result.items())).containsExactly("F");
        assertThat(result.totalElements()).isEqualTo(1);
    }

    // ---------- ⑤ 关键字搜索 ----------

    @Test
    void searchesTitleAndDescription() {
        assertThat(labelsOf(search(criteria(0, 20, null, null, null, null, null, "登录")).items()))
                .as("命中标题").containsExactly("A");
        assertThat(labelsOf(search(criteria(0, 20, null, null, null, null, null, "认证")).items()))
                .as("命中描述").containsExactly("A");
        assertThat(labelsOf(search(criteria(0, 20, null, null, null, null, null, "网络")).items()))
                .as("标题与描述都可能命中").containsExactlyInAnyOrder("B", "G");
    }

    @Test
    void keywordSearchIsCaseInsensitive() {
        assertThat(labelsOf(search(criteria(0, 20, null, null, null, null, null, "vpn")).items()))
                .containsExactly("B");
        assertThat(labelsOf(search(criteria(0, 20, null, null, null, null, null, "VPN")).items()))
                .containsExactly("B");
        assertThat(labelsOf(search(criteria(0, 20, null, null, null, null, null, "important")).items()))
                .containsExactly("G");
        assertThat(labelsOf(search(criteria(0, 20, null, null, null, null, null, "IMPORTANT")).items()))
                .containsExactly("G");
        assertThat(labelsOf(search(criteria(0, 20, null, null, null, null, null, "Full")).items()))
                .as("描述里是 _full_，大小写不敏感后应命中 F").containsExactly("F");
    }

    @Test
    void percentIsSearchedAsALiteralCharacter() {
        // 若 % 被当成通配符，这条查询会匹配全部 8 条
        TicketSearchResult result = search(criteria(0, 20, null, null, null, null, null, "%"));

        assertThat(labelsOf(result.items())).containsExactlyInAnyOrder("F", "H");
        assertThat(result.totalElements()).isEqualTo(2);
    }

    @Test
    void underscoreIsSearchedAsALiteralCharacter() {
        // 若 _ 被当成通配符，它会匹配任意单个字符，从而命中远多于 2 条
        TicketSearchResult result = search(criteria(0, 20, null, null, null, null, null, "_"));

        assertThat(labelsOf(result.items())).containsExactlyInAnyOrder("F", "H");
        assertThat(result.totalElements()).isEqualTo(2);
    }

    @Test
    void exclamationMarkIsSearchedAsALiteralCharacter() {
        // ! 是转义符：必须以 "!!" 绑定。若漏了转义，"%!%" 会被解释成"包含字面百分号"，
        // 命中集合会变成 F、H，而不是 G、H
        TicketSearchResult result = search(criteria(0, 20, null, null, null, null, null, "!"));

        assertThat(labelsOf(result.items())).containsExactlyInAnyOrder("G", "H");
        assertThat(result.totalElements()).isEqualTo(2);
    }

    @Test
    void wildcardSequenceIsSearchedAsALiteralSequence() {
        TicketSearchResult result = search(criteria(0, 20, null, null, null, null, null, "%_!"));

        assertThat(labelsOf(result.items())).as("只有描述里真的含有 %_! 的工单").containsExactly("H");
        assertThat(result.totalElements()).isEqualTo(1);
    }

    @Test
    void maliciousKeywordCannotChangeTheQuerySemantics() {
        // 关键字是绑定的，注入尝试只会变成"查不到"
        TicketSearchResult injected = search(criteria(0, 20, null, null, null, null, null,
                "' OR 1=1 --"));

        assertThat(injected.totalElements()).isZero();
        assertThat(scalarLong("SELECT COUNT(*) FROM tickets")).as("表必须完好").isEqualTo(8);
    }

    @Test
    void keywordWithSingleQuoteStillWorksAsLiteralSearch() {
        assertThat(search(criteria(0, 20, null, null, null, null, null, "'")).totalElements()).isZero();
    }

    @Test
    void keywordMatchingNothingReturnsEmptyPage() {
        TicketSearchResult result = search(criteria(0, 20, null, null, null, null, null, "不存在的关键字"));

        assertThat(result.items()).isEmpty();
        assertThat(result.totalElements()).isZero();
    }

    // ---------- ⑥ 排序 ----------

    @Test
    void sortsByCreatedAtInBothDirections() {
        assertThat(labelsOf(sorted(TicketSortField.CREATED_AT, TicketSortDirection.ASC)))
                .containsExactly("A", "B", "C", "D", "E", "F", "G", "H");
        assertThat(labelsOf(sorted(TicketSortField.CREATED_AT, TicketSortDirection.DESC)))
                .containsExactly("H", "G", "F", "E", "D", "C", "B", "A");
    }

    @Test
    void sortsByUpdatedAtInBothDirections() {
        assertThat(labelsOf(sorted(TicketSortField.UPDATED_AT, TicketSortDirection.ASC)))
                .containsExactly("C", "B", "D", "E", "F", "G", "A", "H");
        assertThat(labelsOf(sorted(TicketSortField.UPDATED_AT, TicketSortDirection.DESC)))
                .containsExactly("H", "A", "G", "F", "E", "D", "B", "C");
    }

    @Test
    void sortsByPriorityInBusinessOrder() {
        // P1 < P2 < P3 < P4；并列值内部按 id 升序
        assertThat(labelsOf(sorted(TicketSortField.PRIORITY, TicketSortDirection.ASC)))
                .containsExactly("A", "F", "B", "E", "C", "G", "D", "H");
        assertThat(labelsOf(sorted(TicketSortField.PRIORITY, TicketSortDirection.DESC)))
                .containsExactly("D", "H", "C", "G", "B", "E", "A", "F");
    }

    @Test
    void sortsByStatusInLifecycleOrder() {
        // 字典序会是 ASSIGNED,CLOSED,IN_PROGRESS,NEW,RESOLVED —— 与本断言完全不同
        assertThat(labelsOf(sorted(TicketSortField.STATUS, TicketSortDirection.ASC)))
                .containsExactly("A", "F", "G", "H", "B", "C", "D", "E");
        assertThat(labelsOf(sorted(TicketSortField.STATUS, TicketSortDirection.DESC)))
                .containsExactly("E", "D", "C", "B", "A", "F", "G", "H");
    }

    @Test
    void equalPrimarySortValuesFallBackToAscendingId() {
        // 按 status 排序且只取 NEW：四条工单的主排序键完全相同，顺序只能由 id ASC 决定
        List<UUID> newStatusIds = search(new TicketSearchCriteria(0, 20, TicketStatus.NEW, null, null, null,
                null, null, TicketSortField.STATUS, TicketSortDirection.ASC))
                .items().stream().map(item -> item.ticket().id().value()).toList();
        assertThat(newStatusIds).hasSize(4).isSortedAccordingTo(JdbcTicketSearchIntegrationTest::compareIds);

        // 按 priority 排序且只取 P3：C 与 G 并列
        List<UUID> p3Ids = search(new TicketSearchCriteria(0, 20, null, null, TicketPriority.P3, null, null,
                null, TicketSortField.PRIORITY, TicketSortDirection.ASC))
                .items().stream().map(item -> item.ticket().id().value()).toList();
        assertThat(p3Ids).hasSize(2).isSortedAccordingTo(JdbcTicketSearchIntegrationTest::compareIds);
    }

    @Test
    void tieBreakMakesPagingOverTiesLossless() {
        // 按状态排序时并列极多；逐页翻完必须不重不漏
        List<String> collected = new ArrayList<>();
        for (int page = 0; page < 4; page++) {
            collected.addAll(labelsOf(search(new TicketSearchCriteria(page, 2, null, null, null, null, null,
                    null, TicketSortField.STATUS, TicketSortDirection.ASC)).items()));
        }

        assertThat(collected).containsExactly("A", "F", "G", "H", "B", "C", "D", "E");
        assertThat(collected).doesNotHaveDuplicates();
    }

    // ---------- ⑦ 分页边界与元数据 ----------

    @Test
    void returnsFirstMiddleAndLastPage() {
        assertThat(labelsOf(page(0, 3).items())).containsExactly("H", "A", "G");
        assertThat(labelsOf(page(1, 3).items())).containsExactly("F", "E", "D");
        assertThat(labelsOf(page(2, 3).items())).containsExactly("B", "C");
    }

    @Test
    void totalElementsIsTheCountOfTheWholeFilteredSet() {
        // 总数必须与分页无关：每一页拿到的都是同一个 8
        for (int page = 0; page < 4; page++) {
            TicketSearchResult result = page(page, 3);
            assertThat(result.totalElements()).as("第 %d 页", page).isEqualTo(8);
        }
    }

    @Test
    void pageBeyondTheLastOneIsEmptyButStillCounted() {
        TicketSearchResult result = page(9, 3);

        assertThat(result.items()).isEmpty();
        assertThat(result.totalElements()).isEqualTo(8);
    }

    @Test
    void offsetIsAppliedFromTheSecondPageOnwards() {
        assertThat(labelsOf(page(2, 3).items())).containsExactly("B", "C");
        assertThat(labelsOf(page(3, 3).items())).isEmpty();
    }

    @Test
    void respectsTheRequestedPageSize() {
        assertThat(page(0, 1).items()).hasSize(1);
        assertThat(page(0, 100).items()).hasSize(8);
    }

    @Test
    void filteringAndPagingCompose() {
        TicketSearchResult result = search(new TicketSearchCriteria(1, 2, TicketStatus.NEW, null, null, null,
                null, null, TicketSortField.CREATED_AT, TicketSortDirection.ASC));

        // NEW = A,F,G,H（按创建时间 A,F,G,H），第 2 页（size=2）是 G,H
        assertThat(labelsOf(result.items())).containsExactly("G", "H");
        assertThat(result.totalElements()).isEqualTo(4);
    }

    // ---------- ⑧ 版本与隔离性 ----------

    @Test
    void returnsTheCurrentVersionOfEveryRow() {
        Instant later = day(30);
        TicketId target = this.ids.get("A");
        Ticket ticket = repository.findById(target).orElseThrow().ticket();
        ticket.assign(UserId.of("zoe"), later);
        repository.update(ticket, 0L);
        Ticket updated = repository.findById(target).orElseThrow().ticket();
        updated.start(later.plusSeconds(60));
        repository.update(updated, 1L);

        TicketSearchResult result = search(criteria(0, 20));

        VersionedTicket rowA = result.items().stream()
                .filter(item -> item.ticket().id().equals(target))
                .findFirst()
                .orElseThrow();
        assertThat(rowA.version()).as("列表里的版本必须是当前版本").isEqualTo(2L);
        assertThat(result.items()).filteredOn(item -> !item.ticket().id().equals(target))
                .allSatisfy(item -> assertThat(item.version()).isZero());
    }

    @Test
    void everyRowIsAnIndependentlyRestoredAggregate() {
        TicketSearchResult first = search(criteria(0, 20));
        TicketSearchResult second = search(criteria(0, 20));

        VersionedTicket rowA = first.items().get(0);
        VersionedTicket otherA = second.items().get(0);

        assertThat(rowA.ticket()).isNotSameAs(otherA.ticket());
        // 恢复出来的聚合是独立对象：改动它不会影响后续查询
        rowA.ticket().assign(UserId.of("mutated"), day(30));
        assertThat(search(criteria(0, 20)).items().get(0).ticket().assigneeId()).isEmpty();
    }

    // ---------- ⑨ 无 N+1 ----------

    @Test
    void oneSearchIssuesExactlyTwoStatements() {
        STATEMENTS.set(0);

        TicketSearchResult result = search(criteria(0, 20));

        assertThat(result.items()).hasSize(8);
        assertThat(STATEMENTS.get()).as("一条 COUNT + 一条分页查询，不得按行再查").isEqualTo(2);
    }

    @Test
    void emptyResultIssuesOnlyTheCount() {
        STATEMENTS.set(0);

        TicketSearchResult result = search(criteria(0, 20, null, null, null, "nobody", null, null));

        assertThat(result.items()).isEmpty();
        assertThat(STATEMENTS.get()).as("总数为 0 时不必再发分页查询").isEqualTo(1);
    }

    @Test
    void largePageSizeStillIssuesTwoStatements() {
        STATEMENTS.set(0);

        search(criteria(0, 100));

        assertThat(STATEMENTS.get()).isEqualTo(2);
    }

    // ---------- 辅助 ----------

    private TicketSearchResult search(TicketSearchCriteria criteria) {
        return repository.search(criteria);
    }

    private TicketSearchResult page(int page, int size) {
        return search(criteria(page, size));
    }

    private List<VersionedTicket> sorted(TicketSortField field, TicketSortDirection direction) {
        return search(new TicketSearchCriteria(0, 100, null, null, null, null, null, null, field, direction))
                .items();
    }

    /** 标签顺序；断言里用标签，失败信息比 UUID 易读得多。 */
    private List<String> labelsOf(List<VersionedTicket> items) {
        return items.stream().map(item -> this.labels.get(item.ticket().id().value())).toList();
    }

    private static TicketSearchCriteria criteria(int page, int size) {
        return new TicketSearchCriteria(page, size, null, null, null, null, null, null,
                TicketSortField.UPDATED_AT, TicketSortDirection.DESC);
    }

    private static TicketSearchCriteria criteria(int page, int size, TicketStatus status,
            TicketCategory category, TicketPriority priority, String requesterId, String assigneeId,
            String keyword) {

        return new TicketSearchCriteria(page, size, status, category, priority, requesterId, assigneeId,
                keyword, TicketSortField.UPDATED_AT, TicketSortDirection.DESC);
    }

    /**
     * 插入一条夹具工单。
     *
     * <p>用 {@link Ticket#restore} 而不是「create + 状态流转」来构造：只有 restore 能同时指定
     * {@code createdAt} 与 {@code updatedAt} —— 新建工单的这两个时间必然相同，
     * 而本测试恰恰需要「创建顺序与更新顺序不一致」的夹具。restore 本身会校验时间链与状态字段组合，
     * 因此夹具仍然是领域合法的（也顺带验证了恢复路径对合法快照的接受）。</p>
     *
     * <p>标识按标签顺序递增（{@code 00000000-0000-0000-0000-00000000000n}），
     * 这样 {@code id ASC} 的顺序就是标签顺序，便于断言并列值的兜底行为。</p>
     */
    private void insert(String label, String title, String description, TicketCategory category,
            TicketPriority priority, String requesterId, TicketStatus status, String assigneeId,
            int createdDay, int updatedDay) {

        TicketId id = TicketId.of(UUID.fromString(String.format("00000000-0000-0000-0000-%012d",
                this.ids.size() + 1)));
        Instant created = day(createdDay);
        Instant updated = day(updatedDay);

        boolean resolved = status == TicketStatus.RESOLVED || status == TicketStatus.CLOSED;
        Instant resolvedAt = resolved ? updated : null;
        Instant closedAt = status == TicketStatus.CLOSED ? updated : null;
        String resolution = resolved ? "已处理：" + label : null;
        UserId assignee = assigneeId == null ? null : UserId.of(assigneeId);

        Ticket ticket = Ticket.restore(id, title, description, category, priority, UserId.of(requesterId),
                assignee, status, resolution, created, updated, resolvedAt, closedAt);

        repository.insert(ticket);
        this.ids.put(label, id);
        this.labels.put(id.value(), label);
    }

    private static Instant day(int dayOfJanuary) {
        return DAY_ONE.plusSeconds(86_400L * (dayOfJanuary - 1));
    }

    private static long scalarLong(String sql) {
        Long value = jdbcClient.sql(sql).query(Long.class).single();
        return value == null ? -1L : value;
    }

    /** 与数据库 {@code id ASC} 一致的比较：先比高位再比低位（两者都是无符号语义下的等价顺序）。 */
    private static int compareIds(UUID left, UUID right) {
        return left.toString().compareTo(right.toString());
    }

    /**
     * 计数数据源：包装连接以统计 {@code prepareStatement} 次数。
     */
    private static DataSource counting(DataSource delegate) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[] { DataSource.class },
                (proxy, method, args) -> {
                    Object result = invoke(delegate, method, args);
                    if (result instanceof Connection connection && "getConnection".equals(method.getName())) {
                        return counting(connection);
                    }
                    return result;
                });
    }

    private static Connection counting(Connection delegate) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] { Connection.class },
                (proxy, method, args) -> {
                    // 同时统计两种执行方式：带参数的语句走 prepareStatement，
                    // 无参数语句可能走 createStatement，两者都算一次数据库往返
                    if ("prepareStatement".equals(method.getName()) || "createStatement".equals(method.getName())) {
                        STATEMENTS.incrementAndGet();
                    }
                    return invoke(delegate, method, args);
                });
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        }
        catch (InvocationTargetException ex) {
            throw ex.getCause();
        }
    }
}
