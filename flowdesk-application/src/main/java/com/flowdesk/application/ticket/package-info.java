/**
 * FlowDesk 工单应用层：框架无关的用例、输入输出端口与只读视图。
 *
 * <p>本包只依赖 JDK、{@code com.flowdesk.domain} 与 {@code com.flowdesk.application}：
 * 不出现 Spring、JPA、Jackson、Lombok、数据库/Redis/MQ 客户端，也不依赖 agent、infrastructure、
 * bootstrap 与 MCP 模块。因此用例可以脱离任何运行时框架直接测试。</p>
 *
 * <p>结构：</p>
 * <ul>
 *   <li>{@code command} —— 输入命令 record，只承载数据，不复制领域校验；</li>
 *   <li>{@code query} —— 只读查询条件；</li>
 *   <li>{@code port.in} —— 输入端口（写入用例与查询用例）；</li>
 *   <li>{@code port.out} —— 输出端口（存储、标识生成、时间）；</li>
 *   <li>{@code service} —— 纯 Java 无状态用例实现；</li>
 *   <li>{@code view} —— 对外只读视图，输入适配器拿不到可变聚合。</li>
 * </ul>
 *
 * <p>错误契约由 {@link com.flowdesk.application.ticket.TicketApplicationException} 与
 * {@link com.flowdesk.application.ticket.TicketApplicationErrorCode} 表达；
 * 领域层失败不经包装，原始领域错误码直接向上传递。</p>
 */
package com.flowdesk.application.ticket;
