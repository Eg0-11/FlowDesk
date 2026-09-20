package com.flowdesk.mcp.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * 工具注册的装配测试（FD-0015）。
 *
 * <p>协议验收（{@code initialize}/{@code tools/list}/{@code tools/call}）由
 * {@code MonitoringMcpProtocolTests} 用真实 SDK 客户端覆盖；本类回答的是另一个问题：
 * <b>「容器里到底注册了几个工具、它们有没有真正挂到 MCP 服务器上」</b>。
 * 这两件事必须分开断言 —— 只断言 Bean 存在，无法排除「Bean 有但没注册进服务器」。</p>
 */
@SpringBootTest(properties = { "flowdesk.monitoring.source.mode=demo" })
class MonitoringMcpRegistrationTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void exactlyOneToolCallbackBeanIsRegistered() {
        String[] names = this.context.getBeanNamesForType(ToolCallback.class);

        assertThat(names).as("本模块只注册一个工具").hasSize(1);
        assertThat(this.context.getBean(ToolCallback.class).getToolDefinition().name())
                .isEqualTo("monitoring_snapshot_get");
    }

    @Test
    void theToolIsActuallyAttachedToTheMcpServer() {
        McpSyncServer server = this.context.getBean(McpSyncServer.class);

        List<McpSchema.Tool> tools = server.listTools();

        assertThat(tools).extracting(McpSchema.Tool::name)
                .as("Bean 存在不等于已注册到服务器：这里直接看服务器自己的工具表")
                .containsExactly("monitoring_snapshot_get");
        assertThat(tools.get(0).inputSchema().required()).containsExactly("assetId");
    }

    @Test
    void noForeignAiInfrastructureLeaksIntoThisContext() {
        // 本模块只依赖 flowdesk-shared：application / bootstrap 的类根本不在它的编译类路径上
        // （这一点由「想引用它们就编译不过」本身证明），这里再从容器视角确认一次。
        List<String> beanNames = List.of(this.context.getBeanDefinitionNames()).stream()
                .map(name -> name.toLowerCase(java.util.Locale.ROOT))
                .toList();

        assertThat(beanNames)
                .as("监控 MCP 服务不装配任何 ChatClient / 检索用例：MCP 工具不会被全局注册到 DeepSeek")
                .noneMatch(name -> name.contains("chatclient")
                        || name.contains("chatmodel")
                        || name.contains("retrieveknowledge")
                        || name.contains("knowledgeanswer"));
    }
}
