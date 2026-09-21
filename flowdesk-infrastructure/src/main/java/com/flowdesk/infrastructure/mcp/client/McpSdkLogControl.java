package com.flowdesk.infrastructure.mcp.client;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import java.util.List;
import java.util.Locale;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 官方 MCP SDK 客户端日志的按包名控制（FD-0016-R1）。
 *
 * <h2>为什么需要它</h2>
 * <p>SDK 的客户端日志会写出<b>远端原文与内部细节</b>（实测 SDK 0.17.0）：</p>
 * <ul>
 *   <li>{@code LifecycleInitializer} 在 <b>INFO</b> 打印
 *       {@code Server response with Protocol: …, Capabilities: …, Info: … and Instructions …}
 *       —— 也就是远端的 {@code serverInfo} 与 {@code instructions} 原文；</li>
 *   <li>{@code LifecycleInitializer}、{@code McpSyncClient}、{@code McpAsyncClient} 在
 *       <b>WARN/ERROR</b> 打印异常对象（消息 + 堆栈），
 *       {@code HttpClientStreamableHttpTransport} 还会打印会话标识与传输细节；</li>
 *   <li>其余是 DEBUG 级别的报文与流跟踪。</li>
 * </ul>
 *
 * <h2>控制范围</h2>
 * <p>只调整<b>一个</b> logger 的级别：{@value #SDK_LOGGER_NAME}（SDK 自己的包）。
 * 不动根 logger、不动应用自身与其它依赖的日志，也不改日志格式与 appender ——
 * 因此本项目自己的固定元数据查询日志（{@code McpQueryLogger}）完全不受影响。</p>
 *
 * <h2>默认与排障</h2>
 * <p>默认 {@code OFF}（{@code flowdesk.mcp.client.sdk-log-level}），即默认生产配置下
 * 不会把远端原文、异常消息、端点细节或堆栈写进日志。排障时可以临时把它设为
 * {@code DEBUG}/{@code INFO}/{@code WARN}，但必须知道<b>风险</b>：那一档会把你正在排查的
 * 远端数据与内部细节写进日志，因此只在本地临时开启，不要留在交付配置里。</p>
 *
 * <p>若日志后端不是 Logback（本项目用的是 Logback），这里不做任何事，
 * 只输出一行提示：<b>不</b>用猜测的方式去改别人的日志配置。</p>
 */
final class McpSdkLogControl {

    /** 官方 MCP SDK 的包名（客户端日志都在它下面）。 */
    static final String SDK_LOGGER_NAME = "io.modelcontextprotocol";

    /** 默认级别：不输出 SDK 自己的日志。 */
    static final String DEFAULT_LEVEL = "OFF";

    /** 允许的级别取值。 */
    static final List<String> ALLOWED_LEVELS = List.of("OFF", "ERROR", "WARN", "INFO", "DEBUG", "TRACE");

    /** 固定错误文案（不回显配置原值）。 */
    static final String LEVEL_MESSAGE = "flowdesk.mcp.client.sdk-log-level 只允许 "
            + "OFF / ERROR / WARN / INFO / DEBUG / TRACE";

    private static final Logger log = LoggerFactory.getLogger(McpSdkLogControl.class);

    private McpSdkLogControl() {
    }

    /**
     * 校验级别取值。
     *
     * @param configuredLevel 配置原值
     * @return 规范化后的大写级别；不合法时为 {@code null}
     */
    static String normalizeLevel(String configuredLevel) {
        if (configuredLevel == null || configuredLevel.isBlank()) {
            return null;
        }
        String normalized = configuredLevel.trim().toUpperCase(Locale.ROOT);
        return ALLOWED_LEVELS.contains(normalized) ? normalized : null;
    }

    /**
     * 把 SDK 包名的日志级别设为配置值。
     *
     * @param configuredLevel 已经校验过的级别（来自 {@link McpClientProperties}）
     * @throws IllegalStateException 级别不在允许集合内
     */
    static void apply(String configuredLevel) {
        String normalized = normalizeLevel(configuredLevel);
        if (normalized == null) {
            throw new IllegalStateException(LEVEL_MESSAGE);
        }
        Level level = switch (normalized) {
            case "OFF" -> Level.OFF;
            case "ERROR" -> Level.ERROR;
            case "WARN" -> Level.WARN;
            case "INFO" -> Level.INFO;
            case "DEBUG" -> Level.DEBUG;
            default -> Level.TRACE;
        };

        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (factory instanceof LoggerContext context) {
            context.getLogger(SDK_LOGGER_NAME).setLevel(level);
            // 只记一行元数据：让运维知道「SDK 日志被按包名控制了」，日志里不含任何远端内容
            log.info("mcp sdk logging scoped logger={} level={}", SDK_LOGGER_NAME, level);
            return;
        }
        log.warn("当前日志后端不支持按包名控制 MCP SDK 日志，SDK 诊断日志保持该后端默认行为");
    }

    /**
     * 当前 SDK 包名的显式级别（用于测试与排障核对）。
     *
     * @return 级别名；未显式设置时为 {@code INHERITED}，后端不支持时为 {@code UNSUPPORTED}
     */
    static String currentLevel() {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (factory instanceof LoggerContext context) {
            Level level = context.getLogger(SDK_LOGGER_NAME).getLevel();
            return level == null ? "INHERITED" : level.toString();
        }
        return "UNSUPPORTED";
    }
}
