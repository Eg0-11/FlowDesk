package com.flowdesk.infrastructure.mcp.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 超时配置校验的单元测试（FD-0016）：必须是正数，且有明确上限。
 */
class McpClientPropertiesTests {

    @Test
    void theShippedDefaultsAreDisabledAndBoundToTheTwoFixedServices() {
        McpClientProperties properties = new McpClientProperties();

        assertThat(properties.isEnabled()).as("默认必须关闭").isFalse();
        assertThat(properties.getAsset().getBaseUrl()).isEqualTo("http://127.0.0.1:8091");
        assertThat(properties.getMonitoring().getBaseUrl()).isEqualTo("http://127.0.0.1:8092");
        assertThat(properties.getRequestTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThatCode(properties::validateTimeout).doesNotThrowAnyException();
    }

    @Test
    void positiveTimeoutsUpToThirtySecondsAreAccepted() {
        for (Duration timeout : new Duration[] { Duration.ofMillis(1), Duration.ofSeconds(5),
                Duration.ofSeconds(30) }) {
            McpClientProperties properties = new McpClientProperties();
            properties.setRequestTimeout(timeout);
            assertThatCode(properties::validateTimeout).as("timeout=%s", timeout).doesNotThrowAnyException();
        }
    }

    @Test
    void nullZeroNegativeAndOverThirtySecondsAreRejected() {
        Duration[] rejected = { null, Duration.ZERO, Duration.ofSeconds(-1), Duration.ofMillis(-5),
                Duration.ofSeconds(31), Duration.ofMinutes(10) };
        for (Duration timeout : rejected) {
            McpClientProperties properties = new McpClientProperties();
            properties.setRequestTimeout(timeout);

            assertThatThrownBy(properties::validateTimeout)
                    .as("timeout=%s", timeout)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("request-timeout")
                    .hasMessageNotContaining("31")
                    .hasMessageNotContaining("600");
        }
    }

    @Test
    void theSdkLogLevelDefaultsToOff() {
        McpClientProperties properties = new McpClientProperties();

        assertThat(properties.getSdkLogLevel()).as("SDK 客户端日志默认不输出").isEqualTo("OFF");
        assertThatCode(properties::validateSdkLogLevel).doesNotThrowAnyException();
    }

    @Test
    void everyDocumentedSdkLogLevelIsAccepted() {
        for (String level : new String[] { "OFF", "ERROR", "WARN", "INFO", "DEBUG", "TRACE", "off", "Debug" }) {
            McpClientProperties properties = new McpClientProperties();
            properties.setSdkLogLevel(level);

            assertThatCode(properties::validateSdkLogLevel).as("level=%s", level).doesNotThrowAnyException();
            assertThat(McpSdkLogControl.normalizeLevel(level)).as("level=%s", level).isNotBlank();
        }
    }

    @Test
    void unknownSdkLogLevelsAreRejectedWithoutEchoingTheValue() {
        for (String level : new String[] { "", " ", "ALL", "VERBOSE", "TRACEY", "OFFF" }) {
            McpClientProperties properties = new McpClientProperties();
            properties.setSdkLogLevel(level);

            assertThatThrownBy(properties::validateSdkLogLevel)
                    .as("level=[%s]", level)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("sdk-log-level")
                    .hasMessageNotContaining("VERBOSE")
                    .hasMessageNotContaining("OFFF");
        }
        McpClientProperties nullLevel = new McpClientProperties();
        nullLevel.setSdkLogLevel(null);
        assertThatThrownBy(nullLevel::validateSdkLogLevel).isInstanceOf(IllegalStateException.class);
    }
}
