package com.flowdesk.mcp.asset;

import static com.flowdesk.mcp.asset.AssetMcpTestClient.call;
import static com.flowdesk.mcp.asset.AssetMcpTestClient.payload;
import static com.flowdesk.mcp.asset.AssetMcpTestClient.text;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flowdesk.mcp.asset.directory.AssetDirectory;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 内部异常脱敏测试（FD-0014）。
 *
 * <p>目录实现抛出<b>任何</b>运行期异常时，工具必须返回稳定的
 * {@code ASSET_SOURCE_UNAVAILABLE}，并且<b>不得</b>把异常消息、堆栈、类名、路径或配置
 * 带进响应；日志里也只允许出现异常类名，不允许出现异常消息或 assetId。</p>
 *
 * <p>这里用一个会抛出「带哨兵文本的异常」的目录替身，因此可以精确断言「哨兵没有出现在任何地方」。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "flowdesk.asset.directory.mode=demo",
        "spring.main.allow-bean-definition-overriding=true"
})
class AssetMcpInternalFailureTests {

    /** 哨兵：故意放进异常消息里，任何地方出现它都说明脱敏失败。 */
    private static final String SENTINEL = "sentinel-internal-path-C:/flowdesk/secret/asset-db.properties";

    @LocalServerPort
    private int port;

    @Autowired
    private LeakyAssetDirectory directory;

    private McpSyncClient client;

    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

    @BeforeEach
    void setUp() {
        this.directory.failWith(new IllegalStateException(SENTINEL));
        this.client = AssetMcpTestClient.connect(this.port);
        this.logAppender.start();
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(this.logAppender);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(this.logAppender);
        this.logAppender.stop();
        if (this.client != null) {
            this.client.close();
        }
    }

    @Test
    void anUnexpectedInternalFailureBecomesSourceUnavailableWithoutLeakingAnything() throws Exception {
        this.client.initialize();

        McpSchema.CallToolResult result = call(this.client, "asset_get", Map.of("assetId", "AST-900001"));

        assertThat(result.isError()).isTrue();
        assertThat(payload(result).path("error").asText()).isEqualTo("ASSET_SOURCE_UNAVAILABLE");

        String body = text(result);
        assertThat(body)
                .as("响应里只能有固定文案")
                .doesNotContain(SENTINEL)
                .doesNotContain("IllegalStateException")
                .doesNotContain("C:/")
                .doesNotContain("java.")
                .doesNotContain("at com.flowdesk")
                .doesNotContain("AST-900001");

        List<String> messages = this.logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(java.util.Objects::nonNull)
                .toList();
        assertThat(messages).as("这次调用确实产生了日志（否则本测试毫无意义）").isNotEmpty();
        assertThat(messages)
                .as("日志只记稳定错误码与异常类名：不含异常消息、堆栈与 assetId")
                .noneMatch(message -> message.contains(SENTINEL)
                        || message.contains("C:/")
                        || message.contains("AST-900001"))
                .anyMatch(message -> message.contains("ASSET_SOURCE_UNAVAILABLE"));
    }

    @Test
    void theFailureIsNotReportedAsNotFound() throws Exception {
        this.client.initialize();

        McpSchema.CallToolResult result = call(this.client, "asset_get", Map.of("assetId", "AST-999999"));

        assertThat(text(result))
                .as("数据源坏了不等于资产不存在")
                .doesNotContain("ASSET_NOT_FOUND")
                .contains("ASSET_SOURCE_UNAVAILABLE");
    }

    /**
     * 目录替身：抛出带哨兵文本的异常。
     */
    static final class LeakyAssetDirectory implements AssetDirectory {

        private RuntimeException failure;

        @Override
        public java.util.Optional<com.flowdesk.mcp.asset.directory.AssetRecord> findById(String assetId) {
            throw this.failure;
        }

        @Override
        public com.flowdesk.mcp.asset.directory.AssetSource source() {
            throw this.failure;
        }

        void failWith(RuntimeException failure) {
            this.failure = failure;
        }
    }

    /**
     * 用替身覆盖演示目录（{@code @Primary}）。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class LeakyDirectoryConfiguration {

        @Bean
        @Primary
        LeakyAssetDirectory leakyAssetDirectory() {
            return new LeakyAssetDirectory();
        }
    }
}
