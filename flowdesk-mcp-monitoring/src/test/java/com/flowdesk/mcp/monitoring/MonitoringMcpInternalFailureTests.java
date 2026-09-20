package com.flowdesk.mcp.monitoring;

import static com.flowdesk.mcp.monitoring.MonitoringMcpTestClient.call;
import static com.flowdesk.mcp.monitoring.MonitoringMcpTestClient.payload;
import static com.flowdesk.mcp.monitoring.MonitoringMcpTestClient.text;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flowdesk.mcp.monitoring.snapshot.MonitoringSnapshot;
import com.flowdesk.mcp.monitoring.snapshot.MonitoringSnapshotSource;
import com.flowdesk.mcp.monitoring.snapshot.SnapshotOrigin;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * 内部异常与非法数据源响应的脱敏测试（FD-0015 / FD-0015-R1）。
 *
 * <p>数据源抛出<b>任何</b>运行期异常，或者给出<b>非法响应</b>（未命中却让 {@code origin()}
 * 返回 {@code null}）时，工具必须返回稳定的 {@code MONITORING_SOURCE_UNAVAILABLE}，
 * 并且<b>不得</b>把异常消息、堆栈、类名、路径、配置或 {@code null} 带进响应；
 * 日志里也只允许出现异常类名，不允许出现异常消息或 assetId。</p>
 *
 * <p>这里用一个「会抛出带哨兵文本的异常」的数据源替身，因此可以精确断言「哨兵没有出现在任何地方」。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "flowdesk.monitoring.source.mode=demo",
        "spring.main.allow-bean-definition-overriding=true"
})
class MonitoringMcpInternalFailureTests {

    /** 哨兵：故意放进异常消息里，任何地方出现它都说明脱敏失败。 */
    private static final String SENTINEL = "sentinel-internal-path-C:/flowdesk/secret/monitoring-source.properties";

    @LocalServerPort
    private int port;

    @Autowired
    private LeakySnapshotSource source;

    private McpSyncClient client;

    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

    @BeforeEach
    void setUp() {
        this.source.failWith(new IllegalStateException(SENTINEL));
        this.client = MonitoringMcpTestClient.connect(this.port);
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

        McpSchema.CallToolResult result = call(this.client, "monitoring_snapshot_get", Map.of("assetId", "AST-900001"));

        assertThat(result.isError()).isTrue();
        assertThat(payload(result).path("error").asText()).isEqualTo("MONITORING_SOURCE_UNAVAILABLE");

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
                .anyMatch(message -> message.contains("MONITORING_SOURCE_UNAVAILABLE"));
    }

    @Test
    void theFailureIsNotReportedAsNotFound() throws Exception {
        this.client.initialize();

        McpSchema.CallToolResult result = call(this.client, "monitoring_snapshot_get", Map.of("assetId", "AST-999999"));

        assertThat(text(result))
                .as("数据源坏了不等于资产不存在")
                .doesNotContain("MONITORING_SNAPSHOT_NOT_FOUND")
                .contains("MONITORING_SOURCE_UNAVAILABLE");
    }

    /**
     * 非法数据源响应：未命中却给不出来源（{@code origin()} 返回 {@code null}）。
     *
     * <p>协议层必须看到 {@code isError=true} + {@code MONITORING_SOURCE_UNAVAILABLE}，
     * 而<b>不是</b> {@code isError=false} 的「未找到 + source:null」：
     * 后者会让调用方以为「这个资产确实没有快照」，而真相是我们给不出来源。</p>
     *
     * @throws Exception 载荷解析失败
     */
    @Test
    void aNullOriginNeverReachesTheClientAsNotFoundWithANullSource() throws Exception {
        this.source.giveNoOrigin();
        this.client.initialize();

        McpSchema.CallToolResult result = call(this.client, "monitoring_snapshot_get", Map.of("assetId", "AST-900003"));

        assertThat(result.isError()).as("非法数据源响应是调用失败，不是「没有快照」").isTrue();

        String body = text(result);
        assertThat(payload(result).path("error").asText()).isEqualTo("MONITORING_SOURCE_UNAVAILABLE");
        assertThat(body)
                .as("错误内容固定：不新增错误码、不出现 null、「未找到」或输入")
                .isEqualTo("{\"error\":\"MONITORING_SOURCE_UNAVAILABLE\",\"message\":\"监控数据源当前不可用\"}");
        assertThat(body)
                .doesNotContain("MONITORING_SNAPSHOT_NOT_FOUND")
                .doesNotContain("null")
                .doesNotContain("\"source\"")
                .doesNotContain("AST-900003");
    }

    /**
     * 数据源替身：可切换为「抛出带哨兵文本的异常」或「未命中且给不出来源（非法响应）」。
     */
    static final class LeakySnapshotSource implements MonitoringSnapshotSource {

        private RuntimeException failure;

        private boolean nullOrigin;

        @Override
        public Optional<MonitoringSnapshot> findSnapshotById(String assetId) {
            if (this.failure != null) {
                throw this.failure;
            }
            return Optional.empty();
        }

        @Override
        public SnapshotOrigin origin() {
            if (this.failure != null) {
                throw this.failure;
            }
            return this.nullOrigin ? null : SnapshotOrigin.DEMO;
        }

        void failWith(RuntimeException failure) {
            this.failure = failure;
            this.nullOrigin = false;
        }

        void giveNoOrigin() {
            this.failure = null;
            this.nullOrigin = true;
        }
    }

    /**
     * 用替身覆盖演示数据源（{@code @Primary}）。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class LeakySnapshotSourceConfiguration {

        @Bean
        @Primary
        LeakySnapshotSource leakySnapshotSource() {
            return new LeakySnapshotSource();
        }
    }
}
