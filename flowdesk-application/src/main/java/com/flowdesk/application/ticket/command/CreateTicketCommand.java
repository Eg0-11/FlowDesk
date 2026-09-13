package com.flowdesk.application.ticket.command;

import com.flowdesk.domain.ticket.TicketCategory;
import com.flowdesk.domain.ticket.TicketPriority;
import com.flowdesk.domain.ticket.UserId;

/**
 * 创建工单命令。
 *
 * <p>命令只承载输入，<b>不复制领域校验</b>：标题与描述的长度、分类与优先级与请求人的必填性
 * 全部由 {@code Ticket} 聚合裁决，领域错误码原样向上传递。</p>
 *
 * @param title       标题
 * @param description 描述
 * @param category    分类
 * @param priority    优先级
 * @param requesterId 请求人
 */
public record CreateTicketCommand(String title,
                                  String description,
                                  TicketCategory category,
                                  TicketPriority priority,
                                  UserId requesterId) {
}
