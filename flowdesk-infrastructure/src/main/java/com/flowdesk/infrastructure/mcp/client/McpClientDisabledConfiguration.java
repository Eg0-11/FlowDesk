package com.flowdesk.infrastructure.mcp.client;

import com.flowdesk.application.integration.port.out.AssetQueryPort;
import com.flowdesk.application.integration.port.out.MonitoringSnapshotQueryPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 未启用 MCP 客户端时的装配（FD-0016，默认分支）。
 *
 * <p>{@code flowdesk.mcp.client.enabled} 缺失或为 {@code false} 时生效。这一分支：</p>
 * <ul>
 *   <li><b>不</b>创建任何 SDK 客户端、<b>不</b>绑定 MCP 配置、<b>不</b>建立连接、<b>不</b>发请求；</li>
 *   <li>仍然提供两个查询端口的 Bean，结果是明确的 {@code DISABLED} 失败 ——
 *       端口存在意味着调用方不需要写「有没有这个功能」的分支，而分类明确意味着
 *       它不会把「功能没开」误读成「数据不存在」。</li>
 * </ul>
 *
 * <p>配置只在启用时才被解析与校验：关闭状态下连 base-url / request-timeout 都不绑定，
 * 因此一个写错的 URL 不会在功能关闭时阻止主服务启动。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "flowdesk.mcp.client", name = "enabled", havingValue = "false",
        matchIfMissing = true)
public class McpClientDisabledConfiguration {

    /**
     * @return 明确回答 {@code DISABLED} 的资产查询端口
     */
    @Bean
    AssetQueryPort assetQueryPort() {
        return new DisabledAssetQueryAdapter();
    }

    /**
     * @return 明确回答 {@code DISABLED} 的监控快照查询端口
     */
    @Bean
    MonitoringSnapshotQueryPort monitoringSnapshotQueryPort() {
        return new DisabledMonitoringSnapshotQueryAdapter();
    }
}
