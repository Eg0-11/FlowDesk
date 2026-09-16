package com.flowdesk.mcp.asset.directory;

/**
 * 资产数据的来源标识（FD-0014）。
 *
 * <p>它是<b>血缘字段</b>：调用方必须能一眼看出「这条资产记录是从哪来的」。</p>
 * <ul>
 *   <li>{@link #DEMO}：本模块内置的<b>虚构演示数据</b>（见 {@link DemoAssetDirectory}），
 *       与任何真实企业资产系统无关；</li>
 *   <li>{@link #REAL}：真实资产目录。本阶段<b>没有</b>任何实现 ——
 *       未配置真实数据源时返回稳定的 {@code ASSET_SOURCE_UNAVAILABLE}，
 *       而不是拿演示数据冒充真实资产。</li>
 * </ul>
 */
public enum AssetSource {

    /** 内置虚构演示数据。 */
    DEMO,

    /** 真实资产目录（本阶段未实现，保留语义）。 */
    REAL
}
