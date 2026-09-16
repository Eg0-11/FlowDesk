package com.flowdesk.mcp.asset.directory;

import java.util.Optional;

/**
 * 未配置真实数据源时的资产目录（FD-0014）：<b>每一次查询都明确失败</b>。
 *
 * <p>为什么不返回空结果：{@link Optional#empty()} 的含义是「查过了，这个资产不存在」。
 * 在没有数据源的情况下给出这个答案，等于把「我们没有数据」说成「这个资产不存在」——
 * 调用方无法区分，会把一次能力缺失当成一条业务结论。因此这里抛
 * {@link AssetSourceUnavailableException}，工具层给出稳定的 {@code ASSET_SOURCE_UNAVAILABLE}。</p>
 */
public final class UnavailableAssetDirectory implements AssetDirectory {

    @Override
    public Optional<AssetRecord> findById(String assetId) {
        throw new AssetSourceUnavailableException();
    }

    @Override
    public AssetSource source() {
        // 没有数据源就没有来源可声明：连「来源是什么」这个问题的答案都不存在
        throw new AssetSourceUnavailableException();
    }
}
