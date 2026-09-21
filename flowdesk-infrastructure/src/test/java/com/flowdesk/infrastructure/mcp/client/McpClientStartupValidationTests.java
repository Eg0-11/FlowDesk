package com.flowdesk.infrastructure.mcp.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;

/**
 * 启用时的启动期校验（FD-0016）。
 *
 * <p>配置错误必须在<b>启动期</b>就以固定文案失败，而且失败信息里不能出现完整端点或配置原值；
 * 配置正确时启动<b>不连接</b>任何服务 —— 因此下面那个用例里根本没有 MCP 服务在跑，上下文仍然能起来。</p>
 */
class McpClientStartupValidationTests {

    @Test
    void aNonLoopbackEndpointFailsStartup() {
        assertStartupFails("flowdesk.mcp.client.asset.base-url=http://192.168.1.10:8091", "base-url");
    }

    @Test
    void aHostnameEndpointFailsStartup() {
        assertStartupFails("flowdesk.mcp.client.asset.base-url=http://asset-db.internal:8091", "base-url");
    }

    @Test
    void anEndpointWithAPathFailsStartup() {
        assertStartupFails("flowdesk.mcp.client.monitoring.base-url=http://127.0.0.1:8092/mcp", "base-url");
    }

    @Test
    void aZeroTimeoutFailsStartup() {
        assertStartupFails("flowdesk.mcp.client.request-timeout=0s", "request-timeout");
    }

    @Test
    void aTimeoutOverThirtySecondsFailsStartup() {
        assertStartupFails("flowdesk.mcp.client.request-timeout=31s", "request-timeout");
    }

    @Test
    void anUnknownSdkLogLevelFailsStartup() {
        assertStartupFails("flowdesk.mcp.client.sdk-log-level=VERBOSE", "sdk-log-level");
    }

    @Test
    void aLoopbackConfigurationStartsWithoutConnectingToAnything() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(TestApplication.class)
                .web(WebApplicationType.NONE)
                .properties("flowdesk.mcp.client.enabled=true",
                        // 这两个端口上没有任何服务在监听：启动仍然成功，证明启动期不连接
                        "flowdesk.mcp.client.asset.base-url=http://127.0.0.1:28091",
                        "flowdesk.mcp.client.monitoring.base-url=http://127.0.0.1:28092",
                        "flowdesk.mcp.client.request-timeout=2s")
                .run()) {

            assertThat(context.getBean(McpAssetQueryAdapter.class)).isNotNull();
            assertThat(context.getBean(McpMonitoringSnapshotQueryAdapter.class)).isNotNull();
        }
    }

    private static void assertStartupFails(String property, String expectedMessageFragment) {
        assertThatThrownBy(() -> new SpringApplicationBuilder(TestApplication.class)
                .web(WebApplicationType.NONE)
                .properties("flowdesk.mcp.client.enabled=true", property)
                .run())
                .as("property=[%s]", property)
                .satisfies(thrown -> {
                    Throwable root = rootCause(thrown);
                    assertThat(root)
                            .as("root=%s (thrown=%s)", root, thrown)
                            .isInstanceOf(IllegalStateException.class);
                    assertThat(root.getMessage())
                            .as("message=[%s]", root.getMessage())
                            .contains(expectedMessageFragment);
                });
    }

    private static Throwable rootCause(Throwable thrown) {
        Throwable current = thrown;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /** 启用分支的最小应用。 */
    @SpringBootConfiguration(proxyBeanMethods = false)
    @Import(McpClientConfiguration.class)
    static class TestApplication {
    }
}
