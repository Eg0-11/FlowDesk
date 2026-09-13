/**
 * FlowDesk 工单 JDBC 持久化包：显式 SQL + Spring JDBC {@code JdbcClient}。
 *
 * <p>不使用 JPA/Hibernate/MyBatis。乐观锁由「行锁 + 条件更新」两个显式 SQL 步骤在同一事务内完成，
 * 见 {@link com.flowdesk.infrastructure.ticket.persistence.jdbc.JdbcTicketRepository} 与
 * {@code docs/adr/0002-ticket-persistence-spring-jdbc.md}。</p>
 *
 * <p>SQL 全部集中在 {@code TicketSql}（固定语句）与 {@code TicketSearchSql}（列表查询的动态部分），
 * 行映射集中在 {@code TicketRowMapper}，LIKE 转义集中在 {@code LikePattern}，
 * 它们都是实现细节，不对外暴露。</p>
 *
 * <p>列表查询的动态 SQL 只由程序常量拼装：筛选值是占位符，排序来自枚举白名单，
 * 不存在把调用方文本拼进语句结构的路径。见
 * {@code docs/adr/0004-ticket-search-pagination.md}。</p>
 */
package com.flowdesk.infrastructure.ticket.persistence.jdbc;
