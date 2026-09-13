/**
 * FlowDesk 工单基础设施包：把工单应用层的输出端口落实为具体实现。
 *
 * <p>当前包含 Spring JDBC 存储适配器（{@code persistence.jdbc}）与时间、标识实现
 * （{@code support}），装配入口为 {@link com.flowdesk.infrastructure.ticket.TicketPersistenceConfiguration}。</p>
 *
 * <p>本包允许依赖 Spring；应用层与领域层则完全不含 Spring。</p>
 */
package com.flowdesk.infrastructure.ticket;
