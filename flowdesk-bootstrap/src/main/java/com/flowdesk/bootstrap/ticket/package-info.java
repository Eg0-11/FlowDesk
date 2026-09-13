/**
 * FlowDesk 工单 HTTP 边界包。
 *
 * <p>本包包含工单 REST 接口（{@link com.flowdesk.bootstrap.ticket.TicketController}，基路径
 * {@code /api/v1/tickets}）、用例服务的装配
 * （{@link com.flowdesk.bootstrap.ticket.TicketApplicationConfiguration}）以及工单相关的
 * 异常映射（{@link com.flowdesk.bootstrap.ticket.TicketExceptionHandler}）。</p>
 *
 * <p>本包只依赖应用层的入站端口，不得依赖具体持久化实现。</p>
 */
package com.flowdesk.bootstrap.ticket;
