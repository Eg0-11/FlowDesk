package com.flowdesk.mcp.asset.directory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 演示用的资产目录（FD-0014）：返回<b>少量固定的虚构资产</b>。
 *
 * <p>它存在的唯一目的是让 MCP 协议链路（initialize / tools/list / tools/call）可以被真实地
 * 端到端验证 —— 没有数据源就无法验证「命中」这条路径。</p>
 *
 * <h2>边界（必须被调用方看见）</h2>
 * <ul>
 *   <li>所有记录都带 {@link AssetSource#DEMO}，调用方一眼就能看出这不是真实企业资产；</li>
 *   <li>资产标识与内容都是<b>构造出来的</b>，与任何真实资产编号无关；
 *       刻意避开容易被误认为真实编号的取值；</li>
 *   <li>只有显式开启演示模式（{@code flowdesk.asset.directory.mode=demo}）才会装配它 ——
 *       默认模式装配的是 {@link UnavailableAssetDirectory}，不会用虚构数据冒充真实资产。</li>
 * </ul>
 */
public final class DemoAssetDirectory implements AssetDirectory {

    /** 固定演示数据：{@code AST-90000x} 是明显的保留段，避免被当成真实编号。 */
    private static final Map<String, AssetRecord> DEMO_ASSETS = demoAssets();

    @Override
    public Optional<AssetRecord> findById(String assetId) {
        return Optional.ofNullable(DEMO_ASSETS.get(assetId));
    }

    @Override
    public AssetSource source() {
        return AssetSource.DEMO;
    }

    private static Map<String, AssetRecord> demoAssets() {
        Map<String, AssetRecord> assets = new LinkedHashMap<>();
        assets.put("AST-900001", new AssetRecord("AST-900001", "SERVER", "IN_SERVICE", AssetSource.DEMO));
        assets.put("AST-900002", new AssetRecord("AST-900002", "NETWORK_DEVICE", "MAINTENANCE",
                AssetSource.DEMO));
        assets.put("AST-900003", new AssetRecord("AST-900003", "WORKSTATION", "RETIRED", AssetSource.DEMO));
        return Map.copyOf(assets);
    }
}
