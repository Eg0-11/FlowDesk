package com.flowdesk.infrastructure.mcp.client;

import com.flowdesk.application.integration.QueryFailure;

/**
 * 基础设施内部的失败载体（FD-0016）。
 *
 * <p>{@link McpToolClient} 在「这次调用没有成功」时抛出它，适配器把它转成
 * {@code QueryOutcome.FAILED} + {@link QueryFailure}。它<b>不会</b>逃出基础设施层，
 * 也<b>不</b>携带远端原文、完整端点或异常消息：消息就是失败分类的名字。</p>
 */
final class McpClientFailureException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient QueryFailure failure;

    /**
     * @param failure 稳定失败分类
     * @param cause   原始异常（只用于服务端诊断，不进入消息、日志正文或响应）
     */
    McpClientFailureException(QueryFailure failure, Throwable cause) {
        super(failure.name(), cause);
        this.failure = failure;
    }

    /**
     * @return 稳定失败分类
     */
    QueryFailure failure() {
        return this.failure;
    }
}
