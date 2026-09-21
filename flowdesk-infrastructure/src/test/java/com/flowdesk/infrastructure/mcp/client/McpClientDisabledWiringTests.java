package com.flowdesk.infrastructure.mcp.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowdesk.application.integration.QueryFailure;
import com.flowdesk.application.integration.port.out.AssetQueryPort;
import com.flowdesk.application.integration.port.out.MonitoringSnapshotQueryPort;
import java.io.IOException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 关闭时的装配验收（FD-0016）。
 *
 * <p>关闭状态必须满足两件事：<b>端口仍然存在</b>（调用方不必写「有没有这个功能」的分支），
 * 而结果必须是明确的 {@code DISABLED}（不能被当成「没有这条数据」）；
 * 并且<b>一个请求都不发</b>——这里用一个真实计数端点把它数出来。</p>
 *
 * <p>配置刻意写成非法值：关闭状态下连配置都不需要解析，因此它不该阻止启动。
 * 这是刻意的取舍（见 ADR 0013）：关闭时零新增失败面。</p>
 */
@SpringBootTest(classes = McpClientDisabledWiringTests.TestApplication.class)
class McpClientDisabledWiringTests {

    private static final ControllableMcpEndpoint ENDPOINT = startEndpoint();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("flowdesk.mcp.client.enabled", () -> "false");
        registry.add("flowdesk.mcp.client.asset.base-url", () -> "http://asset-db.internal:8091");
        registry.add("flowdesk.mcp.client.monitoring.base-url", ENDPOINT::baseUrl);
        registry.add("flowdesk.mcp.client.request-timeout", () -> "0s");
    }

    @AfterAll
    static void stopEndpoint() {
        ENDPOINT.close();
    }

    @Autowired
    private AssetQueryPort assetQueryPort;

    @Autowired
    private MonitoringSnapshotQueryPort monitoringSnapshotQueryPort;

    @Test
    void bothPortsAnswerDisabledAndSendNothing() {
        assertThat(this.assetQueryPort).isInstanceOf(DisabledAssetQueryAdapter.class);
        assertThat(this.monitoringSnapshotQueryPort).isInstanceOf(DisabledMonitoringSnapshotQueryAdapter.class);

        assertThat(this.assetQueryPort.findAsset("AST-900001").failure()).isEqualTo(QueryFailure.DISABLED);
        assertThat(this.monitoringSnapshotQueryPort.findLatestSnapshot("AST-900001").failure())
                .isEqualTo(QueryFailure.DISABLED);

        assertThat(ENDPOINT.postCount()).as("关闭时不发送任何请求").isZero();
        assertThat(ENDPOINT.initializeCount()).isZero();
        assertThat(ENDPOINT.liveSessions()).isEmpty();
    }

    @Test
    void disabledIsNeverMistakenForNotFound() {
        assertThat(this.assetQueryPort.findAsset("AST-900001").isNotFound())
                .as("「没开这个功能」不是「这个资产不存在」")
                .isFalse();
        assertThat(this.monitoringSnapshotQueryPort.findLatestSnapshot("AST-900001").isNotFound()).isFalse();
    }

    @Test
    void aDisabledPortStillAnswersForAnInvalidInputWithoutPretendingToValidateIt() {
        // 功能整体关闭时没有「先校验输入再查」这回事：统一是 DISABLED
        assertThat(this.assetQueryPort.findAsset("not-an-asset-id").failure()).isEqualTo(QueryFailure.DISABLED);
        assertThat(ENDPOINT.postCount()).isZero();
    }

    private static ControllableMcpEndpoint startEndpoint() {
        try {
            return ControllableMcpEndpoint.start();
        }
        catch (IOException ex) {
            throw new IllegalStateException("测试端点启动失败", ex);
        }
    }

    /** 只装配关闭分支的最小应用。 */
    @SpringBootConfiguration(proxyBeanMethods = false)
    @Import(McpClientDisabledConfiguration.class)
    static class TestApplication {
    }
}
