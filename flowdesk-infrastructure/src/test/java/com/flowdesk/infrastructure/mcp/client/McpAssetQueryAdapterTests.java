package com.flowdesk.infrastructure.mcp.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowdesk.application.integration.AssetQueryResult;
import com.flowdesk.application.integration.QueryOutcome;
import com.flowdesk.application.integration.QueryFailure;
import com.flowdesk.application.integration.SourceOrigin;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 资产查询适配器的协议与载荷验收（FD-0016）。
 *
 * <p>用<b>真实 MCP SDK 客户端</b>打一个真实的本机 Streamable HTTP 端点（随机端口），
 * 因此覆盖的是 initialize、固定工具调用、会话释放与载荷解析的真实链路，
 * 而不是「mock 掉 McpSyncClient 之后的自说自话」。</p>
 */
class McpAssetQueryAdapterTests {

    private static final String HIT = "{\"assetId\":\"AST-900001\",\"assetType\":\"SERVER\","
            + "\"status\":\"IN_SERVICE\",\"source\":\"DEMO\"}";

    private static final String NOT_FOUND = "{\"assetId\":\"AST-900001\",\"found\":false,"
            + "\"error\":\"ASSET_NOT_FOUND\",\"message\":\"未找到该资产\",\"source\":\"DEMO\"}";

    private ControllableMcpEndpoint endpoint;

    private McpAssetQueryAdapter adapter;

    @BeforeEach
    void startEndpoint() throws Exception {
        this.endpoint = ControllableMcpEndpoint.start();
        this.adapter = new McpAssetQueryAdapter(new McpToolClient(Duration.ofSeconds(2)),
                McpServerEndpoint.of(this.endpoint.baseUrl()));
    }

    @AfterEach
    void stopEndpoint() {
        this.endpoint.close();
    }

    // ---------- 命中 / 未找到 ----------

    @Test
    void aHitIsParsedIntoAnImmutableViewThatKeepsTheSource() {
        this.endpoint.respondWithToolText(HIT);

        AssetQueryResult result = this.adapter.findAsset("AST-900001");

        assertThat(result.outcome()).isEqualTo(QueryOutcome.FOUND);
        assertThat(result.requireAsset().assetId()).isEqualTo("AST-900001");
        assertThat(result.requireAsset().assetType()).isEqualTo("SERVER");
        assertThat(result.requireAsset().status()).isEqualTo("IN_SERVICE");
        assertThat(result.requireAsset().source()).as("不得把 DEMO 提升成 REAL").isEqualTo(SourceOrigin.DEMO);
    }

    @Test
    void aHitDeliveredAsPlainJsonIsAcceptedToo() {
        this.endpoint.respondWithToolTextAsJson(HIT);

        assertThat(this.adapter.findAsset("AST-900001").isFound()).isTrue();
    }

    @Test
    void aValidButAbsentAssetIsNotFoundAndKeepsItsOrigin() {
        this.endpoint.respondWithToolText(NOT_FOUND);

        AssetQueryResult result = this.adapter.findAsset("AST-900001");

        assertThat(result.outcome()).isEqualTo(QueryOutcome.NOT_FOUND);
        assertThat(result.assetId()).isEqualTo("AST-900001");
        assertThat(result.source()).isEqualTo(SourceOrigin.DEMO);
        assertThat(result.isFailed()).as("未找到不是失败").isFalse();
    }

    // ---------- 远端失败 ----------

    @Test
    void aRemoteSourceUnavailableBecomesUnavailable() {
        this.endpoint.respondWithToolError("{\"error\":\"ASSET_SOURCE_UNAVAILABLE\","
                + "\"message\":\"资产数据源当前不可用\"}");

        assertThat(this.adapter.findAsset("AST-900001").failure()).isEqualTo(QueryFailure.UNAVAILABLE);
    }

    @Test
    void aRemoteUnknownErrorCodeBecomesRemoteToolError() {
        this.endpoint.respondWithToolError("{\"error\":\"SOMETHING_ELSE\",\"message\":\"boom\"}");

        assertThat(this.adapter.findAsset("AST-900001").failure()).isEqualTo(QueryFailure.REMOTE_TOOL_ERROR);
    }

    @Test
    void aMalformedRemoteErrorPayloadIsStillARemoteToolErrorAndNeverNotFound() {
        this.endpoint.respondWithToolError("{\"error\":\"ASSET_NOT_FOUND\"}");

        AssetQueryResult result = this.adapter.findAsset("AST-900001");

        assertThat(result.failure()).isEqualTo(QueryFailure.REMOTE_TOOL_ERROR);
        assertThat(result.isNotFound()).as("远端声明失败时绝不能变成「未找到」").isFalse();
    }

