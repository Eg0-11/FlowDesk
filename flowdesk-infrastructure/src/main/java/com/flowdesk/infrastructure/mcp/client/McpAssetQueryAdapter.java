package com.flowdesk.infrastructure.mcp.client;

import com.flowdesk.application.integration.AssetIdentifier;
import com.flowdesk.application.integration.AssetQueryResult;
import com.flowdesk.application.integration.QueryFailure;
import com.flowdesk.application.integration.port.out.AssetQueryPort;

/**
 * 资产查询的 MCP 适配器（FD-0016）。
 *
 * <p>固定工具 {@code asset_get}，固定入参名 {@code assetId}：调用方只能给出资产标识，
 * <b>不能</b>指定工具名或传任意参数对象，因此「用这个客户端去调别的工具」在这条路径上不可表达。</p>
 *
 * <p>输入校验在<b>发出任何请求之前</b>完成：不合法的 assetId 直接返回
 * {@link QueryFailure#INVALID_INPUT}，不建立客户端、不连接、不发请求。</p>
 */
public final class McpAssetQueryAdapter implements AssetQueryPort {

    /** 日志里的服务别名。 */
    static final String ALIAS = "asset";

    /** 固定工具名（本阶段不做 tools/list，工具契约是固定的）。 */
    static final String TOOL_NAME = "asset_get";

    private final McpToolClient client;

    private final McpServerEndpoint endpoint;

    /**
     * @param client   MCP 工具客户端
     * @param endpoint 已校验的资产服务端点
     */
    public McpAssetQueryAdapter(McpToolClient client, McpServerEndpoint endpoint) {
        this.client = client;
        this.endpoint = endpoint;
    }

    @Override
    public AssetQueryResult findAsset(String assetId) {
        long startedAt = System.nanoTime();

        if (!AssetIdentifier.isValid(assetId)) {
            return log(startedAt, AssetQueryResult.failed(QueryFailure.INVALID_INPUT));
        }

        try {
            McpToolResponse response = this.client.call(this.endpoint, TOOL_NAME, assetId);
            return log(startedAt, AssetPayloadParser.parse(assetId, response));
        }
        catch (McpClientFailureException ex) {
            return log(startedAt, AssetQueryResult.failed(ex.failure()));
        }
    }

    private static AssetQueryResult log(long startedAt, AssetQueryResult result) {
        McpQueryLogger.completed(ALIAS, TOOL_NAME, label(result), startedAt);
        return result;
    }

    private static String label(AssetQueryResult result) {
        return result.isFailed() ? result.failure().name() : result.outcome().name();
    }
}
