package com.flowdesk.infrastructure.mcp.client;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MCP 客户端配置（FD-0016）。
 *
 * <pre>
 * flowdesk:
 *   mcp:
 *     client:
 *       enabled: false                                  # 默认关闭：不创建客户端、不连接、不发请求
 *       asset:
 *         base-url: http://127.0.0.1:8091               # 只允许 http + 完整字面量回环地址
 *       monitoring:
 *         base-url: http://127.0.0.1:8092
 *       request-timeout: 5s                             # 正数，且不得超过 30 秒
 * </pre>
 *
 * <p><b>默认关闭</b>：主服务在没有两个 MCP 服务、没有 API Key 的环境里必须能正常启动。
 * 关闭时查询端口仍然存在，并给出明确的 {@code DISABLED} 失败结果 —— 不会伪装成「未找到」。</p>
 *
 * <p>MCP 路径本阶段固定为 {@code /mcp}，因此 {@code base-url} 里<b>不允许</b>带路径：
 * 端点由 {@link McpEndpointPolicy} 校验并且只认 http + 完整字面量回环地址。</p>
 */
@ConfigurationProperties(prefix = "flowdesk.mcp.client")
public class McpClientProperties {

    /** 请求超时上限（秒）：超过这个值一律视为配置错误。 */
    public static final long MAX_TIMEOUT_SECONDS = 30L;

    /** 固定错误文案（不含配置原值）。 */
    static final String TIMEOUT_MESSAGE = "flowdesk.mcp.client.request-timeout 必须是正数且不超过 "
            + MAX_TIMEOUT_SECONDS + " 秒";

    private boolean enabled;

    private final Asset asset = new Asset();

    private final Monitoring monitoring = new Monitoring();

    private Duration requestTimeout = Duration.ofSeconds(5);

    /**
     * @return 是否启用 MCP 客户端
     */
    public boolean isEnabled() {
        return this.enabled;
    }

    /**
     * @param enabled 是否启用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * @return 资产服务配置组（{@code flowdesk.mcp.client.asset.*}）
     */
    public Asset getAsset() {
        return this.asset;
    }

    /**
     * @return 监控服务配置组（{@code flowdesk.mcp.client.monitoring.*}）
     */
    public Monitoring getMonitoring() {
        return this.monitoring;
    }

    /**
     * @return 请求超时
     */
    public Duration getRequestTimeout() {
        return this.requestTimeout;
    }

    /**
     * @param requestTimeout 请求超时
     */
    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    /**
     * 校验超时配置：必须有值、为正，且不超过 {@value #MAX_TIMEOUT_SECONDS} 秒。
     *
     * <p>错误信息是固定文案，<b>不回显</b>配置原值。</p>
     *
     * @throws IllegalStateException 配置不合法
     */
    public void validateTimeout() {
        if (this.requestTimeout == null || this.requestTimeout.isZero() || this.requestTimeout.isNegative()
                || this.requestTimeout.toMillis() > Duration.ofSeconds(MAX_TIMEOUT_SECONDS).toMillis()) {
            throw new IllegalStateException(TIMEOUT_MESSAGE);
        }
    }

    /** 资产服务配置组。 */
    public static class Asset {

        private String baseUrl = "http://127.0.0.1:8091";

        /**
         * @return 资产服务 base-url
         */
        public String getBaseUrl() {
            return this.baseUrl;
        }

        /**
         * @param baseUrl 资产服务 base-url
         */
        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }

    /** 监控服务配置组。 */
    public static class Monitoring {

        private String baseUrl = "http://127.0.0.1:8092";

        /**
         * @return 监控服务 base-url
         */
        public String getBaseUrl() {
            return this.baseUrl;
        }

        /**
         * @param baseUrl 监控服务 base-url
         */
        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }
}
