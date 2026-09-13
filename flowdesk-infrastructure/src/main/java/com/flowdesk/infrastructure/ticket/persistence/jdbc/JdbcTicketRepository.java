package com.flowdesk.infrastructure.ticket.persistence.jdbc;

import com.flowdesk.application.ticket.TicketApplicationErrorCode;
import com.flowdesk.application.ticket.TicketApplicationException;
import com.flowdesk.application.ticket.port.out.TicketRepository;
import com.flowdesk.application.ticket.port.out.TicketSearchCriteria;
import com.flowdesk.application.ticket.port.out.TicketSearchResult;
import com.flowdesk.application.ticket.port.out.VersionedTicket;
import com.flowdesk.domain.ticket.Ticket;
import com.flowdesk.domain.ticket.TicketId;
import com.flowdesk.domain.ticket.UserId;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 基于 Spring JDBC {@link JdbcClient} 的工单存储适配器。
 *
 * <p>不使用 JPA/Hibernate：乐观锁依赖显式 SQL 表达，见
 * {@code docs/adr/0002-ticket-persistence-spring-jdbc.md}。</p>
 *
 * <h2>读取</h2>
 * <p>{@link #findById} 全程参数绑定，每次都用 {@link Ticket#restore} 构造新聚合，
 * 不缓存也不返回共享可变对象；不存在时返回 {@link Optional#empty()}。</p>
 *
 * <h2>插入</h2>
 * <p>版本固定写 0；数据库主键唯一约束是并发插入的最终防线。
 * 只把<b>主键重复</b>（{@link DuplicateKeyException}）映射为
 * {@link TicketApplicationErrorCode#TICKET_ALREADY_EXISTS}，
 * 其它约束异常（CHECK 违反等）原样抛出，绝不被误判成"重复工单"。</p>
 *
 * <h2>更新（compare-and-set）</h2>
 * <p>三步必须在同一个事务里完成：</p>
 * <ol>
 *   <li>{@code SELECT version FROM tickets WHERE id = ? FOR UPDATE} 锁定目标行 ——
 *       行不存在抛 {@code TICKET_NOT_FOUND}；</li>
 *   <li>当前版本与 {@code expectedVersion} 不一致抛 {@code TICKET_VERSION_CONFLICT}，
 *       此时不产生任何写入；</li>
 *   <li>{@code UPDATE ... WHERE id = ? AND version = ?} 影响行数必须严格等于 1，
 *       并把 {@code version} 加 1；随后在同一事务内重新读取并返回独立聚合。</li>
 * </ol>
 * <p>行锁 + 条件更新共同保证：读取后到写入前发生并发写入时，只有一个调用能成功，
 * 另一个必然拿到 {@code TICKET_VERSION_CONFLICT}，且不会产生部分写入（事务回滚）。</p>
 *
 * <p>本类不记录日志，因此不会把标题、描述、处理结论或数据库连接信息写入日志。</p>
 */
public final class JdbcTicketRepository implements TicketRepository {

    private final JdbcClient jdbcClient;

    private final TransactionOperations transactions;

    /**
     * @param jdbcClient   Spring JDBC 客户端
     * @param transactions 事务操作模板；compare-and-set 的三个步骤必须处于同一事务
     */
    public JdbcTicketRepository(JdbcClient jdbcClient, TransactionOperations transactions) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient 不能为 null");
        this.transactions = Objects.requireNonNull(transactions, "transactions 不能为 null");
    }

    @Override
    public Optional<VersionedTicket> findById(TicketId ticketId) {
        Objects.requireNonNull(ticketId, "ticketId 不能为 null");

        return this.jdbcClient.sql(TicketSql.SELECT_BY_ID)
                .param(1, ticketId.value())
                .query(TicketRowMapper::mapRow)
                .optional();
    }

    @Override
    public VersionedTicket insert(Ticket ticket) {
        Objects.requireNonNull(ticket, "ticket 不能为 null");

        return this.transactions.execute(status -> {
            try {
                this.jdbcClient.sql(TicketSql.INSERT)
                        .param(1, ticket.id().value())
                        .param(2, ticket.title())
                        .param(3, ticket.description())
                        .param(4, ticket.category().name())
                        .param(5, ticket.priority().name())
                        .param(6, ticket.requesterId().value())
                        .param(7, ticket.assigneeId().map(UserId::value).orElse(null), Types.VARCHAR)
                        .param(8, ticket.status().name())
                        .param(9, ticket.resolution().orElse(null), Types.VARCHAR)
                        .param(10, toOffsetDateTime(ticket.createdAt()))
                        .param(11, toOffsetDateTime(ticket.updatedAt()))
                        .param(12, toOffsetDateTime(ticket.resolvedAt().orElse(null)),
                                Types.TIMESTAMP_WITH_TIMEZONE)
                        .param(13, toOffsetDateTime(ticket.closedAt().orElse(null)),
                                Types.TIMESTAMP_WITH_TIMEZONE)
                        .update();
            }
            catch (DuplicateKeyException ex) {
                // 仅主键重复映射为"已存在"，其它完整性约束异常继续向上抛出
                throw new TicketApplicationException(TicketApplicationErrorCode.TICKET_ALREADY_EXISTS,
                        "工单标识已存在");
            }
            return requireExisting(ticket.id());
        });
    }

    @Override
    public VersionedTicket update(Ticket ticket, long expectedVersion) {
        Objects.requireNonNull(ticket, "ticket 不能为 null");

        return this.transactions.execute(status -> compareAndSet(ticket, expectedVersion));
    }

    /**
     * 锁定 → 比对版本 → 条件更新 → 重新读取，全部在同一事务内。
     */
    private VersionedTicket compareAndSet(Ticket ticket, long expectedVersion) {
        TicketId ticketId = ticket.id();

        Long currentVersion = this.jdbcClient.sql(TicketSql.SELECT_VERSION_FOR_UPDATE)
                .param(1, ticketId.value())
                .query(Long.class)
                .optional()
                .orElse(null);
        if (currentVersion == null) {
            throw new TicketApplicationException(TicketApplicationErrorCode.TICKET_NOT_FOUND, "工单不存在");
        }
        if (currentVersion != expectedVersion) {
            throw new TicketApplicationException(TicketApplicationErrorCode.TICKET_VERSION_CONFLICT,
                    "工单版本不匹配");
        }

        int affected = this.jdbcClient.sql(TicketSql.UPDATE)
                .param(1, ticket.title())
                .param(2, ticket.description())
                .param(3, ticket.category().name())
                .param(4, ticket.priority().name())
                .param(5, ticket.requesterId().value())
                .param(6, ticket.assigneeId().map(UserId::value).orElse(null), Types.VARCHAR)
                .param(7, ticket.status().name())
                .param(8, ticket.resolution().orElse(null), Types.VARCHAR)
                .param(9, toOffsetDateTime(ticket.createdAt()))
                .param(10, toOffsetDateTime(ticket.updatedAt()))
                .param(11, toOffsetDateTime(ticket.resolvedAt().orElse(null)), Types.TIMESTAMP_WITH_TIMEZONE)
                .param(12, toOffsetDateTime(ticket.closedAt().orElse(null)), Types.TIMESTAMP_WITH_TIMEZONE)
                .param(13, ticketId.value())
                .param(14, expectedVersion)
                .update();

        if (affected != 1) {
            // 行锁已保证不会走到这里；若真发生，说明写入不完整，按版本冲突处理并回滚
            throw new TicketApplicationException(TicketApplicationErrorCode.TICKET_VERSION_CONFLICT,
                    "并发更新冲突");
        }
        return requireExisting(ticketId);
    }

    private VersionedTicket requireExisting(TicketId ticketId) {
        return findById(ticketId)
                .orElseThrow(() -> new TicketApplicationException(TicketApplicationErrorCode.TICKET_NOT_FOUND,
                        "工单不存在"));
    }

    /**
     * 分页 / 条件搜索：一条 COUNT + 一条分页查询，绝不按行再查（无 N+1）。
     *
     * <p>SQL 由 {@link TicketSearchSql} 从<b>程序常量</b>拼装：筛选值是 {@code ?} 占位符，
     * 排序来自枚举白名单，因此调用方文本无法进入语句结构。
     * {@code LIMIT}/{@code OFFSET} 同样绑定，偏移量用 {@code long} 计算。</p>
     *
     * <p>总数为 0 时直接返回空页，不再发起第二次查询（此时分页查询必然返回空，
     * 白白多一次数据库往返）。</p>
     *
     * <p>读到的每一行都经 {@link TicketRowMapper} 走 {@code Ticket.restore}，
     * 与 {@link #findById} 的隔离性完全一致；不做任何缓存，也不复用聚合实例。</p>
     */
    @Override
    public TicketSearchResult search(TicketSearchCriteria criteria) {
        Objects.requireNonNull(criteria, "criteria 不能为 null");

        List<Object> filterParameters = TicketSearchSql.filterParameters(criteria);

        Long totalElements = this.jdbcClient.sql(TicketSearchSql.countSql(criteria))
                .params(filterParameters)
                .query(Long.class)
                .single();
        long total = totalElements == null ? 0L : totalElements;
        if (total == 0L) {
            return new TicketSearchResult(List.of(), 0L);
        }

        List<VersionedTicket> items = this.jdbcClient.sql(TicketSearchSql.pageSql(criteria))
                .params(filterParameters)
                .param(criteria.size())
                .param(criteria.offset())
                .query(TicketRowMapper::mapRow)
                .list();

        return new TicketSearchResult(items, total);
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
