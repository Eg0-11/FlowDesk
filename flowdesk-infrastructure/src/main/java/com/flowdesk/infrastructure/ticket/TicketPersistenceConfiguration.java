package com.flowdesk.infrastructure.ticket;

import com.flowdesk.application.ticket.port.out.TicketIdGenerator;
import com.flowdesk.application.ticket.port.out.TicketRepository;
import com.flowdesk.application.ticket.port.out.TimeProvider;
import com.flowdesk.infrastructure.ticket.persistence.jdbc.JdbcTicketRepository;
import com.flowdesk.infrastructure.ticket.support.SystemTimeProvider;
import com.flowdesk.infrastructure.ticket.support.UuidTicketIdGenerator;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
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
     * compare-and-set 需要显式事务边界，这里暴露一个可注入的事务操作模板。
     *
     * @param transactionManager Spring Boot 自动配置的数据源事务管理器
     * @return 事务操作模板
     */
    @Bean
    public TransactionOperations ticketTransactionOperations(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    /**
     * @param jdbcClient   JDBC 客户端
     * @param transactions 事务操作模板
     * @return 工单存储适配器
     */
    @Bean
    public TicketRepository ticketRepository(JdbcClient jdbcClient, TransactionOperations transactions) {
        return new JdbcTicketRepository(jdbcClient, transactions);
    }
}
