package com.flowdesk.mcp.monitoring.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 监控数据源配置（FD-0015）。
 *
 * <pre>
 * flowdesk:
 *   monitoring:
 *     source:
 *       mode: unavailable   # 默认；也可以显式设为 demo 使用内置虚构数据
 * </pre>
 *
 * <p><b>默认是 {@code unavailable}</b>：没有真实数据源时，工具返回稳定的
 * {@code MONITORING_SOURCE_UNAVAILABLE}，而不是拿虚构数据冒充真实监控。
 * 演示模式必须<b>显式</b>打开 ——「默认安全、显式开启」比「默认假装有数据」更难出错。</p>
 *
 * <h2>为什么是 String 而不是枚举</h2>
 * <p>Spring Boot 对枚举属性做<b>宽松</b>绑定：{@code DEMO}、{@code Demo}、{@code demo} 都会绑定到
 * 同一个枚举常量。本阶段的要求正好相反 —— <b>未知、大小写错误或带空白的值都必须启动失败</b>。
 * 因此这里按原始字符串接收，再用 {@link #resolvedMode()} 做<b>逐字符</b>匹配：
 * {@code unavailable} 与 {@code demo} 是仅有的两个合法值，其它一律拒绝（包括 {@code DEMO}、
 * {@code " demo "}、{@code "demo "}）。</p>
 *
 * <p>生产代码只经过 {@link #resolvedMode()} 这一个入口：装配期闸门
 * （{@code MonitoringMcpBindingGuard}）与数据源装配都调用它，因此不存在「两套校验」。</p>
 */
@ConfigurationProperties(prefix = "flowdesk.monitoring.source")
public class MonitoringSourceProperties {

    /** 合法模式值：没有数据源（默认）。 */
    public static final String MODE_UNAVAILABLE = "unavailable";

    /** 合法模式值：内置虚构演示数据。 */
    public static final String MODE_DEMO = "demo";

    /** 三种模式。 */
    public enum Mode {

        /** 没有任何数据源：每次查询都以 {@code MONITORING_SOURCE_UNAVAILABLE} 失败。 */
        UNAVAILABLE,

        /** 内置虚构演示数据：所有结果都带 {@code source=DEMO}。 */
        DEMO
    }

    private String mode = MODE_UNAVAILABLE;

    /**
     * @return 原始配置文本（不做任何归一化）
     */
    public String getMode() {
        return this.mode;
    }

    /**
     * @param mode 原始配置文本
     */
    public void setMode(String mode) {
        this.mode = mode;
    }

    /**
     * 严格解析模式：只接受精确的 {@code unavailable} 与 {@code demo}。
     *
     * @return 解析后的模式
     * @throws IllegalStateException 值缺失、为空或不是这两个字面量之一
     */
    public Mode resolvedMode() {
        String value = this.mode;
        if (MODE_UNAVAILABLE.equals(value)) {
            return Mode.UNAVAILABLE;
        }
        if (MODE_DEMO.equals(value)) {
            return Mode.DEMO;
        }
        // 错误信息不回显配置值（它可能来自环境变量或命令行），只说清合法取值
        throw new IllegalStateException("flowdesk.monitoring.source.mode 只允许精确的 "
                + MODE_UNAVAILABLE + " 或 " + MODE_DEMO
                + "（不接受未知值、大小写变体或带空白的值）");
    }
}
