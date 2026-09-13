/**
 * FlowDesk 工单领域包：工单聚合、值对象与生命周期规则。
 *
 * <p>本包是纯 Java 实现，只使用 JDK：不依赖 Spring、持久化框架、序列化框架或任何外部系统。
 * 时间与标识一律由调用方传入，聚合内部从不读取系统时钟，因此行为完全确定、可直接单元测试。</p>
 *
 * <p>对外契约：</p>
 * <ul>
 *   <li>{@link com.flowdesk.domain.ticket.Ticket} —— 聚合根，负责状态机与不变量；</li>
 *   <li>{@link com.flowdesk.domain.ticket.TicketId}、{@link com.flowdesk.domain.ticket.UserId} —— 值对象；</li>
 *   <li>{@link com.flowdesk.domain.ticket.TicketStatus}、{@link com.flowdesk.domain.ticket.TicketPriority}、
 *       {@link com.flowdesk.domain.ticket.TicketCategory} —— 枚举；</li>
 *   <li>{@link com.flowdesk.domain.ticket.TicketDomainException} 携带
 *       {@link com.flowdesk.domain.ticket.TicketErrorCode}，上层据此做稳定映射。</li>
 * </ul>
 */
package com.flowdesk.domain.ticket;
