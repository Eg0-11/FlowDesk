/**
 * FlowDesk 工单基础设施支撑包：时间与标识两个输出端口的实现。
 *
 * <p>随机 UUID 与系统时钟只允许出现在这里；应用层与领域层通过端口获取，
 * 因此它们的用例行为可重复、可断言。</p>
 */
package com.flowdesk.infrastructure.ticket.support;
