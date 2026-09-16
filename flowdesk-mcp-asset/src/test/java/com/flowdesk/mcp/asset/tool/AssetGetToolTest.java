package com.flowdesk.mcp.asset.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowdesk.mcp.asset.directory.AssetDirectory;
import com.flowdesk.mcp.asset.directory.AssetRecord;
import com.flowdesk.mcp.asset.directory.AssetSource;
import com.flowdesk.mcp.asset.directory.DemoAssetDirectory;
import com.flowdesk.mcp.asset.directory.UnavailableAssetDirectory;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

/**
 * {@code asset_get} 的载荷契约单元测试（FD-0014）。
 *
 * <p>这些用例直接驱动工具回调，覆盖「输入 → 载荷 / 失败码」的全部形状，跑得快、失败定位准。
 * 它们<b>不</b>代替 MCP 协议验收（那是 {@code AssetMcpProtocolTests} 的职责，用真实 SDK 客户端
 * 走 Streamable HTTP），而是把内容契约钉死。</p>
 */
class AssetGetToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolCallback demo = new AssetGetTool(new DemoAssetDirectory());

    // ---------- 工具描述与输入 schema ----------

    @Test
    void theToolDefinitionNamesAndSchemaAreStable() throws Exception {
        assertThat(this.demo.getToolDefinition().name()).isEqualTo("asset_get");
        assertThat(this.demo.getToolDefinition().description()).contains("只读").contains("DEMO");

        JsonNode schema = MAPPER.readTree(this.demo.getToolDefinition().inputSchema());
        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("properties").fieldNames()).toIterable().containsExactly("assetId");
        assertThat(schema.path("properties").path("assetId").path("type").asText()).isEqualTo("string");
        assertThat(schema.path("required")).hasSize(1);
        assertThat(schema.path("required").get(0).asText()).isEqualTo("assetId");
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
    }

    // ---------- 命中 ----------

    @Test
    void aHitReturnsOnlyTheNecessaryFields() throws Exception {
        JsonNode payload = MAPPER.readTree(this.demo.call("{\"assetId\":\"AST-900002\"}"));

        assertThat(payload.path("assetId").asText()).isEqualTo("AST-900002");
        assertThat(payload.path("assetType").asText()).isEqualTo("NETWORK_DEVICE");
        assertThat(payload.path("status").asText()).isEqualTo("MAINTENANCE");
        assertThat(payload.path("source").asText()).isEqualTo("DEMO");
        assertThat(payload.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("assetId", "assetType", "status", "source");
    }

    // ---------- 合法但不存在 ----------

    @Test
    void aValidButUnknownIdReturnsANotFoundPayload() throws Exception {
        JsonNode payload = MAPPER.readTree(this.demo.call("{\"assetId\":\"AST-123456\"}"));

        assertThat(payload.path("assetId").asText()).isEqualTo("AST-123456");
        assertThat(payload.path("found").asBoolean()).isFalse();
        assertThat(payload.path("error").asText()).isEqualTo("ASSET_NOT_FOUND");
        assertThat(payload.path("source").asText()).isEqualTo("DEMO");
    }

    // ---------- 非法输入 ----------

    @Test
    void invalidInputFailsWithTheFixedInvalidAssetIdPayload() throws Exception {
        for (String toolInput : new String[] { "{\"assetId\":\"\"}", "{\"assetId\":\"  \"}",
                "{\"assetId\":\"AST-1\"}", "{\"assetId\":\"ast-900001\"}", "{\"assetId\":900001}",
                "{\"assetId\":null}", "{}", "not-json", "{\"other\":\"AST-900001\"}" }) {

            Throwable thrown = catchThrowable(() -> this.demo.call(toolInput));

            assertThat(thrown).as("toolInput=%s", toolInput).isInstanceOf(AssetToolException.class);
            assertThat(((AssetToolException) thrown).error())
                    .as("toolInput=%s", toolInput)
                    .isEqualTo(AssetToolError.INVALID_ASSET_ID);
            JsonNode payload = MAPPER.readTree(thrown.getMessage());
            assertThat(payload.path("error").asText()).isEqualTo("INVALID_ASSET_ID");
            assertThat(payload.path("message").asText()).isEqualTo("assetId 必须形如 AST-000001（AST- 加 6 位数字）");
        }
    }

    @Test
    void aNullToolInputIsRejected() {
        assertThatThrownBy(() -> this.demo.call(null))
                .isInstanceOf(AssetToolException.class)
                .hasMessageContaining("INVALID_ASSET_ID");
    }

    // ---------- 数据源不可用 / 内部异常 ----------

    @Test
    void anUnavailableSourceFailsWithTheFixedSourcePayload() throws Exception {
        ToolCallback unavailable = new AssetGetTool(new UnavailableAssetDirectory());

        Throwable thrown = catchThrowable(() -> unavailable.call("{\"assetId\":\"AST-900001\"}"));

        assertThat(thrown).isInstanceOf(AssetToolException.class);
        assertThat(((AssetToolException) thrown).error()).isEqualTo(AssetToolError.ASSET_SOURCE_UNAVAILABLE);
        JsonNode payload = MAPPER.readTree(thrown.getMessage());
        assertThat(payload.path("error").asText()).isEqualTo("ASSET_SOURCE_UNAVAILABLE");
        assertThat(payload.path("message").asText()).isEqualTo("资产数据源当前不可用");
    }

    @Test
    void anUnexpectedDirectoryFailureIsSanitizedToSourceUnavailable() {
        String sentinel = "sentinel-internal-detail-C:/secret/asset-db.properties";
        ToolCallback leaky = new AssetGetTool(new FailingAssetDirectory(
                new IllegalStateException(sentinel)));

        Throwable thrown = catchThrowable(() -> leaky.call("{\"assetId\":\"AST-900001\"}"));

        assertThat(thrown).isInstanceOf(AssetToolException.class);
        assertThat(thrown.getMessage())
                .as("内部细节一律不进响应内容")
                .doesNotContain(sentinel)
                .doesNotContain("C:/")
                .doesNotContain("IllegalStateException")
                .doesNotContain("AST-900001")
                .contains("ASSET_SOURCE_UNAVAILABLE");
    }

    @Test
    void anEmptyDirectoryReportsNotFoundTogetherWithItsSource() throws Exception {
        ToolCallback emptyDirectory = new AssetGetTool(new EmptyAssetDirectory());

        JsonNode payload = MAPPER.readTree(emptyDirectory.call("{\"assetId\":\"AST-900001\"}"));

        assertThat(payload.path("error").asText()).isEqualTo("ASSET_NOT_FOUND");
        assertThat(payload.path("source").asText())
                .as("「未找到」也要带血缘：是哪个目录说没有")
                .isEqualTo("DEMO");
    }

    /** 永远返回空结果的目录替身。 */
    private static final class EmptyAssetDirectory implements AssetDirectory {

        @Override
        public Optional<AssetRecord> findById(String assetId) {
            return Optional.empty();
        }

        @Override
        public AssetSource source() {
            return AssetSource.DEMO;
        }
    }

    /** 永远抛同一个异常的目录替身。 */
    private static final class FailingAssetDirectory implements AssetDirectory {

        private final RuntimeException failure;

        FailingAssetDirectory(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public Optional<AssetRecord> findById(String assetId) {
            throw this.failure;
        }

        @Override
        public AssetSource source() {
            throw this.failure;
        }
    }

    // ---------- 内容形状 ----------

    @Test
    void everyErrorContentIsItselfValidJson() throws Exception {
        for (AssetToolError error : AssetToolError.values()) {
            JsonNode payload = MAPPER.readTree(error.content());

            assertThat(payload.path("error").asText()).as("error=%s", error).isEqualTo(error.name());
            assertThat(payload.path("message").asText()).as("error=%s", error).isEqualTo(error.message());
        }
    }

    @Test
    void theDemoDirectoryNeverClaimsToBeRealData() {
        for (AssetRecord record : new AssetRecord[] {
                new DemoAssetDirectory().findById("AST-900001").orElseThrow(),
                new DemoAssetDirectory().findById("AST-900002").orElseThrow(),
                new DemoAssetDirectory().findById("AST-900003").orElseThrow() }) {

            assertThat(record.source()).isEqualTo(AssetSource.DEMO);
        }
    }
}
