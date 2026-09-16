package com.flowdesk.mcp.asset.config;

import com.flowdesk.mcp.asset.directory.AssetDirectory;
import com.flowdesk.mcp.asset.directory.DemoAssetDirectory;
import com.flowdesk.mcp.asset.directory.UnavailableAssetDirectory;
import com.flowdesk.mcp.asset.tool.AssetGetTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 资产 MCP 服务的装配（FD-0014）。
 *
 * <p>分工：</p>
 * <ul>
 *   <li>{@link AssetDirectory}：资产目录查询端口 —— 按 {@code flowdesk.asset.directory.mode}
 *       装配演示数据或「不可用」实现；</li>
 *   <li>{@link AssetGetTool}：协议适配层，产出唯一的 MCP 工具规格；</li>
 *   <li>本类：把两者接起来，并守住两条安全边界（只监听回环、拒绝带 Origin 的请求）。</li>
 * </ul>
 *
 * <p>只注册<b>一个</b>工具规格（{@code asset_get}）：本模块只有一个 {@link ToolCallback} Bean，
 * 而 MCP Server starter 只从 {@code ToolCallback} / {@code ToolCallbackProvider} Bean 收集工具，
 * 因此「注册几个」在这里是显式且可数的（详见 {@link #assetGetTool(AssetDirectory)}）。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AssetDirectoryProperties.class)
public class AssetMcpConfiguration {

    /**
     * 资产目录端口。
     *
     * <p>默认（{@code mode=unavailable}）装配「每次查询都明确失败」的实现 ——
     * 本阶段没有真实资产数据源，工具会返回稳定的 {@code ASSET_SOURCE_UNAVAILABLE}，
     * 而不是把虚构数据冒充真实资产。演示数据必须显式开启。</p>
     *
     * @param properties 资产目录配置
     * @return 资产目录
     */
    @Bean
    public AssetDirectory assetDirectory(AssetDirectoryProperties properties) {
        properties.validate();
        return properties.getMode() == AssetDirectoryProperties.Mode.DEMO
                ? new DemoAssetDirectory()
                : new UnavailableAssetDirectory();
    }

    /**
     * 唯一的 MCP 工具（{@code asset_get}，只读）。
     *
     * <p>注册的是 Spring AI 的 {@link ToolCallback}：MCP Server starter 会把容器里的
     * {@code ToolCallback} Bean 转换成 MCP 工具规格并挂到服务器上（实现见
     * {@code ToolCallbackConverterAutoConfiguration.syncTools}）。因此「注册几个工具」在这里
     * 显式且可数 —— 本模块只有这一个 Bean，没有写工具。</p>
     *
     * @param directory 资产目录端口
     * @return 工具回调
     */
    @Bean
    public ToolCallback assetGetTool(AssetDirectory directory) {
        return new AssetGetTool(directory);
    }

    /**
     * 启动期校验监听地址：必须是<b>字面量</b>回环地址（第二道闸门）。
     *
     * <p><b>第一道闸门是 {@link AssetMcpBindingGuard}</b>：它在环境准备阶段就拒绝，
     * 那时还没有创建 Web 服务器、也没有绑定任何端口。这里再校验一次，用于兜住
     * 「绕过监听器直接刷新上下文」的路径（例如自定义引导代码），两处共享同一份判定与同一条文案。</p>
     *
     * <p>这正是「不能只靠 application.yml 默认值」的含义：默认值只是默认，
     * 配置覆盖必须被拦下。</p>
     *
     * @param environment 配置环境
     * @return 校验通过标记
     */
    @Bean
    public Boolean assetMcpBindingConsistency(Environment environment) {
        AssetMcpBindingGuard.requireLoopbackBinding(environment);
        return Boolean.TRUE;
    }

    /**
     * 给 MCP 端点挂上 Origin 拒绝过滤器。
     *
     * <p>只作用于 {@code /mcp}：健康检查等其它端点不受影响。</p>
     *
     * @param mcpEndpoint MCP 端点路径（与 {@code spring.ai.mcp.server.streamable-http.mcp-endpoint} 一致）
     * @return 过滤器注册
     */
    @Bean
    public FilterRegistrationBean<McpOriginRejectionFilter> mcpOriginRejectionFilter(
            @org.springframework.beans.factory.annotation.Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}")
            String mcpEndpoint) {

        FilterRegistrationBean<McpOriginRejectionFilter> registration =
                new FilterRegistrationBean<>(new McpOriginRejectionFilter());
        registration.addUrlPatterns(mcpEndpoint);
        registration.setName("mcpOriginRejectionFilter");
        registration.setOrder(0);
        return registration;
    }
}
