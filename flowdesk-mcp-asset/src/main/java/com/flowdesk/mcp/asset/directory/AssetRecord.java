package com.flowdesk.mcp.asset.directory;

/**
 * 一条资产记录（FD-0014）：只包含回答「这个资产是什么、现在什么状态、这条记录从哪来」所需的字段。
 *
 * <p>刻意<b>不含</b>负责人、位置、IP、序列号、采购信息、内部标识或任何可能来自真实资产系统的
 * 敏感字段 —— 本工具是只读查询，返回面越小越好。</p>
 *
 * @param assetId   资产标识（{@code AST-123456}）
 * @param assetType 资产类型（稳定枚举名，例如 {@code SERVER}）
 * @param status    资产状态（稳定枚举名，例如 {@code IN_SERVICE}）
 * @param source    该记录来自哪里（{@link AssetSource}）
 */
public record AssetRecord(String assetId, String assetType, String status, AssetSource source) {
}
