package com.flowdesk.mcp.monitoring.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowdesk.mcp.monitoring.snapshot.AssetId;
import com.flowdesk.mcp.monitoring.snapshot.DemoSnapshotSource;
import com.flowdesk.mcp.monitoring.snapshot.HealthState;
import com.flowdesk.mcp.monitoring.snapshot.MonitoringSnapshot;
import com.flowdesk.mcp.monitoring.snapshot.MonitoringSnapshotSource;
import com.flowdesk.mcp.monitoring.snapshot.SnapshotOrigin;
import com.flowdesk.mcp.monitoring.snapshot.UnavailableSnapshotSource;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

/**
 * {@code monitoring_snapshot_get} 的载荷契约单元测试（FD-0015）。
 *
 * <p>这些用例直接驱动工具回调，覆盖「输入 → 载荷 / 失败码」的全部形状，跑得快、失败定位准。
 * 它们<b>不</b>代替 MCP 协议验收（那是 {@code MonitoringMcpProtocolTests} 的职责，用真实 SDK 客户端
 * 走 Streamable HTTP），而是把内容契约与 schema/执行校验的一致性钉死。</p>
 */
class MonitoringSnapshotGetToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolCallback demo = new MonitoringSnapshotGetTool(new DemoSnapshotSource());

    // ---------- 工具描述与输入 schema ----------

    @Test
    void theToolDefinitionNamesAndSchemaAreStable() throws Exception {
        assertThat(this.demo.getToolDefinition().name()).isEqualTo("monitoring_snapshot_get");
        assertThat(this.demo.getToolDefinition().description()).contains("只读").contains("DEMO");

        JsonNode schema = MAPPER.readTree(this.demo.getToolDefinition().inputSchema());
        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("properties").fieldNames()).toIterable().containsExactly("assetId");
        assertThat(schema.path("properties").path("assetId").path("type").asText()).isEqualTo("string");
        assertThat(schema.path("required")).hasSize(1);
        assertThat(schema.path("required").get(0).asText()).isEqualTo("assetId");
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
    }

    @Test
    void thePublishedSchemaCarriesTheSameLimitsAsTheRuntimeValidation() throws Exception {
        JsonNode assetId = MAPPER.readTree(this.demo.getToolDefinition().inputSchema())
                .path("properties").path("assetId");

        assertThat(assetId.path("maxLength").asInt())
                .as("schema 的 maxLength 必须就是 AssetId 的长度上限")
                .isEqualTo(AssetId.MAX_LENGTH);

        String pattern = assetId.path("pattern").asText();
        assertThat(pattern)
                .as("JSON Schema 的 pattern 是部分匹配：必须锚定")
                .isEqualTo(AssetId.SCHEMA_PATTERN);
        assertThat(AssetId.SCHEMA_PATTERN).isEqualTo("^" + AssetId.PATTERN.pattern() + "$");

        Pattern compiled = Pattern.compile(pattern);
        assertThat(compiled.matcher("AST-900001").matches()).isTrue();
        assertThat(AssetId.isValid("AST-900001")).isTrue();

        for (String rejected : new String[] { "AST-1", "ast-900001", "AST-900001 ", " AST-900001", "AST-9000011",
                "XAST-900001", "AST-900001X" }) {
            assertThat(compiled.matcher(rejected).matches()).as("schema 必须拒绝 [%s]", rejected).isFalse();
            assertThat(AssetId.isValid(rejected)).as("执行校验也必须拒绝 [%s]", rejected).isFalse();
        }
    }

    // ---------- 命中 ----------

    @Test
    void aHitReturnsTheFixedFieldOrderAndValues() throws Exception {
        JsonNode payload = MAPPER.readTree(this.demo.call("{\"assetId\":\"AST-900001\"}"));

        assertThat(payload.path("assetId").asText()).isEqualTo("AST-900001");
        assertThat(payload.path("observedAt").asText()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(payload.path("health").asText()).isEqualTo("DEGRADED");
        assertThat(payload.path("cpuUtilizationPercent").asInt()).isEqualTo(92);
        assertThat(payload.path("memoryUtilizationPercent").asInt()).isEqualTo(68);
        assertThat(payload.path("activeAlertCount").asInt()).isEqualTo(1);
        assertThat(payload.path("source").asText()).isEqualTo("DEMO");
        assertThat(payload.fieldNames()).toIterable()
                .containsExactly("assetId", "observedAt", "health", "cpuUtilizationPercent",
                        "memoryUtilizationPercent", "activeAlertCount", "source");
    }

    @Test
    void theOutputIsDeterministic() {
        assertThat(this.demo.call("{\"assetId\":\"AST-900001\"}"))
                .as("固定数据 + 固定时间：同样输入逐字节相同")
                .isEqualTo(this.demo.call("{\"assetId\":\"AST-900001\"}"));
    }

    @Test
    void theHitNeverCarriesHostOrCredentialLikeFields() throws Exception {
        String body = this.demo.call("{\"assetId\":\"AST-900002\"}");

        assertThat(body)
                .doesNotContain("host")
                .doesNotContain("ip")
                .doesNotContain("address")
                .doesNotContain("token")
                .doesNotContain("password")
                .doesNotContain("credential");
    }

    /**
     * 命中结果的 {@code source} 必须来自<b>记录本身</b>，而不是数据源自己的 {@code origin()}：
     * 快照自带血缘，工具如实转述即可，不去猜也不去覆盖。
     *
     * @throws Exception 载荷解析失败
     */
    @Test
    void aHitReportsTheOriginCarriedByTheRecordItself() throws Exception {
        ToolCallback misleadingSource = new MonitoringSnapshotGetTool(new MisleadingOriginSnapshotSource());

        JsonNode payload = MAPPER.readTree(misleadingSource.call("{\"assetId\":\"AST-900001\"}"));

        assertThat(payload.path("source").asText())
                .as("记录自带 DEMO 血缘时，即使数据源自称 REAL 也必须如实输出 DEMO")
                .isEqualTo("DEMO");
    }

    // ---------- 合法但未找到 ----------

    @Test
    void aValidButUnknownIdReturnsANotFoundPayloadWithOrigin() throws Exception {
        JsonNode payload = MAPPER.readTree(this.demo.call("{\"assetId\":\"AST-900003\"}"));

        assertThat(payload.path("assetId").asText()).isEqualTo("AST-900003");
        assertThat(payload.path("found").asBoolean()).isFalse();
        assertThat(payload.path("error").asText()).isEqualTo("MONITORING_SNAPSHOT_NOT_FOUND");
        assertThat(payload.path("message").asText()).isEqualTo("未找到该资产的监控快照");
        assertThat(payload.path("source").asText()).as("「未找到」也要带血缘").isEqualTo("DEMO");
        assertThat(payload.fieldNames()).toIterable()
                .containsExactly("assetId", "found", "error", "message", "source");
    }

    @Test
    void anEmptySourceReportsNotFoundTogetherWithItsOrigin() throws Exception {
        ToolCallback emptySource = new MonitoringSnapshotGetTool(new EmptySnapshotSource());

        JsonNode payload = MAPPER.readTree(emptySource.call("{\"assetId\":\"AST-900001\"}"));

        assertThat(payload.path("error").asText()).isEqualTo("MONITORING_SNAPSHOT_NOT_FOUND");
        assertThat(payload.path("source").asText()).isEqualTo("DEMO");
    }

    // ---------- 非法输入 ----------

    @Test
    void invalidInputFailsWithTheFixedInvalidAssetIdPayload() throws Exception {
        for (String toolInput : new String[] { "{\"assetId\":\"\"}", "{\"assetId\":\"  \"}",
                "{\"assetId\":\"AST-1\"}", "{\"assetId\":\"ast-900001\"}", "{\"assetId\":900001}",
                "{\"assetId\":null}", "{}", "not-json", "{\"other\":\"AST-900001\"}" }) {

            Throwable thrown = catchThrowable(() -> this.demo.call(toolInput));

            assertThat(thrown).as("toolInput=%s", toolInput).isInstanceOf(MonitoringToolException.class);
            assertThat(((MonitoringToolException) thrown).error())
                    .as("toolInput=%s", toolInput)
                    .isEqualTo(MonitoringToolError.INVALID_ASSET_ID);
            JsonNode payload = MAPPER.readTree(thrown.getMessage());
            assertThat(payload.path("error").asText()).isEqualTo("INVALID_ASSET_ID");
            assertThat(payload.path("message").asText())
                    .isEqualTo("assetId 必须形如 AST-000001（AST- 加 6 位数字）");
        }
    }

    @Test
    void extraFieldsAreRejectedInsteadOfBeingSilentlyIgnored() throws Exception {
        for (String toolInput : new String[] {
                "{\"assetId\":\"AST-900001\",\"extra\":\"sentinel-extra\"}",
                "{\"assetId\":\"AST-900001\",\"note\":null}",
                "{\"assetId\":\"AST-900001\",\"assetId2\":\"AST-900002\"}",
                "{\"extra\":\"sentinel-extra\",\"assetId\":\"AST-900001\"}",
                "{\"asset_id\":\"AST-900001\"}",
                "{\"assetid\":\"AST-900001\"}" }) {

            Throwable thrown = catchThrowable(() -> this.demo.call(toolInput));

            assertThat(thrown).as("toolInput=%s", toolInput).isInstanceOf(MonitoringToolException.class);
            assertThat(thrown.getMessage())
                    .as("固定内容，不回显输入与多余字段的值")
                    .doesNotContain("sentinel-extra")
                    .doesNotContain("AST-900001");
        }
    }

    @Test
    void aNonObjectOrTrailingJsonInputIsRejected() throws Exception {
        for (String toolInput : new String[] {
                "[\"AST-900001\"]", "\"AST-900001\"", "900001", "true", "null",
                "{\"assetId\":\"AST-900001\"}{\"assetId\":\"AST-900002\"}",
                "{\"assetId\":[\"AST-900001\"]}", "{\"assetId\":{\"value\":\"AST-900001\"}}" }) {

            Throwable thrown = catchThrowable(() -> this.demo.call(toolInput));

            assertThat(thrown).as("toolInput=%s", toolInput).isInstanceOf(MonitoringToolException.class);
            assertThat(((MonitoringToolException) thrown).error())
                    .as("toolInput=%s", toolInput)
                    .isEqualTo(MonitoringToolError.INVALID_ASSET_ID);
        }
    }

    @Test
    void aNullToolInputIsRejected() {
        assertThatThrownBy(() -> this.demo.call(null))
                .isInstanceOf(MonitoringToolException.class)
                .hasMessageContaining("INVALID_ASSET_ID");
    }

    @Test
    void theStrictShapeIsAlsoEnforcedWhenTheSourceIsUnavailable() {
        ToolCallback unavailable = new MonitoringSnapshotGetTool(new UnavailableSnapshotSource());

        Throwable thrown = catchThrown(() -> unavailable.call("{\"assetId\":\"AST-900001\",\"extra\":\"x\"}"));

        assertThat(((MonitoringToolException) thrown).error())
                .as("输入问题先于数据源问题")
                .isEqualTo(MonitoringToolError.INVALID_ASSET_ID);
    }

    // ---------- 数据源不可用 / 内部异常 ----------

    @Test
    void anUnavailableSourceFailsWithTheFixedSourcePayload() throws Exception {
        ToolCallback unavailable = new MonitoringSnapshotGetTool(new UnavailableSnapshotSource());

        Throwable thrown = catchThrowable(() -> unavailable.call("{\"assetId\":\"AST-900001\"}"));

        assertThat(thrown).isInstanceOf(MonitoringToolException.class);
        assertThat(((MonitoringToolException) thrown).error())
                .isEqualTo(MonitoringToolError.MONITORING_SOURCE_UNAVAILABLE);
        JsonNode payload = MAPPER.readTree(thrown.getMessage());
        assertThat(payload.path("error").asText()).isEqualTo("MONITORING_SOURCE_UNAVAILABLE");
        assertThat(payload.path("message").asText()).isEqualTo("监控数据源当前不可用");
    }

    @Test
    void anUnexpectedSourceFailureIsSanitizedToSourceUnavailable() {
        String sentinel = "sentinel-internal-detail-C:/secret/monitoring-db.properties";
        ToolCallback leaky = new MonitoringSnapshotGetTool(new FailingSnapshotSource(
                new IllegalStateException(sentinel)));

        Throwable thrown = catchThrowable(() -> leaky.call("{\"assetId\":\"AST-900001\"}"));

        assertThat(thrown).isInstanceOf(MonitoringToolException.class);
        assertThat(thrown.getMessage())
                .as("内部细节一律不进响应内容")
                .doesNotContain(sentinel)
                .doesNotContain("C:/")
                .doesNotContain("IllegalStateException")
                .doesNotContain("AST-900001")
                .contains("MONITORING_SOURCE_UNAVAILABLE");
    }

    // ---------- 内容形状 ----------

    @Test
    void everyErrorContentIsItselfValidJson() throws Exception {
        for (MonitoringToolError error : MonitoringToolError.values()) {
            JsonNode payload = MAPPER.readTree(error.content());

            assertThat(payload.path("error").asText()).as("error=%s", error).isEqualTo(error.name());
            assertThat(payload.path("message").asText()).as("error=%s", error).isEqualTo(error.message());
            assertThat(error.content())
                    .as("固定文案里不得出现引号与反斜杠，否则内容不再是合法 JSON")
                    .doesNotContain("\\")
                    .doesNotContain("\"message\":\"\"");
        }
    }

    @Test
    void onlyTheTwoContractedErrorCodesExist() {
        assertThat(MonitoringToolError.values()).extracting(Enum::name)
                .containsExactly("INVALID_ASSET_ID", "MONITORING_SOURCE_UNAVAILABLE");
    }

    /** 永远返回空结果的数据源替身。 */
    private static final class EmptySnapshotSource implements MonitoringSnapshotSource {

        @Override
        public Optional<MonitoringSnapshot> findSnapshotById(String assetId) {
            return Optional.empty();
        }

        @Override
        public SnapshotOrigin origin() {
            return SnapshotOrigin.DEMO;
        }
    }

    /** 永远抛同一个异常的数据源替身。 */
    private static final class FailingSnapshotSource implements MonitoringSnapshotSource {

        private final RuntimeException failure;

        FailingSnapshotSource(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public Optional<MonitoringSnapshot> findSnapshotById(String assetId) {
            throw this.failure;
        }

        @Override
        public SnapshotOrigin origin() {
            throw this.failure;
        }
    }

    /** 记录自带 {@code DEMO} 血缘、但数据源自称 {@code REAL} 的替身：用于确认工具不会拿数据源的来源覆盖记录。 */
    private static final class MisleadingOriginSnapshotSource implements MonitoringSnapshotSource {

        @Override
        public Optional<MonitoringSnapshot> findSnapshotById(String assetId) {
            return Optional.of(snapshotOf(assetId));
        }

        @Override
        public SnapshotOrigin origin() {
            return SnapshotOrigin.REAL;
        }
    }

    /** 构造一条固定的快照记录（血缘为 {@code DEMO}）。 */
    private static MonitoringSnapshot snapshotOf(String assetId) {
        return new MonitoringSnapshot(assetId, Instant.parse("2026-01-01T00:00:00Z"), HealthState.UNKNOWN,
                0, 0, 0, SnapshotOrigin.DEMO);
    }

    private static Throwable catchThrown(ThrowingCall call) {
        try {
            call.run();
            throw new AssertionError("预期抛出工具异常，但调用成功了");
        }
        catch (MonitoringToolException ex) {
            return ex;
        }
    }

    /** 允许直接调用的函数式接口。 */
    @FunctionalInterface
    private interface ThrowingCall {

        void run();
    }
}
