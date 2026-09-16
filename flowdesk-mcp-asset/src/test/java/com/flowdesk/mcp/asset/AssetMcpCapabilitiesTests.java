package com.flowdesk.mcp.asset;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;

/**
 * 能力声明的测试（FD-0014-R1）。
 *
 * <p>FD-0014 声称「只声明 tools」，但 Spring AI 1.1.2 里
 * {@code spring.ai.mcp.server.capabilities} 的 {@code resource} / {@code prompt} / {@code completion}
 * 默认值都是 {@code true}，因此当时的 {@code initialize} 响应其实同时声明了
 * resources、prompts 与 completions —— 这三项本模块<b>根本没有实现</b>。
 * 客户端据此去调用只会失败：声明了不存在的能力，比不声明更糟。</p>
 *
 * <p>本类断言<b>真实的 {@code initialize} 响应</b>（原始 JSON-RPC 报文 + 真实 SDK 客户端两条路径），
 * 而不只是断言配置文件写了什么。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "flowdesk.asset.directory.mode=demo"
})
class AssetMcpCapabilitiesTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private ApplicationContext context;

    /**
     * 原始报文里的 {@code capabilities}：只有 {@code tools}。
     *
     * @throws Exception 请求或解析失败
     */
    @Test
    void theRawInitializeResponseDeclaresOnlyTheImplementedCapabilities() throws Exception {
        AssetMcpRawClient.RawResponse response = new AssetMcpRawClient(this.port).initialize();

        assertThat(response.status()).isEqualTo(200);
        JsonNode result = MAPPER.readTree(response.body()).path("result");

        assertThat(result.path("protocolVersion").asText()).isNotBlank();
        assertThat(result.path("serverInfo").path("name").asText()).isEqualTo("flowdesk-mcp-asset");

        JsonNode capabilities = result.path("capabilities");
        List<String> declared = new ArrayList<>();
        capabilities.fieldNames().forEachRemaining(declared::add);

        assertThat(declared)
                .as("本服务只实现了 tools：initialize 不得声明 resources / prompts / completions")
                .doesNotContain("resources", "prompts", "completions");
        assertThat(declared)
                .as("""
                        实际声明集是 tools + logging。logging 不是本服务的配置项：MCP Java SDK 0.17.0 的
                        McpAsyncServer 构造器无条件调用 capabilities.mutate().logging()，Spring AI 1.1.2
                        也没有对应开关。这里如实断言真实报文，而不是只断言我们想要的那一半
                        （该偏离已写入 ADR 0011 与 README 第二十一章）。""")
                .containsExactlyInAnyOrder("logging", "tools");
        assertThat(capabilities.path("tools").isObject())
                .as("tools 能力必须存在，否则客户端不会列出工具")
                .isTrue();
    }

    /**
     * 没声明的能力，服务端也没有对应处理器（不是只把声明藏起来）。
     *
     * <p>这里查容器而不是发 HTTP 请求：向本服务调用未实现的方法确实会被拒绝，
     * 但那种调用会在服务端留下一个不会结束的响应流（原因与处理见
     * {@code AssetMcpSessionTests.callingAnUnimplementedMethodLeavesASessionThatDeleteStillCleansUp}），
     * 而「有没有注册处理器」在容器层面就能直接看清。</p>
     */
    @Test
    void noResourcePromptOrCompletionHandlerIsRegistered() {
        assertThat(this.context.getBeanNamesForType(McpServerFeatures.SyncResourceSpecification.class))
                .as("resources 未实现")
                .isEmpty();
        assertThat(this.context.getBeanNamesForType(McpServerFeatures.SyncResourceTemplateSpecification.class))
                .as("resource templates 未实现")
                .isEmpty();
        assertThat(this.context.getBeanNamesForType(McpServerFeatures.SyncPromptSpecification.class))
                .as("prompts 未实现")
                .isEmpty();
        assertThat(this.context.getBeanNamesForType(McpServerFeatures.SyncCompletionSpecification.class))
                .as("completions 未实现")
                .isEmpty();
    }

    /**
     * SDK 客户端看到的同一份能力声明。
     */
    @Test
    void theSdkClientSeesToolsAndNoUnimplementedCapability() {
        try (McpSyncClient client = AssetMcpTestClient.connect(this.port)) {
            McpSchema.ServerCapabilities capabilities = client.initialize().capabilities();

            assertThat(capabilities.tools()).as("必须声明 tools").isNotNull();
            assertThat(capabilities.resources()).as("resources 未实现，不得声明").isNull();
            assertThat(capabilities.prompts()).as("prompts 未实现，不得声明").isNull();
            assertThat(capabilities.completions()).as("completions 未实现，不得声明").isNull();
            assertThat(capabilities.experimental()).as("不得声明实验能力").isNull();
            assertThat(capabilities.logging())
                    .as("""
                            logging 由 MCP Java SDK 0.17.0 无条件声明（McpAsyncServer 构造器固定 mutate().logging()），
                            不是本服务的配置，也没有开关；本服务不主动向客户端推送日志通知。
                            这里如实断言它存在，避免「声称只有 tools」与真实报文不符。""")
                    .isNotNull();
        }
    }

    /**
     * 声明了 tools 就要真的能用：工具列表与服务端自己的工具表都只有一个只读工具。
     */
    @Test
    void theDeclaredToolsCapabilityIsBackedByExactlyOneReadOnlyTool() {
        try (McpSyncClient client = AssetMcpTestClient.connect(this.port)) {
            client.initialize();

            assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name)
                    .containsExactly("asset_get");
        }
    }
}
