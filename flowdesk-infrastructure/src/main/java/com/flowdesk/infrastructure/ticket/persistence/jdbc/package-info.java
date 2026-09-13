/**
 * FlowDesk 工单 JDBC 持久化包：显式 SQL + Spring JDBC {@code JdbcClient}。
 *
 * <p>不使用 JPA/Hibernate/MyBatis。乐观锁由「行锁 + 条件更新」两个显式 SQL 步骤在同一事务内完成，
 * 见 {@link com.flowdesk.infrastructure.ticket.persistence.jdbc.JdbcTicketRepository} 与
 * {@code docs/adr/0002-ticket-persistence-spring-jdbc.md}。</p>
 *
 * <p>SQL 全部集中在 {@code TicketSql}，行映射集中在 {@code TicketRowMapper}，
 * 两者都是实现细节，不对外暴露。</p>
 */
package com.flowdesk.infrastructure.ticket.persistence.jdbc;
