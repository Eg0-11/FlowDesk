package com.flowdesk.infrastructure.mcp.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowdesk.application.integration.AssetQueryResult;
import com.flowdesk.application.integration.QueryFailure;
import com.flowdesk.application.integration.QueryOutcome;
import com.flowdesk.application.integration.SourceOrigin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 真实 SDK + 真实 HTTP 冒烟测试（FD-0016）：先把「协议子集能跑通」这件事钉住，
 * 其余用例都建立在它之上。
 */
class ControllableEndpointSmokeTests {

    private ControllableMcpEndpoint endpoint;

    @BeforeEach
    void startEndpoint() throws Exception {
        this.endpoint = ControllableMcpEndpoint.start();
    }

    @AfterEach
    void stopEndpoint() {
        this.endpoint.close();
    }

    @Test
    void theRealSdkClientCompletesInitializeAndAFixedToolCall() {
        this.endpoint.respondWithToolText("{\"assetId\":\"AST-900001\",\"assetType\":\"SERVER\","
                + "\"status\":\"IN_SERVICE\",\"source\":\"DEMO\"}");

        McpAssetQueryAdapter adapter = new McpAssetQueryAdapter(new McpToolClient(java.time.Duration.ofSeconds(5)),
                McpServerEndpoint.of(this.endpoint.baseUrl()));

        AssetQueryResult result = adapter.findAsset("AST-900001");

        assertThat(result.outcome()).isEqualTo(QueryOutcome.FOUND);
        assertThat(result.requireAsset().assetType()).isEqualTo("SERVER");
        assertThat(result.requireAsset().source()).isEqualTo(SourceOrigin.DEMO);

        assertThat(this.endpoint.initializeCount()).as("每次查询独立初始化").isEqualTo(1);
        assertThat(this.endpoint.calledTools()).as("只调用固定工具").containsExactly("asset_get");
        assertThat(this.endpoint.deleteCount()).as("会话必须被释放").isEqualTo(1);
        assertThat(this.endpoint.liveSessions()).as("不留悬挂会话").isEmpty();
    }

    @Test
    void theEndpointRejectsACallWithoutTheSessionId() {
        this.endpoint.respondWithToolText("{\"assetId\":\"AST-900001\",\"assetType\":\"SERVER\","
                + "\"status\":\"IN_SERVICE\",\"source\":\"DEMO\"}");

        McpAssetQueryAdapter adapter = new McpAssetQueryAdapter(new McpToolClient(java.time.Duration.ofSeconds(5)),
                McpServerEndpoint.of(this.endpoint.baseUrl()));

        AssetQueryResult result = adapter.findAsset("AST-900001");

        // 端点会强制要求会话；若客户端不带会话，这里就会是失败分类而不是 FOUND
        assertThat(result.isFound()).as("客户端必须真的建立并使用会话").isTrue();
        assertThat(QueryFailure.values()).hasSize(6);
    }
}
