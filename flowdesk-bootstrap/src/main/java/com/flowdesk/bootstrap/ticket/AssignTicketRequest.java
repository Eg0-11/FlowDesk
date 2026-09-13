package com.flowdesk.bootstrap.ticket;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 分配与重新分配处理人的请求体。
 *
 * @param assigneeId 处理人，非空白，最长 64
 */
public record AssignTicketRequest(

        @NotBlank(message = "不能为空")
        @Size(max = 64, message = "长度不能超过 64 个字符")
        String assigneeId) {
}
