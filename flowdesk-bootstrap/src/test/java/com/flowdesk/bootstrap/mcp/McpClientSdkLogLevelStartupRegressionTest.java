package com.flowdesk.bootstrap.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.flowdesk.infrastructure.mcp.client.McpClientProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 交付 {@code application.yml} 的 MCP 客户端启动回归测试（FD-0020-E-R1）。
 *
 * <p><b>背景</b>：不带引号的 {@code sdk-log-level: OFF} 会被 YAML 解析成布尔 {@code false}
 * （SnakeYAML 2.4 实测），绑定到 {@link McpClientProperties#getSdkLogLevel() String 字段}
 * 得到字符串 {@code "false"}，不在允许集合内，导致「MCP 客户端启用」时主服务装配失败
 * （FD-0020-E 实测；当时的冒烟只能靠命令行显式覆盖绕开）。</p>
 *
 * <p><b>本测试加载的是真实主服务上下文</b>（{@code FlowDeskApplication} + 随包交付的
 * {@code application.yml}）：测试属性只覆盖「是否启用」与「端点指向」，
 * {@code sdk-log-level} 刻意<b>不</b>用测试属性覆盖 —— 它必须由交付配置本身绑定出来，
 * 因此交付文件里一旦再出现「裸标量被解析成布尔」这类问题，这里会在装配期直接失败。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:flowdesk_mcp_sdk_log_it"
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "flowdesk.knowledge.storage.root=target/knowledge-mcp-sdk-log-it"
})
class McpClientSdkLogLevelStartupRegressionTest {

    /** 与 {@code McpSdkLogControl.SDK_LOGGER_NAME} 同值（跨模块取不到包内常量，这里按契约字面量写死）。 */
    private static final String SDK_LOGGER_NAME = "io.modelcontextprotocol";

    private static final CountingEndpoint ENDPOINT = startEndpoint();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("flowdesk.mcp.client.enabled", () -> "true");
        registry.add("flowdesk.mcp.client.asset.base-url", ENDPOINT::baseUrl);
        registry.add("flowdesk.mcp.client.monitoring.base-url", ENDPOINT::baseUrl);
        // 刻意不覆盖 flowdesk.mcp.client.sdk-log-level：它必须由交付 application.yml 绑定。
    }

    @AfterAll
    static void stopEndpoint() {
        ENDPOINT.close();
    }

    @Autowired
    private McpClientProperties properties;

    @Test
    void theShippedStartupContractBindsOffAsStringAndKeepsStartupQuiet() {
        assertThat(this.properties.getSdkLogLevel())
                .as("交付配置里的 OFF 必须以字符串形式绑定（裸标量 OFF 会被 YAML 解析成布尔 false）")
                .isEqualTo("OFF");

        Logger sdkLogger = (Logger) LoggerFactory.getLogger(SDK_LOGGER_NAME);
        assertThat(sdkLogger.getLevel())
                .as("启用装配时按包名把 SDK 日志设成 OFF（不输出远端原文与堆栈）")
                .isEqualTo(Level.OFF);

        assertThat(ENDPOINT.postCount())
                .as("启动期只校验配置：不建客户端、不初始化、不向 MCP 服务发请求")
                .isZero();
    }

    private static CountingEndpoint startEndpoint() {
        try {
            return CountingEndpoint.start();
        }
        catch (IOException ex) {
            throw new IllegalStateException("测试端点启动失败", ex);
        }
    }

    /**
     * 只计数的本机 HTTP 端点（随机端口、只绑回环）：用来把「启动期没有向 MCP 服务发过请求」
     * 这件事<b>数出来</b> —— 上下文启动完成后计数必须仍然是 0。
     */
    private static final class CountingEndpoint implements AutoCloseable {

        private final HttpServer server;

        private final AtomicInteger postCount = new AtomicInteger();

        private CountingEndpoint() throws IOException {
            this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            this.server.createContext("/", exchange -> {
                this.postCount.incrementAndGet();
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
            });
            this.server.start();
        }

        static CountingEndpoint start() throws IOException {
            return new CountingEndpoint();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + this.server.getAddress().getPort();
        }

        int postCount() {
            return this.postCount.get();
        }

        @Override
        public void close() {
            this.server.stop(0);
        }
    }
}
