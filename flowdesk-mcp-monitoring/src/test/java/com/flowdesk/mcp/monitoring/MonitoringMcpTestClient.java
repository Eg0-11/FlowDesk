package com.flowdesk.mcp.monitoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.Map;

/**
 * 测试用的 MCP 客户端工厂（FD-0015）。
 *
 * <p>用的是<b>官方 MCP Java SDK 客户端</b>（{@code io.modelcontextprotocol.sdk:mcp}，版本由
 * Spring AI 1.1.2 的 BOM 带入），通过 Streamable HTTP 连接真实启动的服务。
 * 测试断言的是真实的 JSON-RPC 报文往返，而不是本地 Java 方法调用。</p>
 */
final class MonitoringMcpTestClient {

    /** 与 {@code spring.ai.mcp.server.streamable-http.mcp-endpoint} 一致。 */
    static final String MCP_ENDPOINT = "/mcp";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MonitoringMcpTestClient() {
    }

    /**
     * 连接一个随机端口上的监控 MCP 服务。
     *
     * @param port 服务端口
     * @return 同步客户端（调用方负责 {@code close()}）
     */
    static McpSyncClient connect(int port) {
        return connect(port, null);
    }

    /**
     * 连接服务，并可选地给每个请求带上 {@code Origin} 头（用于验证跨源拒绝）。
     *
     * @param port   服务端口
     * @param origin Origin 头的值；{@code null} 表示不带
     * @return 同步客户端
     */
    static McpSyncClient connect(int port, String origin) {
        HttpClientStreamableHttpTransport.Builder builder = HttpClientStreamableHttpTransport
                .builder("http://127.0.0.1:" + port)
                .endpoint(MCP_ENDPOINT);
        if (origin != null) {
            builder.customizeRequest(request -> request.header("Origin", origin));
        }
        return McpClient.sync(builder.build())
                .requestTimeout(Duration.ofSeconds(15))
                .build();
    }

    /**
     * @param client 已初始化的客户端
     * @param toolName 工具名
     * @param arguments 入参
     * @return 工具调用结果
     */
    static McpSchema.CallToolResult call(McpSyncClient client, String toolName, Map<String, Object> arguments) {
        return client.callTool(McpSchema.CallToolRequest.builder()
                .name(toolName)
                .arguments(arguments)
                .build());
    }

    /**
     * @param result 工具结果
     * @return 第一个 text content 的原文
     */
    static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    /**
     * @param result 工具结果
     * @return 解析成 JSON 的载荷
     * @throws Exception JSON 不合法
     */
    static JsonNode payload(McpSchema.CallToolResult result) throws Exception {
        return MAPPER.readTree(text(result));
    }
}
