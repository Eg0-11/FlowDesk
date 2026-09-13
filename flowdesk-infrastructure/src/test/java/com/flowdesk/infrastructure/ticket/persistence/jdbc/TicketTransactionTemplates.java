package com.flowdesk.infrastructure.ticket.persistence.jdbc;

import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 测试用的两个事务模板：与 {@code TicketPersistenceConfiguration} 的装配保持一致。
 *
 * <p>刻意抽出来共享，避免每个集成测试各写一份隔离级别 —— 一旦有人把列表查询的
 * 「只读 + 可重复读」改掉，这里也会一起改，测试不会因为「测试自己另配了一套」而继续通过。</p>
 */
final class TicketTransactionTemplates {

    private TicketTransactionTemplates() {
    }

    /**
     * 写路径：读写、默认隔离级别（与生产一致）。
     */
    static TransactionTemplate write(DataSource dataSource) {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    /**
     * 列表查询路径：只读 + {@code REPEATABLE_READ}（与生产一致）。
     */
    static TransactionTemplate readOnly(DataSource dataSource) {
        TransactionTemplate template = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        template.setReadOnly(true);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return template;
    }
}
