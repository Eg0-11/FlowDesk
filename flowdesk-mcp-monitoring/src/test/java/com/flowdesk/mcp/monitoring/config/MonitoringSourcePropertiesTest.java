package com.flowdesk.mcp.monitoring.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 数据源模式的严格解析测试（FD-0015）。
 *
 * <p>纯 Java：绑定层（Spring Boot 的宽松枚举绑定）会把 {@code DEMO}/{@code Demo}/{@code demo}
 * 视作同一个值，而本阶段要求「未知、大小写错误或带空白都必须启动失败」。
 * 因此模式以原始字符串接收，再用 {@link MonitoringSourceProperties#resolvedMode()} 做逐字符判定 ——
 * 这些用例把那条判定钉死。</p>
 */
class MonitoringSourcePropertiesTest {

    @Test
    void theDefaultModeIsUnavailable() {
        assertThat(new MonitoringSourceProperties().getMode()).isEqualTo("unavailable");
        assertThat(new MonitoringSourceProperties().resolvedMode())
                .isEqualTo(MonitoringSourceProperties.Mode.UNAVAILABLE);
    }

    @Test
    void theTwoLiteralValuesAreAccepted() {
        MonitoringSourceProperties unavailable = new MonitoringSourceProperties();
        unavailable.setMode("unavailable");
        assertThat(unavailable.resolvedMode()).isEqualTo(MonitoringSourceProperties.Mode.UNAVAILABLE);

        MonitoringSourceProperties demo = new MonitoringSourceProperties();
        demo.setMode("demo");
        assertThat(demo.resolvedMode()).isEqualTo(MonitoringSourceProperties.Mode.DEMO);
    }

    @ParameterizedTest
    @ValueSource(strings = { "DEMO", "Demo", "UNAVAILABLE", "Unavailable", "demo ", " demo", " demo ",
            "unavailable ", "real", "none", "", " ", "demo\n" })
    void everyOtherValueIsRejected(String mode) {
        MonitoringSourceProperties properties = new MonitoringSourceProperties();
        properties.setMode(mode);

        assertThatThrownBy(properties::resolvedMode)
                .as("mode=[%s]", mode)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flowdesk.monitoring.source.mode");
    }

    @Test
    void theErrorMessageDoesNotEchoTheConfiguredValue() {
        for (String value : new String[] { "SECRET-INTERNAL-MODE", "Demo", " demo ", "DEMO" }) {
            MonitoringSourceProperties properties = new MonitoringSourceProperties();
            properties.setMode(value);

            assertThatThrownBy(properties::resolvedMode)
                    .as("value=[%s]：错误信息不回显配置值（它可能来自环境变量或命令行），只列合法取值", value)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining(value);
        }
    }

    @Test
    void validateDelegatesToTheSameStrictResolution() {
        MonitoringSourceProperties properties = new MonitoringSourceProperties();
        properties.setMode("Demo");

        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
    }
}
