package com.flowdesk.infrastructure.mcp.client;

/**
 * 一个已校验的 MCP 服务端点（FD-0016）。
 *
 * <p>只能通过 {@link #of(String)} 构造，因此<b>持有实例就意味着端点已经通过了
 * {@link McpEndpointPolicy} 的校验</b>：明文 HTTP + 完整字面量回环地址 + 显式端口，
 * 没有 userinfo、query、fragment 或自定义路径。本阶段的 MCP 路径固定为 {@code /mcp}，
 * 调用方无法指定别的路径或工具名。</p>
 *
 * @param baseUri 规范化后的 base uri（例如 {@code http://127.0.0.1:8091}）
 * @param path    MCP 路径（固定 {@code /mcp}）
 */
public record McpServerEndpoint(String baseUri, String path) {

    /**
     * @param baseUrl 配置里的 base-url
     * @return 已校验的端点
     * @throws IllegalStateException base-url 不满足回环 HTTP 规则
     */
    public static McpServerEndpoint of(String baseUrl) {
        return new McpServerEndpoint(McpEndpointPolicy.requireLoopbackHttpBaseUrl(baseUrl),
                McpEndpointPolicy.MCP_PATH);
    }
}