    @Test
    void aJsonRpcErrorFromTheRemoteIsARemoteToolError() {
        this.endpoint.respondWithJsonRpcError(-32601, "Method not found: asset_get");

        assertThat(this.adapter.findAsset("AST-900001").failure()).isEqualTo(QueryFailure.REMOTE_TOOL_ERROR);
    }

    @Test
    void anHttpErrorStatusIsUnavailable() {
        this.endpoint.respondWithRaw(500, "application/json", "{\"error\":\"internal\"}");

        assertThat(this.adapter.findAsset("AST-900001").failure()).isEqualTo(QueryFailure.UNAVAILABLE);
    }

    // ---------- 非法响应 ----------

    @ParameterizedTest
    @MethodSource("illegalAssetPayloads")
    void anIllegalResponseIsRejectedAsInvalidResponse(String payload) {
        this.endpoint.respondWithToolText(payload);

        AssetQueryResult result = this.adapter.findAsset("AST-900001");

        assertThat(result.outcome()).as("payload=%s", payload).isEqualTo(QueryOutcome.FAILED);
        assertThat(result.failure()).as("payload=%s", payload).isEqualTo(QueryFailure.INVALID_RESPONSE);
    }

    static Stream<String> illegalAssetPayloads() {
        return Stream.of(
                // 缺字段 / 多字段
                "{\"assetId\":\"AST-900001\",\"assetType\":\"SERVER\",\"source\":\"DEMO\"}",
                "{\"assetId\":\"AST-900001\",\"assetType\":\"SERVER\",\"status\":\"IN_SERVICE\","
                        + "\"source\":\"DEMO\",\"extra\":1}",
                // 类型不对
                "{\"assetId\":900001,\"assetType\":\"SERVER\",\"status\":\"IN_SERVICE\",\"source\":\"DEMO\"}",
                "{\"assetId\":\"AST-900001\",\"assetType\":123,\"status\":\"IN_SERVICE\",\"source\":\"DEMO\"}",
                // 编号错配（把别的资产当成成功返回）
                "{\"assetId\":\"AST-900002\",\"assetType\":\"SERVER\",\"status\":\"IN_SERVICE\",\"source\":\"DEMO\"}",
                "{\"assetId\":\"not-an-asset-id\",\"assetType\":\"SERVER\",\"status\":\"IN_SERVICE\","
                        + "\"source\":\"DEMO\"}",
                // source 缺失 / 为 null / 未知取值
                "{\"assetId\":\"AST-900001\",\"assetType\":\"SERVER\",\"status\":\"IN_SERVICE\"}",
                "{\"assetId\":\"AST-900001\",\"assetType\":\"SERVER\",\"status\":\"IN_SERVICE\",\"source\":null}",
                "{\"assetId\":\"AST-900001\",\"assetType\":\"SERVER\",\"status\":\"IN_SERVICE\","
                        + "\"source\":\"STAGING\"}",
                // 枚举形状不符
                "{\"assetId\":\"AST-900001\",\"assetType\":\"server\",\"status\":\"IN_SERVICE\",\"source\":\"DEMO\"}",
                "{\"assetId\":\"AST-900001\",\"assetType\":\"\",\"status\":\"IN_SERVICE\",\"source\":\"DEMO\"}",
                // 未找到形状不合法
                "{\"assetId\":\"AST-900001\",\"found\":true,\"error\":\"ASSET_NOT_FOUND\","
                        + "\"message\":\"x\",\"source\":\"DEMO\"}",
                "{\"assetId\":\"AST-900001\",\"found\":\"false\",\"error\":\"ASSET_NOT_FOUND\","
                        + "\"message\":\"x\",\"source\":\"DEMO\"}",
                "{\"assetId\":\"AST-900001\",\"found\":false,\"error\":\"ASSET_FOUND\","
                        + "\"message\":\"x\",\"source\":\"DEMO\"}",
                "{\"assetId\":\"AST-900002\",\"found\":false,\"error\":\"ASSET_NOT_FOUND\","
                        + "\"message\":\"x\",\"source\":\"DEMO\"}",
                "{\"assetId\":\"AST-900001\",\"found\":false,\"error\":\"ASSET_NOT_FOUND\","
                        + "\"message\":\"x\",\"source\":null}",
                // 不是对象 / 不是 JSON / 两段 JSON 拼接
                "[]",
                "\"AST-900001\"",
                "not json at all",
                "{\"assetId\":\"AST-900001\"}{\"assetId\":\"AST-900002\"}",
                "");
    }

