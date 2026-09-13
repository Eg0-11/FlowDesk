/**
 * FlowDesk 基础设施层根包：持久化、模型、向量库等外部系统的适配器。
 *
 * <p>本模块依赖 {@code flowdesk-application}，实现应用层定义的输出端口，
 * 把外部系统差异收敛在适配器内部。</p>
 *
 * <p>当前已提供两部分：</p>
 * <ul>
 *   <li>{@code com.flowdesk.infrastructure.ticket} —— Spring JDBC 工单存储、标识与时间端口实现，
 *       以及 Flyway 迁移脚本；</li>
 *   <li>{@code com.flowdesk.infrastructure.ai} —— DeepSeek 的 OpenAI 兼容传输适配。</li>
 * </ul>
 *
 * <p>Redis、消息队列与向量库适配器尚未引入。</p>
 */
package com.flowdesk.infrastructure;
