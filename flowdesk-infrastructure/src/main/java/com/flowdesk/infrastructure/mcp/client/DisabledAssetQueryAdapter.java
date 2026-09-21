package com.flowdesk.infrastructure.mcp.client;

import com.flowdesk.application.integration.AssetQueryResult;
import com.flowdesk.application.integration.QueryFailure;
import com.flowdesk.application.integration.port.out.AssetQueryPort;

/**
 * 未启用 MCP 客户端时的资产查询端口（FD-0016）。
 *
 * <p>{@code flowdesk.mcp.client.enabled=false}（默认）时装配这个实现：它不创建 SDK 客户端、
 * 不初始化、不发出任何网络请求，而是明确回答 {@link QueryFailure#DISABLED}。</p>
 *
 * <p>为什么<b>不</b>返回「未找到」：那会把「这个功能没开」说成「这个资产不存在」，
 * 调用方会据此做出「资产确实没有」的业务判断 —— 这正是本阶段最需要避免的错误结论。
 * 输入不合法的情形同样回答 {@code DISABLED}：功能整体关闭时，连输入校验都无从谈起。</p>
 */
public final class DisabledAssetQueryAdapter implements AssetQueryPort {

    @Override
    public AssetQueryResult findAsset(String assetId) {
        long startedAt = System.nanoTime();
        McpQueryLogger.completed(McpAssetQueryAdapter.ALIAS, McpAssetQueryAdapter.TOOL_NAME,
                QueryFailure.DISABLED.name(), startedAt);
        return AssetQueryResult.failed(QueryFailure.DISABLED);
    }
}
