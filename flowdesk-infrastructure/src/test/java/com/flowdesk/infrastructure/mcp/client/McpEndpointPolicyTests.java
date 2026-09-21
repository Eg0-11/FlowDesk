package com.flowdesk.infrastructure.mcp.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 端点校验的单元测试（FD-0016）。
 *
 * <p>这一层是「主服务能连到哪里」的唯一决策点，因此用例覆盖的不是「能连上」而是
 * 「<b>连不上</b>」：主机名、非回环、userinfo、query、fragment、自定义路径、缺端口、
 * 越界端口与各种含糊写法都必须被拒绝。</p>
 */
class McpEndpointPolicyTests {

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1:8091",
            "http://127.0.0.2:8091",
            "http://127.255.255.255:65535",
            "http://127.0.0.1:1",
            "http://127.0.0.1:8091/",
            "http://[::1]:8091",
            "http://[0:0:0:0:0:0:0:1]:8091"
    })
    void literalLoopbackHttpBaseUrlsAreAccepted(String baseUrl) {
        assertThat(McpEndpointPolicy.requireLoopbackHttpBaseUrl(baseUrl)).isNotBlank();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://127.0.0.1:8091",
            "ftp://127.0.0.1:8091",
            "http://localhost:8091",
            "http://LOCALHOST:8091",
            "http://asset-service:8091",
            "http://127.0.0.1.evil.example:8091",
            "http://127.1:8091",
            "http://127.0.0.1:8091/mcp",
            "http://127.0.0.1:8091/path/",
            "http://user@127.0.0.1:8091",
            "http://user:secret@127.0.0.1:8091",
            "http://127.0.0.1:8091?x=1",
            "http://127.0.0.1:8091#frag",
            "http://127.0.0.1",
            "http://127.0.0.1:0",
            "http://127.0.0.1:65536",
            "http://127.0.0.1:70000",
            "http://0.0.0.0:8091",
            "http://192.168.1.10:8091",
            "http://10.0.0.5:8091",
            "http://203.0.113.7:8091",
            "http://2130706433:8091",
            "http://0127.0.0.1:8091",
            "http://127.0.0.1:8091 ",
            " http://127.0.0.1:8091",
            "127.0.0.1:8091",
            "http://127.0.0.1:8091/mcp/",
            "http://::1:8091"
    })
    void everythingElseIsRejected(String baseUrl) {
        assertThatThrownBy(() -> McpEndpointPolicy.requireLoopbackHttpBaseUrl(baseUrl))
                .as("baseUrl=[%s]", baseUrl)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base-url");
    }

    @ParameterizedTest
    @ValueSource(strings = { "", " ", "   " })
    void blankValuesAreRejected(String baseUrl) {
        assertThatThrownBy(() -> McpEndpointPolicy.requireLoopbackHttpBaseUrl(baseUrl))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aNullValueIsRejected() {
        assertThatThrownBy(() -> McpEndpointPolicy.requireLoopbackHttpBaseUrl(null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theErrorMessageEchoesNeitherTheEndpointNorItsHost() {
        assertThatThrownBy(() -> McpEndpointPolicy.requireLoopbackHttpBaseUrl(
                "http://operator:s3cret@asset-db.internal:8091?token=abc#frag"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("asset-db.internal")
                .hasMessageNotContaining("operator")
                .hasMessageNotContaining("s3cret")
                .hasMessageNotContaining("abc");
    }

    @Test
    void anAcceptedEndpointIsNormalizedAndThePathIsFixed() {
        McpServerEndpoint endpoint = McpServerEndpoint.of("HTTP://127.0.0.1:8091");

        assertThat(endpoint.baseUri()).as("规范化后只保留 http + 字面量回环 + 端口").isEqualTo("http://127.0.0.1:8091");
        assertThat(endpoint.path()).as("MCP 路径本阶段固定").isEqualTo("/mcp");
    }
}
