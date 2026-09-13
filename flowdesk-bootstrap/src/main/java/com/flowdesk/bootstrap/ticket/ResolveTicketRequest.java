package com.flowdesk.bootstrap.ticket;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 提交处理结论的请求体。
 *
 * <p>文本规范化语义同 {@link CreateTicketRequest}：先 {@link String#strip()}，
 * 再判空白，最后按规范化后的长度校验；{@code null} 保持 {@code null}，不会触发 NPE。</p>
 *
 * @param resolution 处理结论，规范化后非空白且最长 2000
 */
public record ResolveTicketRequest(

        @NotBlank(message = "不能为空")
        @Size(max = 2000, message = "长度不能超过 2000 个字符")
        String resolution) {

    public ResolveTicketRequest {
        resolution = CreateTicketRequest.strip(resolution);
    }
}