    @Test
    void aNonEmptyStructuredContentIsRejected() {
        var result = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        result.put("isError", false);
        var content = result.putArray("content");
        var text = content.addObject();
        text.put("type", "text");
        text.put("text", HIT);
        result.set("structuredContent", new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode());
        this.endpoint.respondWithToolResultNode(result);

        assertThat(this.adapter.findAsset("AST-900001").failure()).isEqualTo(QueryFailure.INVALID_RESPONSE);
    }

    @Test
    void moreThanOneContentItemIsRejected() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var result = mapper.createObjectNode();
        result.put("isError", false);
        var content = result.putArray("content");
        content.addObject().put("type", "text").put("text", HIT);
        content.addObject().put("type", "text").put("text", HIT);
        this.endpoint.respondWithToolResultNode(result);

        assertThat(this.adapter.findAsset("AST-900001").failure()).isEqualTo(QueryFailure.INVALID_RESPONSE);
    }

    @Test
    void aNonTextContentItemIsRejected() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var result = mapper.createObjectNode();
        result.put("isError", false);
        var image = result.putArray("content").addObject();
        image.put("type", "image");
        image.put("data", "AAAA");
        image.put("mimeType", "image/png");
        this.endpoint.respondWithToolResultNode(result);

        assertThat(this.adapter.findAsset("AST-900001").failure()).isEqualTo(QueryFailure.INVALID_RESPONSE);
    }

    // ---------- 输入校验与请求次数 ----------

    @ParameterizedTest
    @ValueSource(strings = { "", " ", "AST-1", "AST-12345", "AST-1234567", "ast-900001", "AST-900001 ",
            " AST-900001", "AST_900001", "AST 900001", "900001", "AST-90000A" })
    void invalidInputIsRejectedBeforeAnyRequestIsSent(String assetId) {
        int before = this.endpoint.postCount();

        AssetQueryResult result = this.adapter.findAsset(assetId);

        assertThat(result.failure()).as("assetId=[%s]", assetId).isEqualTo(QueryFailure.INVALID_INPUT);
        assertThat(this.endpoint.postCount())
                .as("非法输入不得发出任何请求（assetId=[%s]）", assetId)
                .isEqualTo(before);
    }

    @Test
    void aNullAssetIdIsRejectedWithoutAnyRequest() {
        int before = this.endpoint.postCount();

        assertThat(this.adapter.findAsset(null).failure()).isEqualTo(QueryFailure.INVALID_INPUT);
        assertThat(this.endpoint.postCount()).isEqualTo(before);
    }

    // ---------- 超时 / 不可达 ----------

    @Test
    void aHangingRemoteEndsTheCallAtTheConfiguredBound() {
        this.endpoint.hang();
        long startedAt = System.nanoTime();

        AssetQueryResult result = this.adapter.findAsset("AST-900001");

        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
        assertThat(result.failure()).isEqualTo(QueryFailure.TIMEOUT);
        assertThat(elapsedMillis).as("必须在配置的上界附近结束（%d ms）", elapsedMillis).isLessThan(15_000L);
    }

    @Test
    void anUnreachableServiceIsUnavailableAndNotNotFound() {
        String baseUrl = this.endpoint.baseUrl();
        this.endpoint.close();

        McpAssetQueryAdapter offline = new McpAssetQueryAdapter(new McpToolClient(Duration.ofSeconds(2)),
                McpServerEndpoint.of(baseUrl));
        AssetQueryResult result = offline.findAsset("AST-900001");

        assertThat(result.failure()).isEqualTo(QueryFailure.UNAVAILABLE);
        assertThat(result.isNotFound()).isFalse();
    }

    // ---------- 会话与工具白名单 ----------

    @Test
    void everyCallUsesTheFixedToolAndReleasesItsSession() {
        this.endpoint.respondWithToolText(HIT);
        for (int index = 0; index < 3; index++) {
            assertThat(this.adapter.findAsset("AST-900001").isFound()).isTrue();
        }
        this.endpoint.respondWithToolError("{\"error\":\"ASSET_SOURCE_UNAVAILABLE\",\"message\":\"x\"}");
        assertThat(this.adapter.findAsset("AST-900001").isFailed()).isTrue();

        assertThat(this.endpoint.calledTools())
                .as("只允许调用 asset_get，且调用方无法指定别的工具名")
                .containsOnly("asset_get")
                .hasSize(4);
        assertThat(this.endpoint.initializeCount()).as("每次查询独立初始化").isEqualTo(4);
        assertThat(this.endpoint.deleteCount()).as("成功与失败路径都必须释放会话").isEqualTo(4);
        assertThat(new java.util.LinkedHashSet<>(this.endpoint.sessionsSeen()))
                .as("四次查询用的是四个不同的会话（每次查询独立建会话，没有复用）")
                .hasSize(4);
        assertThat(this.endpoint.liveSessions()).as("不留悬挂会话").isEmpty();
    }
}
