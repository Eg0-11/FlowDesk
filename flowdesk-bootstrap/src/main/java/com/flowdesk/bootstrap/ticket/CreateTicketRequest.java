package com.flowdesk.bootstrap.ticket;

import com.flowdesk.domain.ticket.TicketCategory;
import com.flowdesk.domain.ticket.TicketPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建工单请求体。
 *
 * <p>这里只做 HTTP 层的形状与长度校验；字段的最终权威仍是领域聚合，
 * 领域规则不会被复制成 Controller 的业务判断。</p>
 *
 * @param title       标题，非空白，最长 200
 * @param description 描述，非空白，最长 4000
 * @param category    分类，必填
 * @param priority    优先级，必填
 * @param requesterId 请求人，非空白，最长 64
 */
public record CreateTicketRequest(

        @NotBlank(message = "不能为空")
        @Size(max = 200, message = "长度不能超过 200 个字符")
        String title,

        @NotBlank(message = "不能为空")
        @Size(max = 4000, message = "长度不能超过 4000 个字符")
        String description,

        @NotNull(message = "不能为空")
        TicketCategory category,

        @NotNull(message = "不能为空")
        TicketPriority priority,

        @NotBlank(message = "不能为空")
        @Size(max = 64, message = "长度不能超过 64 个字符")
        String requesterId) {
}
