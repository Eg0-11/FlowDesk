package com.flowdesk.mcp.asset.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 资产目录配置（FD-0014）。
 *
 * <pre>
 * flowdesk:
 *   asset:
 *     directory:
 *       mode: unavailable   # 默认；也可以显式设为 demo 使用内置虚构数据
 * </pre>
 *
 * <p><b>默认是 {@link Mode#UNAVAILABLE}</b>：没有真实数据源时，工具返回稳定的
 * {@code ASSET_SOURCE_UNAVAILABLE}，而不是拿虚构数据冒充真实资产。
 * 演示模式必须在配置里显式打开 —— 「默认安全、显式开启」比「默认假装有数据」更难出错。</p>
 */
@ConfigurationProperties(prefix = "flowdesk.asset.directory")
public class AssetDirectoryProperties {

    /** 资产目录模式。 */
    public enum Mode {

        /**
         * 没有任何数据源：每次查询都以 {@code ASSET_SOURCE_UNAVAILABLE} 失败。
         * <p>这是默认值，也是本阶段（未接入真实资产系统）的诚实状态。</p>
         */
        UNAVAILABLE,

        /**
         * 内置虚构演示数据：所有结果都带 {@code source=DEMO}。
         * <p>只用于演示与自动化测试；它<b>不是</b>真实资产系统。</p>
         */
        DEMO
    }

    private Mode mode = Mode.UNAVAILABLE;

    /**
     * @return 当前模式
     */
    public Mode getMode() {
        return this.mode;
    }

    /**
     * @param mode 当前模式
     */
    public void setMode(Mode mode) {
        this.mode = mode;
    }

    /**
     * 严格校验：模式不允许为空。
     *
     * @throws IllegalStateException 模式缺失
     */
    public void validate() {
        if (this.mode == null) {
            throw new IllegalStateException("flowdesk.asset.directory.mode 只能是 "
                    + Mode.UNAVAILABLE + " 或 " + Mode.DEMO + "（当前为空）");
        }
    }
}
