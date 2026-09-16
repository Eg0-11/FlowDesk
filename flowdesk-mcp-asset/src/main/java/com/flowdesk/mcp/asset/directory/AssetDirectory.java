package com.flowdesk.mcp.asset.directory;

import java.util.Optional;

/**
 * 资产目录查询端口（FD-0014）：<b>只读</b>。
 *
 * <p>协议适配（工具输入/输出、JSON、MCP 结果）与查询逻辑通过这个接口分开：
 * 工具层不认识数据从哪来，目录层不认识 MCP。</p>
 *
 * <h2>契约</h2>
 * <ul>
 *   <li>输入一定已经通过 {@link AssetId#isValid(String)} 校验；实现不必重复判断；</li>
 *   <li><b>未找到</b>：返回 {@link Optional#empty()}。这是一个正常答案，
 *       不是错误 —— 工具层据此返回 {@code ASSET_NOT_FOUND}；</li>
 *   <li><b>数据源不可用</b>：抛 {@link AssetSourceUnavailableException}
 *       （未配置真实数据源、连接失败、上游异常…）。工具层据此返回
 *       {@code ASSET_SOURCE_UNAVAILABLE}；</li>
 *   <li>实现抛出的任何其它异常也会被工具层收敛为 {@code ASSET_SOURCE_UNAVAILABLE}，
 *       并且<b>不会</b>把异常消息、堆栈、路径或配置带出去；</li>
 *   <li>本接口<b>没有</b>任何写方法：新增、修改、删除资产在本阶段不可表达。</li>
 * </ul>
 */
public interface AssetDirectory {

    /**
     * 按标识查询一条资产。
     *
     * @param assetId 已校验的资产标识
     * @return 命中的资产；不存在时为空
     * @throws AssetSourceUnavailableException 数据源不可用
     */
    Optional<AssetRecord> findById(String assetId);

    /**
     * 本目录数据的来源标识。
     *
     * <p>它让「未找到」这个答案也能带上血缘：调用方需要知道「是哪个目录说没有这条资产」——
     * 演示目录说「没有」与真实资产系统说「没有」是完全不同的两件事。</p>
     *
     * @return 来源标识
     * @throws AssetSourceUnavailableException 没有可用来源（例如未配置真实数据源）
     */
    AssetSource source();
}
