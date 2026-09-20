package com.flowdesk.mcp.monitoring.snapshot;

/**
 * 监控数据源不可用（FD-0015）。
 *
 * <p>出现的情形：没有配置真实监控数据源、数据源连接失败、上游返回异常等。
 * 工具层把它映射为稳定的 {@code MONITORING_SOURCE_UNAVAILABLE}。</p>
 *
 * <p><b>消息是固定的服务端文案</b>（{@value #MESSAGE}），不含路径、配置、地址、凭证或上游原文。
 * 原始异常只允许作为 {@link #getCause()} 保留在服务端，且工具层<b>不会</b>把它写进响应或日志正文
 * ——日志里只出现异常类名。</p>
 */
public class SnapshotSourceUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 固定的服务端文案：可以安全地出现在日志里（不会进入工具响应）。 */
    public static final String MESSAGE = "监控数据源当前不可用";

    /**
     * 无原始异常（例如「本阶段没有实现真实数据源」）。
     */
    public SnapshotSourceUnavailableException() {
        super(MESSAGE);
    }

    /**
     * @param cause 原始异常，仅用于服务端诊断，不得外泄
     */
    public SnapshotSourceUnavailableException(Throwable cause) {
        super(MESSAGE, cause);
    }
}
