package com.flowdesk.bootstrap.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowdesk.application.integration.QueryFailure;
import com.flowdesk.application.integration.port.out.AssetQueryPort;
import com.flowdesk.application.integration.port.out.MonitoringSnapshotQueryPort;
import com.flowdesk.bootstrap.knowledge.KnowledgeDocumentController;
import com.flowdesk.bootstrap.ticket.TicketController;
import com.flowdesk.infrastructure.mcp.client.DisabledAssetQueryAdapter;
import com.flowdesk.infrastructure.mcp.client.DisabledMonitoringSnapshotQueryAdapter;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * 主服务默认配置下的 MCP 客户端装配（FD-0016）。
 *
 * <p>这里加载的是<b>真实的</b>主服务上下文（{@code FlowDeskApplication} + 随包交付的
 * {@code application.yml}），因此它证明的是交付默认值本身：功能关闭、端口存在、结果明确为
 * {@code DISABLED}，而且这次接入<b>没有</b>给模型新增任何工具、也没有引入 MCP 客户端自动装配。</p>
 */
@SpringBootTest
class McpClientDefaultContextTests {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private AssetQueryPort assetQueryPort;

    @Autowired
    private MonitoringSnapshotQueryPort monitoringSnapshotQueryPort;

    @Test
    void theShippedDefaultIsDisabledAndBothPortsAreWired() {
        assertThat(this.assetQueryPort).isInstanceOf(DisabledAssetQueryAdapter.class);
        assertThat(this.monitoringSnapshotQueryPort).isInstanceOf(DisabledMonitoringSnapshotQueryAdapter.class);

        assertThat(this.assetQueryPort.findAsset("AST-900001").failure()).isEqualTo(QueryFailure.DISABLED);
        assertThat(this.monitoringSnapshotQueryPort.findLatestSnapshot("AST-900001").failure())
                .isEqualTo(QueryFailure.DISABLED);

        assertThat(this.assetQueryPort.findAsset("AST-900001").isNotFound())
                .as("关闭 ≠ 未找到：这是两种不同的结论")
                .isFalse();
    }

    @Test
    void thisIntegrationRegistersNoToolAndNoMcpClientBean() {
        assertThat(this.context.getBeanNamesForType(ToolCallback.class))
                .as("远端工具不得成为模型可见的工具")
                .isEmpty();

        List<String> beanNames = List.of(this.context.getBeanDefinitionNames()).stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .toList();
        assertThat(beanNames)
                .as("不得出现 Spring AI 的 MCP 客户端自动装配")
                .noneMatch(name -> name.contains("mcpsyncclient")
                        || name.contains("mcpasyncclient")
                        || name.contains("mcpclients"));
    }

    @Test
    void theExistingTicketAndKnowledgeBeansAreStillThere() {
        assertThat(this.context.getBeanNamesForType(TicketController.class)).hasSize(1);
        assertThat(this.context.getBeanNamesForType(KnowledgeDocumentController.class)).hasSize(1);
    }
}
