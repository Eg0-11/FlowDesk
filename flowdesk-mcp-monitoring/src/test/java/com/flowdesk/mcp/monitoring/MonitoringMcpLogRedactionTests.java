package com.flowdesk.mcp.monitoring;

import static com.flowdesk.mcp.monitoring.MonitoringMcpTestClient.call;
import static com.flowdesk.mcp.monitoring.MonitoringMcpTestClient.text;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flowdesk.mcp.monitoring.snapshot.DemoSnapshotSource;
import com.flowdesk.mcp.monitoring.snapshot.MonitoringSnapshot;
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
 * 日志脱敏测试（FD-0015 / FD-0015-R1）。
 *
 * <p>工具会记录每一次调用的结果码与耗时，这本身是有价值的运维信息；
 * 但日志里<b>不得</b>出现 assetId 原值、监控数值、异常消息、堆栈或演示记录正文。</p>
 *
 * <p>本类用「带哨兵文本的失败数据源」把这条边界钉死：<b>没有注入异常时它委托演示数据源</b>，
 * 因此 {@code AST-900001} 真的走命中路径（92/68/DEGRADED 确实出现在响应里）、
 * {@code AST-999999} 真的走未找到路径 —— 先证明这些值存在，再证明它们没有进日志。</p>
 *
 * <p><b>怎么断言「没有监控数值」而不与耗时撞车</b>：日志行是固定模板
 * （{@code … result={} durationMs={}}），末尾本来就有一个合法数值。因此本类不再对
 * 「格式化后的整行」搜裸字符串 {@code "92"}/{@code "68"}（耗时完全可能就是 92 毫秒），
 * 而是<b>结构化断言</b>：模板必须是两种固定文案之一，且<b>每一个参数位</b>都被钉死 ——
 * 固定常量、稳定结果码、异常类名，以及一个只断言「是数字」的耗时。这样任何业务数据
 * 都无处可放，同时测试不依赖耗时的具体取值。文本扫描只保留不可能与数字撞车的哨兵
 * （assetId、health 取值、异常消息里的路径）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "flowdesk.monitoring.source.mode=demo"
})
class MonitoringMcpLogRedactionTests {

    /** 哨兵：故意放进异常消息里，任何地方出现它都说明脱敏失败。 */
    private static final String SENTINEL = "sentinel-internal-path-C:/flowdesk/secret/monitoring-db.properties";

    /** 工具日志的操作名（也是参数位 0 与 1 的取值）。 */
    private static final String OPERATION = "monitoring.snapshot.get";

    /** 工具日志允许出现的两种模板，逐字取自 {@code MonitoringSnapshotGetTool} 的固定格式。 */
    private static final String COMPLETED_TEMPLATE = "{} completed operation={} result={} durationMs={}";

    private static final String FAILED_TEMPLATE = "{} failed operation={} result={} exception={} durationMs={}";

    /** 允许出现在结果位的稳定码（穷举：新增必须显式加进来）。 */
    private static final List<String> STABLE_RESULTS =
            List.of("OK", "MONITORING_SNAPSHOT_NOT_FOUND", "INVALID_ASSET_ID", "MONITORING_SOURCE_UNAVAILABLE");

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

        String hit = text(call(this.client, "monitoring_snapshot_get", Map.of("assetId", "AST-900001")));
        String miss = text(call(this.client, "monitoring_snapshot_get", Map.of("assetId", "AST-999999")));

        // 先证明这两次调用真的走了「命中」与「未找到」两条路径：否则「日志里没有监控数值」
        // 只是一句空话（那些值根本没出现过）
        assertThat(hit)
                .as("AST-900001 必须真的命中：92/68/DEGRADED 就在响应里，只是不许进日志")
                .contains("\"cpuUtilizationPercent\":92")
                .contains("\"memoryUtilizationPercent\":68")
                .contains("\"health\":\"DEGRADED\"")
                .contains("\"source\":\"DEMO\"");
        assertThat(miss)
                .as("AST-999999 必须真的未命中（同一条数据源的另一条路径）")
                .contains("MONITORING_SNAPSHOT_NOT_FOUND")
                .contains("\"found\":false");

        assertLogParametersCarryNoBusinessData();

