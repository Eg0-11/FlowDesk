package com.flowdesk.bootstrap.ticket;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 提交处理结论的请求体。
 *
 * @param resolution 处理结论，非空白，最长 2000
 */
public record ResolveTicketRequest(

        @NotBlank(message = "不能为空")
        @Size(max = 2000, message = "长度不能超过 2000 个字符")
        String resolution) {
}
