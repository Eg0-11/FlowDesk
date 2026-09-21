package com.flowdesk.application.integration.port.out;

import com.flowdesk.application.integration.AssetQueryResult;

/**
 * 资产查询输出端口（FD-0016）。
 *
 * <p>只读：只有一个查询方法，没有写方法。输入契约与远端服务一致 ——
 * {@code AST-} 加六位数字；不合法输入必须在发出任何请求之前被拒绝
 * （返回 {@code FAILED} + {@code INVALID_INPUT}）。</p>
 *
 * <p>实现不得暴露任意工具名或任意参数对象：调用方只能给出资产标识，
 * 「查什么」由实现决定。</p>
 */
public interface AssetQueryPort {

    /**
     * 按资产标识查询一条资产记录。
     *
     * @param assetId 资产标识（{@code AST-123456}）
     * @return 三态结果，永不为 {@code null}
     */
    AssetQueryResult findAsset(String assetId);
}