        assertThat(messages())
                .as("日志文本里不得出现 assetId 原值、health 取值或演示记录正文"
                        + "（只扫不可能与耗时数字撞车的哨兵）")
                .noneMatch(message -> message.contains("AST-900001")
                        || message.contains("AST-999999")
                        || message.contains("DEGRADED")
                        || message.contains("HEALTHY")
                        || message.contains(SENTINEL)
                        || message.contains("C:/")
                        || message.contains("monitoring-db"))
                .anyMatch(message -> message.contains("monitoring.snapshot.get"));
    }

    @Test
    void anInternalFailureLogsOnlyTheStableCodeAndTheExceptionClassName() {
        this.source.failWith(new IllegalStateException(SENTINEL));
        this.client.initialize();

        String body = text(call(this.client, "monitoring_snapshot_get", Map.of("assetId", "AST-900001")));

        assertThat(body)
                .as("先证明失败路径真的发生了")
                .isEqualTo("{\"error\":\"MONITORING_SOURCE_UNAVAILABLE\",\"message\":\"监控数据源当前不可用\"}");

        assertLogParametersCarryNoBusinessData();

        assertThat(messages())
                .as("日志里只允许出现稳定错误码与异常类名，不允许出现异常消息、路径与 assetId")
                .noneMatch(message -> message.contains(SENTINEL)
                        || message.contains("C:/")
                        || message.contains("AST-900001")
                        || message.contains("monitoring-db"))
                .anyMatch(message -> message.contains("MONITORING_SOURCE_UNAVAILABLE"));
    }

    /**
     * 结构化断言：把每条日志事件的<b>模板</b>与<b>每一个参数位</b>都钉死。
     *
     * <p>这证明的正是「日志参数里没有 assetId、health、CPU/内存占用与告警数」——
     * 参数位只可能是固定常量、稳定结果码、异常类名，以及唯一的数值参数（耗时）。
     * 任何业务数据都没有位置可放。</p>
     *
     * <p>耗时的<b>具体取值从不参与断言</b>：它完全可以恰好是 92 或 68，
     * 那就是一次 92 毫秒的调用，与监控数值无关 —— 这正是原来裸字符串扫描的假失败来源。</p>
     */
    private void assertLogParametersCarryNoBusinessData() {
        assertThat(this.logAppender.list)
                .as("这次调用确实产生了日志（否则本测试毫无意义）")
                .isNotEmpty();

        for (ILoggingEvent event : this.logAppender.list) {
            String template = event.getMessage();
            Object[] arguments = event.getArgumentArray();

            assertThat(template)
                    .as("日志模板必须是两种固定文案之一：模板本身不得携带动态数据")
                    .isIn(COMPLETED_TEMPLATE, FAILED_TEMPLATE);
            assertThat(arguments).as("模板带参数，参数数组必须存在").isNotNull();
            assertThat(arguments[0]).as("参数位 0 是操作名").isEqualTo(OPERATION);
            assertThat(arguments[1]).as("参数位 1 是操作名").isEqualTo(OPERATION);
            assertThat(arguments[2])
                    .as("参数位 2 只允许稳定结果码，不可能是 assetId 或其它业务数据")
                    .isInstanceOf(String.class)
                    .isIn(STABLE_RESULTS);

            if (COMPLETED_TEMPLATE.equals(template)) {
                assertThat(arguments).as("完成路径的参数形状固定").hasSize(4);
                assertThat(arguments[3])
                        .as("唯一的数值参数是耗时：只断言它是数字，不断言取值")
                        .isInstanceOf(Number.class);
            }
            else {
                assertThat(arguments).as("失败路径的参数形状固定").hasSize(5);
                String exceptionField = String.valueOf(arguments[3]);
                assertThat(exceptionField.equals("none") || exceptionField.endsWith("Exception"))
                        .as("参数位 3 只允许异常类名（或 none），不可能是业务数据：%s", exceptionField)
                        .isTrue();
                assertThat(arguments[4])
                        .as("唯一的数值参数是耗时：只断言它是数字，不断言取值")
                        .isInstanceOf(Number.class);
            }
        }
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

    /**
     * 可切换的数据源替身：注入异常时抛异常，<b>没有异常时委托演示数据源</b>。
     *
     * <p>委托很重要：如果它无条件返回 {@code Optional.empty()}，{@code AST-900001} 就永远
     * 走不到命中路径，「日志里没有出现 92/68/DEGRADED」也就成了一句没有验证对象的断言。</p>
     */
    static final class SentrySnapshotSource implements MonitoringSnapshotSource {

        private final DemoSnapshotSource delegate = new DemoSnapshotSource();

        private RuntimeException failure;

        @Override
        public Optional<MonitoringSnapshot> findSnapshotById(String assetId) {
            if (this.failure != null) {
                throw this.failure;
            }
            return this.delegate.findSnapshotById(assetId);
        }

        @Override
        public SnapshotOrigin origin() {
            if (this.failure != null) {
                throw this.failure;
            }
            return this.delegate.origin();
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
