package com.flowdesk.bootstrap.ticket;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 分配与重新分配处理人的请求体。
 *
 * <p>文本规范化语义同 {@link CreateTicketRequest}：先 {@link String#strip()}，
 * 再判空白，最后按规范化后的长度校验；{@code null} 保持 {@code null}，不会触发 NPE。</p>
 *
 * @param assigneeId 处理人，规范化后非空白且最长 64
 */
public record AssignTicketRequest(

        @NotBlank(message = "不能为空")
        @Size(max = 64, message = "长度不能超过 64 个字符")
        String assigneeId) {

    public AssignTicketRequest {
        assigneeId = CreateTicketRequest.strip(assigneeId);
    }
}
