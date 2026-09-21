package com.flowdesk.infrastructure.mcp.client;

import com.flowdesk.application.integration.port.out.AssetQueryPort;
import com.flowdesk.application.integration.port.out.MonitoringSnapshotQueryPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 启用 MCP 客户端时的装配（FD-0016）。
 *
 * <p>只有 {@code flowdesk.mcp.client.enabled=true} 才会生效。装配期做两件事：</p>
 * <ol>
 *   <li><b>校验配置</b>（超时为正且不超过 30 秒；两个 base-url 必须是 http + 完整字面量回环地址，
 *       否则启动失败）—— {@link McpServerEndpoint#of(String)} 在构造期完成校验，
 *       因此「持有端点」就等于「端点合法」；</li>
 *   <li>建立两个<b>互相独立</b>的适配器，各自持有自己的端点。</li>
 * </ol>
 *
 * <p>这里<b>不</b>建立任何连接：{@code initialize} 与 {@code tools/call} 都发生在第一次查询里。
 * 也不注册任何 {@code ToolCallback} / {@code ChatClient} 工具 —— 远端工具不会变成模型可见的工具。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(McpClientProperties.class)
@ConditionalOnProperty(prefix = "flowdesk.mcp.client", name = "enabled", havingValue = "true")
public class McpClientConfiguration {

    /**
     * @param properties 客户端配置
     * @return 按次调用的工具客户端（请求/初始化/建连共用同一个上界）
     */
    @Bean
    McpToolClient mcpToolClient(McpClientProperties properties) {
        properties.validateTimeout();
        properties.validateSdkLogLevel();
        // 按包名控制官方 SDK 的客户端日志（默认 OFF）：它会把远端 serverInfo/instructions 原文
        // 与异常堆栈写进日志，而本项目自己的固定元数据日志不受影响（见 McpSdkLogControl）
        McpSdkLogControl.apply(properties.getSdkLogLevel());
        return new McpToolClient(properties.getRequestTimeout());
    }

    /**
     * @param client     MCP 工具客户端
     * @param properties 客户端配置
     * @return 资产查询端口（端点校验失败会让启动失败）
     */
    @Bean
    AssetQueryPort assetQueryPort(McpToolClient client, McpClientProperties properties) {
        return new McpAssetQueryAdapter(client, McpServerEndpoint.of(properties.getAsset().getBaseUrl()));
    }

    /**
     * @param client     MCP 工具客户端
     * @param properties 客户端配置
     * @return 监控快照查询端口（端点校验失败会让启动失败）
     */
    @Bean
    MonitoringSnapshotQueryPort monitoringSnapshotQueryPort(McpToolClient client, McpClientProperties properties) {
        return new McpMonitoringSnapshotQueryAdapter(client,
                McpServerEndpoint.of(properties.getMonitoring().getBaseUrl()));
    }
}
