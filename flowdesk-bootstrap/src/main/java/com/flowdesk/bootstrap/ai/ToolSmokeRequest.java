package com.flowdesk.bootstrap.ai;

import jakarta.validation.constraints.NotBlank;

/**
 * 工具调用冒烟请求体。
 *
 * <p>这里只校验非空；具体允许值由应用层 {@code IssueType} 统一裁决，
 * 保证 HTTP 边界与本地工具使用同一份允许值定义。</p>
 *
 * @param issueType 工单类型
 */
public record ToolSmokeRequest(

        @NotBlank(message = "不能为空")
        String issueType) {
}
