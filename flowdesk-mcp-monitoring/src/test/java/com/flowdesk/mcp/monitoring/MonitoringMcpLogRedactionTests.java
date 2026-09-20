package com.flowdesk.mcp.monitoring;

import static com.flowdesk.mcp.monitoring.MonitoringMcpTestClient.call;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flowdesk.mcp.monitoring.snapshot.MonitoringSnapshotSource;
import com.flowdesk.mcp.monitoring.snapshot.SnapshotOrigin;
import com.flowdesk.mcp.monitoring.tool.MonitoringSnapshotGetTool;
import io.modelcontextprotocol.client.McpSyncClient;
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
 * 日志脱敏测试（FD-0015）。
 *
 * <p>工具会记录每一次调用的结果码与耗时，这本身是有价值的运维信息；
 * 但日志里<b>不得</b>出现 assetId 原值、监控数值、异常消息、堆栈或演示记录正文。
 * 本类用「带哨兵文本的失败数据源」把这条边界钉死：既检查成功路径（命中与未找到），
 * 也检查失败路径（数据源内部异常）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "flowdesk.monitoring.source.mode=demo"
})
class MonitoringMcpLogRedactionTests {

    /** 哨兵：故意放进异常消息里，任何地方出现它都说明脱敏失败。 */
    private static final String SENTINEL = "sentinel-internal-path-C:/flowdesk/secret/monitoring-db.properties";

    @LocalServerPort
    private int port;

    @Autowired
    private SentrySnapshotSource source;

    private McpSyncClient client;

    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

    @BeforeEach
    void setUp() {
        this.source.failWith(null);
        this.client = MonitoringMcpTestClient.connect(this.port);
        this.logAppender.start();
        toolLogger().addAppender(this.logAppender);
    }

    @AfterEach
    void tearDown() {
        toolLogger().detachAppender(this.logAppender);
        this.logAppender.stop();
        if (this.client != null) {
            this.client.close();
        }
    }

    @Test
    void aSuccessfulLookupLogsNoAssetIdAndNoValues() {
        this.client.initialize();

        call(this.client, "monitoring_snapshot_get", Map.of("assetId", "AST-900001"));
        call(this.client, "monitoring_snapshot_get", Map.of("assetId", "AST-999999"));

        List<String> messages = messages();
        assertThat(messages).as("这次调用确实产生了日志（否则本测试毫无意义）").isNotEmpty();
        assertThat(messages)
                .as("日志只记操作、结果码与耗时：不含 assetId、监控数值、演示记录正文")
                .noneMatch(message -> message.contains("AST-900001")
                        || message.contains("AST-999999")
                        || message.contains("92")
                        || message.contains("68")
                        || message.contains("DEGRADED")
                        || message.contains(SENTINEL)
                        || message.contains("C:/"))
                .anyMatch(message -> message.contains("monitoring.snapshot.get"));
    }

    @Test
    void anInternalFailureLogsOnlyTheStableCodeAndTheExceptionClassName() {
        this.source.failWith(new IllegalStateException(SENTINEL));
        this.client.initialize();

        call(this.client, "monitoring_snapshot_get", Map.of("assetId", "AST-900001"));

        List<String> messages = messages();
        assertThat(messages).isNotEmpty();
        assertThat(messages)
                .as("日志里只允许出现稳定错误码与异常类名")
                .noneMatch(message -> message.contains(SENTINEL)
                        || message.contains("C:/")
                        || message.contains("AST-900001")
                        || message.contains("92")
                        || message.contains("monitoring-db"))
                .anyMatch(message -> message.contains("MONITORING_SOURCE_UNAVAILABLE"));
    }

    private static Logger toolLogger() {
        return (Logger) LoggerFactory.getLogger(MonitoringSnapshotGetTool.class);
    }

    private List<String> messages() {
        return this.logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** 可以被测试切换为「抛异常」的数据源替身。 */
    static final class SentrySnapshotSource implements MonitoringSnapshotSource {

        private RuntimeException failure;

        @Override
        public Optional<com.flowdesk.mcp.monitoring.snapshot.MonitoringSnapshot> findSnapshotById(String assetId) {
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
            return SnapshotOrigin.DEMO;
        }

        void failWith(RuntimeException failure) {
            this.failure = failure;
        }
    }

    /** 用替身覆盖演示数据源（{@code @Primary}）。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class SentrySourceConfiguration {

        @Bean
        @Primary
        SentrySnapshotSource sentrySnapshotSource() {
            return new SentrySnapshotSource();
        }
    }
}
