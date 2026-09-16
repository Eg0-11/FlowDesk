package com.flowdesk.mcp.asset;

import static com.flowdesk.mcp.asset.AssetMcpTestClient.call;
import static com.flowdesk.mcp.asset.AssetMcpTestClient.payload;
import static com.flowdesk.mcp.asset.AssetMcpTestClient.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 资产 MCP 服务的<b>真实协议</b>验收测试（FD-0014）。
 *
 * <p>用官方 MCP Java SDK 客户端通过 Streamable HTTP 连接一个真实启动、随机端口的服务，
 * 完成 {@code initialize} → {@code tools/list} → {@code tools/call} 的完整握手与调用。
 * 这<b>不是</b> {@code contextLoads}，也不是直接调用 Java 方法：请求走真实 HTTP + JSON-RPC。</p>
 *
 * <p>演示模式在这里被显式打开：只有存在数据源，才能真实验证「命中」这条路径。
 * 所有结果的 {@code source} 都必须是 {@code DEMO} —— 演示数据不得冒充真实资产。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "flowdesk.asset.directory.mode=demo"
})
class AssetMcpProtocolTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @LocalServerPort
    private int port;

    private McpSyncClient client;

    @BeforeEach
    void connect() {
        this.client = AssetMcpTestClient.connect(this.port);
    }

    @AfterEach
    void disconnect() {
        if (this.client != null) {
            this.client.close();
        }
    }

    // ---------- initialize / tools/list ----------

    @Test
    void initializeSucceedsAndPublishesTheConfiguredServerIdentity() {
        McpSchema.InitializeResult result = this.client.initialize();

        assertThat(this.client.isInitialized()).isTrue();
        assertThat(result.serverInfo().name()).isEqualTo("flowdesk-mcp-asset");
        assertThat(result.protocolVersion()).isNotBlank();
        assertThat(result.capabilities().tools())
                .as("必须声明 tools 能力，否则客户端不会列出工具")
                .isNotNull();

        // FD-0014-R1：只声明真正实现的能力（原始报文级断言见 AssetMcpCapabilitiesTests）
        assertThat(result.capabilities().resources())
                .as("resources 未实现，不得声明")
                .isNull();
        assertThat(result.capabilities().prompts())
                .as("prompts 未实现，不得声明")
                .isNull();
        assertThat(result.capabilities().completions())
                .as("completions 未实现，不得声明")
                .isNull();
    }

    @Test
    void toolsListContainsExactlyTheReadOnlyAssetGetToolWithARequiredAssetId() {
        this.client.initialize();

        List<McpSchema.Tool> tools = this.client.listTools().tools();

        assertThat(tools).extracting(McpSchema.Tool::name).containsExactly("asset_get");

        McpSchema.Tool tool = tools.get(0);
        assertThat(tool.description())
                .as("描述必须说明它是只读的，并说明 DEMO 的含义")
                .contains("只读")
                .contains("DEMO");

        McpSchema.JsonSchema schema = tool.inputSchema();
        assertThat(schema.type()).isEqualTo("object");
        assertThat(schema.properties()).containsOnlyKeys("assetId");
        assertThat(schema.required()).as("assetId 必须是必填").containsExactly("assetId");
        assertThat(schema.additionalProperties()).isFalse();
        assertThat(((Map<?, ?>) schema.properties().get("assetId")).get("type")).isEqualTo("string");

        assertThat(tools).extracting(McpSchema.Tool::name)
                .as("不得出现任何写工具（工具名以写动词结尾）")
                .noneMatch(name -> name.matches(".*[_-](add|create|update|set|put|patch|delete|remove|write)$"));
    }

    // ---------- tools/call：命中 ----------

    @Test
    void aDemoAssetIsReturnedWithTheDemoSource() throws Exception {
        this.client.initialize();

        McpSchema.CallToolResult result = call(this.client, "asset_get", Map.of("assetId", "AST-900001"));

        assertThat(result.isError()).as("命中是成功的调用").isFalse();
        JsonNode payload = payload(result);
        assertThat(payload.path("assetId").asText()).isEqualTo("AST-900001");
        assertThat(payload.path("assetType").asText()).isEqualTo("SERVER");
        assertThat(payload.path("status").asText()).isEqualTo("IN_SERVICE");
        assertThat(payload.path("source").asText()).as("演示数据必须明确标识来源").isEqualTo("DEMO");
        assertThat(payload.fieldNames()).toIterable()
                .as("成功结果只含必要字段")
                .containsExactlyInAnyOrder("assetId", "assetType", "status", "source");
    }

    @Test
    void everyDemoAssetIsMarkedAsDemo() throws Exception {
        this.client.initialize();

        for (String assetId : List.of("AST-900001", "AST-900002", "AST-900003")) {
            JsonNode payload = payload(call(this.client, "asset_get", Map.of("assetId", assetId)));

            assertThat(payload.path("assetId").asText()).as("assetId=%s", assetId).isEqualTo(assetId);
            assertThat(payload.path("source").asText()).as("assetId=%s", assetId).isEqualTo("DEMO");
        }
    }

    // ---------- tools/call：合法但不存在 ----------

    @Test
    void aValidButUnknownAssetReturnsNotFoundWithoutBeingAToolError() throws Exception {
        this.client.initialize();

        McpSchema.CallToolResult result = call(this.client, "asset_get", Map.of("assetId", "AST-000000"));

        assertThat(result.isError()).as("「未找到」是查询正常给出的答案，不是工具执行失败").isFalse();
        JsonNode payload = payload(result);
        assertThat(payload.path("assetId").asText()).isEqualTo("AST-000000");
        assertThat(payload.path("found").asBoolean()).isFalse();
        assertThat(payload.path("error").asText()).isEqualTo("ASSET_NOT_FOUND");
        assertThat(payload.path("source").asText()).isEqualTo("DEMO");
    }

    // ---------- tools/call：非法输入 ----------

    @Test
    void invalidAssetIdsAreRejectedAsToolErrors() throws Exception {
        this.client.initialize();

        for (String invalid : List.of("", "   ", "AST-1", "AST-12345", "AST-1234567", "ast-900001",
                "AST-90000A", "900001", "AST-900001 ", "AST_900001", "A".repeat(200))) {

            McpSchema.CallToolResult result = call(this.client, "asset_get", Map.of("assetId", invalid));

            assertThat(result.isError()).as("assetId=[%s]", invalid).isTrue();
            assertThat(payload(result).path("error").asText())
                    .as("assetId=[%s]", invalid)
                    .isEqualTo("INVALID_ASSET_ID");
            assertThat(text(result))
                    .as("错误内容不得回显输入")
                    .doesNotContain("ast-900001")
                    .doesNotContain("A".repeat(20));
        }
    }

    @Test
    void aMissingOrNonStringAssetIdIsRejectedAsAToolError() throws Exception {
        this.client.initialize();

        McpSchema.CallToolResult missing = call(this.client, "asset_get", Map.of());
        assertThat(missing.isError()).isTrue();
        assertThat(payload(missing).path("error").asText()).isEqualTo("INVALID_ASSET_ID");

        McpSchema.CallToolResult wrongType = call(this.client, "asset_get", Map.of("assetId", 900001));
        assertThat(wrongType.isError()).isTrue();
        assertThat(payload(wrongType).path("error").asText()).isEqualTo("INVALID_ASSET_ID");
    }

    @Test
    void aWriteLookingToolNameIsRejectedByTheServer() {
        this.client.initialize();

        Throwable thrown = catchThrowable(
                () -> AssetMcpTestClient.call(this.client, "asset_delete", Map.of("assetId", "AST-900001")));

        assertThat(thrown)
                .as("未注册的工具名必须被服务端拒绝（这同时证明本服务没有写工具）")
                .isNotNull();
    }

    // ---------- FD-0014-R1：执行校验必须与公布的 input schema 一致 ----------

    @Test
    void anExtraArgumentIsRejectedInsteadOfBeingSilentlyIgnored() throws Exception {
        this.client.initialize();

        McpSchema.CallToolResult result = call(this.client, "asset_get",
                Map.of("assetId", "AST-900001", "extra", "sentinel-extra-argument"));

        assertThat(result.isError())
                .as("schema 声明 additionalProperties=false：多传字段必须失败，不能被忽略后照常返回资产")
                .isTrue();
        assertThat(payload(result).path("error").asText()).isEqualTo("INVALID_ASSET_ID");
        assertThat(text(result))
                .as("固定内容：不回显多余字段的值，也不返回任何资产数据")
                .doesNotContain("sentinel-extra-argument")
                .doesNotContain("SERVER")
                .doesNotContain("IN_SERVICE")
                .doesNotContain("DEMO");
    }

    @Test
    void aWrongNamedArgumentIsRejected() throws Exception {
        this.client.initialize();

        McpSchema.CallToolResult result = call(this.client, "asset_get", Map.of("asset_id", "AST-900001"));

        assertThat(result.isError()).isTrue();
        assertThat(payload(result).path("error").asText()).isEqualTo("INVALID_ASSET_ID");
    }

    @Test
    void nonObjectArgumentsAreRejectedBeforeTheToolIsEverCalled() throws Exception {
        AssetMcpRawClient raw = new AssetMcpRawClient(this.port);
        String sessionId = raw.initialize().sessionId();

        // arguments 不是 JSON 对象（这里是字符串）：闸门在用 200 + -32602 明确拒绝，
        // 工具与资产目录都不会被调用（详细断言见 AssetMcpTransportErrorTests）
        AssetMcpRawClient.RawResponse response = raw.post("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call",\
                "params":{"name":"asset_get","arguments":"AST-900001"}}""", sessionId);

        assertThat(response.status())
                .as("协议级输入错误用 200 + JSON-RPC 错误表达，不再是 500 空响应")
                .isEqualTo(200);
        assertThat(response.body())
                .as("拒绝响应不得回显取值、不得返回资产数据、不得夹带内部信息")
                .doesNotContain("SERVER")
                .doesNotContain("IN_SERVICE")
                .doesNotContain("DEMO")
                .doesNotContain("AST-900001")
                .doesNotContain("stackTrace")
                .doesNotContain("io.modelcontextprotocol")
                .doesNotContain("com.flowdesk")
                .contains("-32602");

        assertThat(MAPPER.readTree(response.body()).path("error").path("message").asText())
                .as("错误必须可解析且稳定")
                .isEqualTo("Invalid params: arguments must be a JSON object");
    }

    @Test
    void aMalformedJsonRpcEnvelopeIsRejectedWithoutEchoingTheInput() throws Exception {
        AssetMcpRawClient raw = new AssetMcpRawClient(this.port);
        String sessionId = raw.initialize().sessionId();

        // 报文本身畸形，但里面带着一个合法 assetId：拒绝时绝不能把它回显出来
        AssetMcpRawClient.RawResponse response = raw.post("{\"assetId\":\"AST-900001\",", sessionId);

        assertThat(response.status()).as("畸形报文必须被拒绝").isEqualTo(400);
        assertThat(response.body())
                .as("拒绝体不得回显输入")
                .doesNotContain("AST-900001");

        // FD-0014-R2：错误响应必须是固定、可解析、不含任何内部信息的 JSON-RPC 错误。
        // 这里做的是**负向泄漏断言**：SDK 曾经把 McpError（RuntimeException）直接当响应体，
        // Jackson 按 Throwable 序列化，于是带出 stackTrace、类名、文件名与行号。
        assertThat(response.body())
                .as("不得出现堆栈、异常类名、内部路径或原始异常消息")
                .doesNotContain("stackTrace")
                .doesNotContain("cause")
                .doesNotContain("lineNumber")
                .doesNotContain("nativeMethod")
                .doesNotContain("Exception")
                .doesNotContain("java.")
                .doesNotContain("io.modelcontextprotocol")
                .doesNotContain("org.springframework")
                .doesNotContain("com.flowdesk")
                .doesNotContain(".java")
                .doesNotContain("Invalid message format");

        assertThat(MAPPER.readTree(response.body()).path("error").path("code").asInt())
                .as("固定错误码：解析失败")
                .isEqualTo(-32700);
    }
}
