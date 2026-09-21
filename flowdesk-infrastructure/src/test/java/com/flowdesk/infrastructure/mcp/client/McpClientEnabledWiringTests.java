package com.flowdesk.infrastructure.mcp.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowdesk.application.integration.port.out.AssetQueryPort;
import com.flowdesk.application.integration.port.out.MonitoringSnapshotQueryPort;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 启用时的装配验收（FD-0016）。
 *
 * <p>端点是一个真实的本机计数端点（随机端口），因此「启动期不连接」这件事是被<b>数出来</b>的：
 * 上下文启动完成后计数必须仍然是 0。</p>
 */
@SpringBootTest(classes = McpClientEnabledWiringTests.TestApplication.class)
class McpClientEnabledWiringTests {

    private static final ControllableMcpEndpoint ENDPOINT = startEndpoint();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("flowdesk.mcp.client.enabled", () -> "true");
        registry.add("flowdesk.mcp.client.asset.base-url", ENDPOINT::baseUrl);
        registry.add("flowdesk.mcp.client.monitoring.base-url", ENDPOINT::baseUrl);
        registry.add("flowdesk.mcp.client.request-timeout", () -> "2s");
    }

    @AfterAll
    static void stopEndpoint() {
        ENDPOINT.close();
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private AssetQueryPort assetQueryPort;

    @Autowired
    private MonitoringSnapshotQueryPort monitoringSnapshotQueryPort;

    @Test
    void theContextStartsAndWiresBothPortsWithoutTouchingTheNetwork() {
        assertThat(this.assetQueryPort).isInstanceOf(McpAssetQueryAdapter.class);
        assertThat(this.monitoringSnapshotQueryPort).isInstanceOf(McpMonitoringSnapshotQueryAdapter.class);
        assertThat(ENDPOINT.postCount()).as("启动期只校验配置：不建客户端、不初始化、不发请求").isZero();
        assertThat(McpSdkLogControl.currentLevel())
                .as("启用装配时按包名把 SDK 日志设成交付默认（不输出远端原文与堆栈）")
                .isEqualTo("OFF");
    }

    @Test
    void aQueryThroughTheWiredPortCallsOnlyTheFixedTool() {
        ENDPOINT.respondWithToolText("{\"assetId\":\"AST-900001\",\"assetType\":\"SERVER\","
                + "\"status\":\"IN_SERVICE\",\"source\":\"DEMO\"}");

        var result = this.assetQueryPort.findAsset("AST-900001");
        assertThat(result.isFound()).as("result=%s", result).isTrue();
        assertThat(ENDPOINT.calledTools()).containsExactly("asset_get");
        assertThat(ENDPOINT.liveSessions()).as("查询结束后不留悬挂会话").isEmpty();
    }

    @Test
    void theRemoteToolIsNeverRegisteredAsAToolOrAnMcpClientBean() {
        assertThat(this.context.getBeanNamesForType(ToolCallback.class))
                .as("远端工具不得变成模型可见的工具")
                .isEmpty();

        List<String> beanNames = List.of(this.context.getBeanDefinitionNames()).stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .toList();
        assertThat(beanNames)
                .as("不得出现 Spring AI 的 MCP 客户端自动装配（那会把远端工具带进上下文）")
                .noneMatch(name -> name.contains("mcpsyncclient")
                        || name.contains("mcpasyncclient")
                        || name.contains("mcpclients")
                        || name.contains("toolcallback"));
    }

    private static ControllableMcpEndpoint startEndpoint() {
        try {
            return ControllableMcpEndpoint.start();
        }
        catch (IOException ex) {
            throw new IllegalStateException("测试端点启动失败", ex);
        }
    }

    /** 只装配被测配置类的最小应用：不引入数据库、Web 或其它自动装配。 */
    @SpringBootConfiguration(proxyBeanMethods = false)
    @Import(McpClientConfiguration.class)
    static class TestApplication {
    }
}
