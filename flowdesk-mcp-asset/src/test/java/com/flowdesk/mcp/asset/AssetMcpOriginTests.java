package com.flowdesk.mcp.asset;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * MCP 端点的安全边界测试（FD-0014）。
 *
 * <p>两条要求：</p>
 * <ol>
 *   <li><b>带 Origin 的请求一律 403</b>：本服务面向本机 MCP 客户端，不面向浏览器，
 *       也不提供宽松 CORS；</li>
 *   <li><b>不带 Origin 的正常 MCP SDK 客户端可用</b>：拒绝跨源不能把真实客户端一起挡掉；</li>
 *   <li>健康检查<b>不受影响</b>（过滤器只作用于 {@code /mcp}）。</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "flowdesk.asset.directory.mode=demo"
})
class AssetMcpOriginTests {

    private static final String INITIALIZE_BODY = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",\
            "capabilities":{},"clientInfo":{"name":"origin-test","version":"1.0.0"}}}""";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void aRequestWithAnOriginHeaderIsRejectedWith403() {
        for (String origin : new String[] { "https://evil.example.com", "http://localhost:3000",
                "null", "https://127.0.0.1:5173" }) {

            ResponseEntity<String> response = postWithOrigin(origin);

            assertThat(response.getStatusCode()).as("origin=%s", origin).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getHeaders().getContentType())
                    .as("origin=%s", origin)
                    .isNotNull();
            assertThat(response.getHeaders().getContentType().toString())
                    .contains(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            assertThat(response.getBody())
                    .as("响应体固定，不回显 Origin 值")
                    .contains("ORIGIN_NOT_ALLOWED")
                    .contains("urn:flowdesk:problem:origin-not-allowed")
                    .doesNotContain(origin);
            assertThat(response.getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                    .as("不得用宽松 CORS 放行")
                    .isNull();
        }
    }

    @Test
    void theSameRequestWithoutOriginIsNotBlockedByTheFilter() {
        ResponseEntity<String> response = postWithoutOrigin();

        assertThat(response.getStatusCode())
                .as("过滤器只拦带 Origin 的请求；无 Origin 的请求由 MCP 端点自己处理")
                .isNotEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aRealMcpClientWithoutOriginStillWorks() {
        try (McpSyncClient client = AssetMcpTestClient.connect(this.port)) {
            McpSchema.InitializeResult result = client.initialize();

            assertThat(result.serverInfo().name()).isEqualTo("flowdesk-mcp-asset");
            assertThat(client.listTools().tools()).hasSize(1);
            assertThat(AssetMcpTestClient.call(client, "asset_get", Map.of("assetId", "AST-900001"))
                    .isError()).isFalse();
        }
    }

    @Test
    void aRealMcpClientWithOriginIsRejected() {
        try (McpSyncClient client = AssetMcpTestClient.connect(this.port, "https://evil.example.com")) {
            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(client::initialize);

            assertThat(thrown)
                    .as("带 Origin 的 MCP 客户端必须被 403 挡住（初始化不可能成功）")
                    .isNotNull();
            assertThat(client.isInitialized()).isFalse();
        }
    }

    @Test
    void theHealthEndpointStillWorks() {
        ResponseEntity<String> response = this.restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("UP");
    }

    private ResponseEntity<String> postWithOrigin(String origin) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.ACCEPT, "application/json, text/event-stream");
        headers.add(HttpHeaders.ORIGIN, origin);
        return this.restTemplate.exchange(uri(), HttpMethod.POST, new HttpEntity<>(INITIALIZE_BODY, headers),
                String.class);
    }

    private ResponseEntity<String> postWithoutOrigin() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.ACCEPT, "application/json, text/event-stream");
        return this.restTemplate.exchange(uri(), HttpMethod.POST, new HttpEntity<>(INITIALIZE_BODY, headers),
                String.class);
    }

    private String uri() {
        return "http://127.0.0.1:" + this.port + AssetMcpTestClient.MCP_ENDPOINT;
    }

    /**
     * 用 JDK HttpClient 再确认一次 403（不经过 Spring 的测试客户端，排除测试侧伪造的可能）。
     *
     * @throws Exception 请求失败
     */
    @Test
    void theRejectionIsObservableWithAPlainHttpClientToo() throws Exception {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(uri()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Origin", "https://evil.example.com")
                .POST(HttpRequest.BodyPublishers.ofString(INITIALIZE_BODY, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("ORIGIN_NOT_ALLOWED");
    }
}
