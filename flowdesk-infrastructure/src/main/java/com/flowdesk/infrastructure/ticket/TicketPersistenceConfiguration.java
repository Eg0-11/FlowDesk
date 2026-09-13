package com.flowdesk.infrastructure.ticket;

import com.flowdesk.application.ticket.port.out.TicketIdGenerator;
import com.flowdesk.application.ticket.port.out.TicketRepository;
import com.flowdesk.application.ticket.port.out.TimeProvider;
import com.flowdesk.infrastructure.ticket.persistence.jdbc.JdbcTicketRepository;
import com.flowdesk.infrastructure.ticket.support.SystemTimeProvider;
import com.flowdesk.infrastructure.ticket.support.UuidTicketIdGenerator;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 工单持久化的 Spring 装配。
 *
 * <p>把应用层定义的三类输出端口落实为具体实现：</p>
 * <ul>
 *   <li>{@link TicketRepository} → {@link JdbcTicketRepository}（Spring JDBC + 显式 SQL）；</li>
 *   <li>{@link TicketIdGenerator} → {@link UuidTicketIdGenerator}；</li>
 *   <li>{@link TimeProvider} → {@link SystemTimeProvider}（注入 {@link Clock}，截断到微秒）。</li>
 * </ul>
 *
 * <p>应用层与领域层<b>不含任何 Spring 注解</b>；装配全部集中在基础设施层与 bootstrap 层。</p>
 *
 * <p>本类暴露<b>两个</b>事务模板：写路径使用默认（读写、默认隔离级别），
 * 列表查询使用只读 + {@code REPEATABLE_READ}，以保证 COUNT 与分页查询处于同一快照。
 * 两者刻意分开，避免把写事务一并提升隔离级别。</p>
 */
@Configuration(proxyBeanMethods = false)
public class TicketPersistenceConfiguration {

    /**
     * 系统时钟。统一使用 UTC，避免夏令时与本地时区带来的歧义。
     *
     * @return UTC 时钟
     */
    @Bean
    public Clock flowdeskClock() {
        return Clock.systemUTC();
    }

    /**
     * @param clock UTC 时钟
     * @return 截断到微秒的时间端口
     */
    @Bean
    public TimeProvider ticketTimeProvider(Clock clock) {
        return new SystemTimeProvider(clock);
    }

    /**
     * @return UUID 工单标识生成器
     */
    @Bean
    public TicketIdGenerator ticketIdGenerator() {
        return new UuidTicketIdGenerator();
    }

    /**
     * @param dataSource 数据源
     * @return Spring JDBC 客户端
     */
    @Bean
    public JdbcClient jdbcClient(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    /**
     * 写路径的事务模板：compare-and-set 需要显式事务边界。
     *
     * <p><b>刻意保持默认隔离级别</b>（{@code READ_COMMITTED}）与读写语义：
     * 乐观并发依赖的是「行锁 + 条件更新」这一对显式 SQL（ADR 0002），
     * 把写事务一起提到可重复读只会增加锁竞争与序列化失败，并不会让 CAS 更正确。</p>
     *
     * @param transactionManager Spring Boot 自动配置的数据源事务管理器
     * @return 读写事务操作模板
     */
    @Bean
    public TransactionOperations ticketTransactionOperations(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    /**
     * 列表查询专用的事务模板：<b>只读 + {@code REPEATABLE_READ}</b>。
     *
     * <p>列表接口要发两条 SELECT（COUNT 与分页查询），它们必须来自同一个数据库快照，
     * 否则会出现「总数 5 却返回 6 行」这种自相矛盾的响应。默认的 {@code READ_COMMITTED}
     * <b>不足以</b>保证这一点 —— PostgreSQL 在 READ COMMITTED 下每条语句各取一个新快照，
     * 同一事务里的两条 SELECT 仍可能看到不同数据。</p>
     *
     * <p>只读、不使用行锁，因此不会阻塞并发写入；写路径继续使用上面那个模板，
     * 两者互不影响。</p>
     *
     * @param transactionManager Spring Boot 自动配置的数据源事务管理器
     * @return 只读、可重复读的事务操作模板
     */
    @Bean
    public TransactionOperations ticketReadOnlyTransactionOperations(
            PlatformTransactionManager transactionManager) {

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return template;
    }

    /**
     * @param jdbcClient                  JDBC 客户端
     * @param transactions                写事务模板（读写、默认隔离级别）
     * @param readOnlyTransactions        列表查询事务模板（只读、可重复读）
     * @return 工单存储适配器
     */
    @Bean
    public TicketRepository ticketRepository(JdbcClient jdbcClient,
            @Qualifier("ticketTransactionOperations") TransactionOperations transactions,
            @Qualifier("ticketReadOnlyTransactionOperations") TransactionOperations readOnlyTransactions) {

        return new JdbcTicketRepository(jdbcClient, transactions, readOnlyTransactions);
    }
}
