package com.flowdesk.infrastructure.mcp.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP 查询日志（FD-0016）。
 *
 * <p>每次查询<b>只记一行</b>，字段固定为：服务别名、固定工具名、结果分类与耗时。</p>
 *
 * <p>刻意<b>不</b>记录：assetId 原值、监控数值、完整响应正文、异常消息、异常类名、堆栈、
 * 完整端点与配置。这些信息对排障有用，但代价是把业务数据与内部细节写进日志 ——
 * 本阶段选择「日志只留可聚合的元数据」。</p>
 */
final class McpQueryLogger {

    private static final Logger log = LoggerFactory.getLogger(McpQueryLogger.class);

    private McpQueryLogger() {
    }

    /**
     * @param alias       服务别名（{@code asset} / {@code monitoring}）
     * @param toolName    固定工具名
     * @param result      结果分类（{@code FOUND} / {@code NOT_FOUND} / 失败分类名）
     * @param startedAt   {@code System.nanoTime()} 起点
     */
    static void completed(String alias, String toolName, String result, long startedAt) {
        log.info("mcp query completed alias={} tool={} result={} durationMs={}", alias, toolName, result,
                elapsedMillis(startedAt));
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }
}
