package com.flowdesk.bootstrap.ticket;

import com.flowdesk.domain.ticket.TicketCategory;
import com.flowdesk.domain.ticket.TicketPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建工单请求体。
 *
 * <p><b>文本规范化语义</b>：所有字符串字段在构造时先做 {@link String#strip()}，
 * Bean Validation 于是校验的是<b>规范化之后</b>的值 —— 因此
 * 「最大有效长度 + 首尾空白」是合法输入，而「strip 后仍超长」被拒绝。
 * {@code null} 保持 {@code null}（不调用 strip），任何字段都不会因为空值触发 NPE。</p>
 *
 * <p>这里只做 HTTP 层的形状与长度校验；字段的最终权威仍是领域聚合，
 * 领域规则不会被复制成 Controller 的业务判断。</p>
 *
 * @param title       标题，规范化后非空白且最长 200
 * @param description 描述，规范化后非空白且最长 4000
 * @param category    分类，必填
 * @param priority    优先级，必填
 * @param requesterId 请求人，规范化后非空白且最长 64
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

    public CreateTicketRequest {
        title = strip(title);
        description = strip(description);
        requesterId = strip(requesterId);
    }

    /**
     * 规范化文本：去除首尾空白（含 Unicode 空白），{@code null} 保持 {@code null}。
     *
     * @param value 原始文本，可为 {@code null}
     * @return 规范化后的文本
     */
    static String strip(String value) {
        return value == null ? null : value.strip();
    }
}
