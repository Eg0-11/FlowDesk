package com.flowdesk.infrastructure.mcp.client;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 按次调用的 MCP 工具客户端（FD-0016）。
 *
 * <h2>生命周期</h2>
 * <p>每次调用都是完整的一轮：<b>建立客户端 → {@code initialize()} → 一次 {@code tools/call}
 * → 释放会话</b>。没有连接池、没有后台保活、没有应用级重试；两个服务各自独立，
 * 因此一个不可达不会影响另一个。</p>
 *
 * <h2>为什么不做 {@code tools/list}</h2>
 * <p>本阶段的工具是<b>固定契约</b>（{@code asset_get} / {@code monitoring_snapshot_get}），
 * 工具名由适配器写死，调用方既不能指定工具名也不能传任意参数对象。少一次往返，
 * 也少一个「服务公布了别的工具」的意外面。</p>
 *
 * <h2>超时覆盖范围（实测 SDK 0.17.0）</h2>
 * <ul>
 *   <li>{@code initializationTimeout} 约束 {@code initialize} 握手（{@code LifecycleInitializer}
 *       对该 Mono 施加 {@code timeout}）；</li>
 *   <li>{@code requestTimeout} 约束每一个请求的响应等待（{@code McpClientSession.sendRequest}
 *       对该 Mono 施加 {@code timeout}）；</li>
 *   <li>传输层 {@code connectTimeout} 约束 TCP 建连（默认 10 秒，这里显式设为同一个上界）；</li>
 *   <li>关闭：SDK 的 {@code closeGracefully()} 自带 10 秒上限（{@code DEFAULT_CLOSE_TIMEOUT_MS}），
 *       而它发出的 {@code DELETE /mcp} 也走同一个 HttpClient。SDK 默认<b>不给单个 HTTP 请求</b>
 *       设超时，因此这里通过 {@code customizeRequest} 给每个请求（含 DELETE）加上
 *       {@code requestTimeout}，于是关闭的实际上界是「requestTimeout 与 10 秒中的较小者」。</li>
 * </ul>
 *
 * <h2>重定向</h2>
 * <p>显式设置 {@code followRedirects(NEVER)}：跟随重定向等于允许服务把我们带到别的地址，
 * 与「只允许回环字面量」的边界冲突。</p>
 */
final class McpToolClient {

    /** 客户端自称（用于 initialize 的 clientInfo）。 */
    private static final McpSchema.Implementation CLIENT_INFO =
            new McpSchema.Implementation("flowdesk-main", "0.1.0");

    /** 唯一入参名（与两个服务公布的 schema 一致）。 */
    private static final String ARGUMENT_ASSET_ID = "assetId";

    private final Duration requestTimeout;

    /**
     * @param requestTimeout 请求/初始化/建连共用的上界
     */
    McpToolClient(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    /**
     * 调用一个固定工具。
     *
     * @param endpoint 已校验的端点（路径固定 {@code /mcp}）
     * @param toolName 固定工具名（由适配器提供，调用方无法覆盖）
     * @param assetId  已经过格式校验的资产标识
     * @return 原始工具响应
     * @throws McpClientFailureException 建连、初始化、调用或关闭阶段失败（携带稳定分类）
     */
    McpToolResponse call(McpServerEndpoint endpoint, String toolName, String assetId) {
        McpSyncClient client = null;
        try {
            client = newClient(endpoint);
            client.initialize();

            McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder()
                    .name(toolName)
                    .arguments(Map.of(ARGUMENT_ASSET_ID, assetId))
                    .build());

            return toResponse(result);
        }
        catch (McpClientFailureException ex) {
            // 已经分类好的失败（例如内容形状不合法）不得被下面的兜底再分类一次
            throw ex;
        }
        catch (RuntimeException ex) {
            throw new McpClientFailureException(McpFailureMapper.classify(ex), ex);
        }
        finally {
            close(client);
        }
    }

    private McpSyncClient newClient(McpServerEndpoint endpoint) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder(endpoint.baseUri())
                .endpoint(endpoint.path())
                .connectTimeout(this.requestTimeout)
                .customizeClient(builder -> builder
                        .connectTimeout(this.requestTimeout)
                        .followRedirects(HttpClient.Redirect.NEVER))
                .customizeRequest(builder -> builder.timeout(this.requestTimeout))
                .build();

        return McpClient.sync(transport)
                .requestTimeout(this.requestTimeout)
                .initializationTimeout(this.requestTimeout)
                .clientInfo(CLIENT_INFO)
                .build();
    }

    /**
     * 把 SDK 的工具结果收敛成「远端是否声明失败 + 唯一文本载荷」。
     *
     * <p>内容形状不合法的情形（没有内容、多段内容、非文本内容、{@code structuredContent} 非空、
     * 文本为空或超长）在这里按非法响应处理 —— 本阶段两个服务公布的都是
     * 「单个 text content」这一种形状，出现别的形状说明契约变了。</p>
     *
     * @param result SDK 结果
     * @return 原始响应
     * @throws McpClientFailureException 内容形状不合法
     */
    private static McpToolResponse toResponse(McpSchema.CallToolResult result) {
        if (result == null || result.structuredContent() != null) {
            throw new McpClientFailureException(com.flowdesk.application.integration.QueryFailure.INVALID_RESPONSE,
                    null);
        }
        List<McpSchema.Content> content = result.content();
        if (content == null || content.size() != 1 || !(content.get(0) instanceof McpSchema.TextContent text)) {
            throw new McpClientFailureException(com.flowdesk.application.integration.QueryFailure.INVALID_RESPONSE,
                    null);
        }
        String payload = text.text();
        if (payload == null || payload.isBlank() || payload.length() > PayloadFields.MAX_PAYLOAD_CHARS) {
            throw new McpClientFailureException(com.flowdesk.application.integration.QueryFailure.INVALID_RESPONSE,
                    null);
        }
        return new McpToolResponse(Boolean.TRUE.equals(result.isError()), payload);
    }

    /**
     * 释放客户端与会话：先尝试优雅关闭（会发出 {@code DELETE /mcp} 结束会话），失败再立即关闭。
     *
     * <p>关闭失败<b>不</b>改变这次调用的结论：它只影响服务端会话的清理，客户端进程不持有
     * 任何需要回收的连接。但它是<b>有界</b>的 —— 见类注释里的超时覆盖范围。</p>
     *
     * @param client 客户端（可以是 {@code null}）
     */
    private static void close(McpSyncClient client) {
        if (client == null) {
            return;
        }
        try {
            if (!client.closeGracefully()) {
                client.close();
            }
        }
        catch (RuntimeException ex) {
            try {
                client.close();
            }
            catch (RuntimeException ignored) {
                // 关闭是尽力而为：既不进响应，也不改变调用结论
            }
        }
    }
}
