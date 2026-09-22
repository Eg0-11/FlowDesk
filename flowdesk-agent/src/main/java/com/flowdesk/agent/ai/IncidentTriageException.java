package com.flowdesk.agent.ai;

/**
 * 事件研判编排的内部失败载体（FD-0018-A）。
 *
 * <p>{@link #getMessage()} 是固定的稳定文案（失败类别的名字），不含任何业务数据与框架细节；
 * 对外最终统一为 {@link com.flowdesk.application.ai.AiProviderException} 的固定文案。</p>
 */
public class IncidentTriageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient IncidentTriageFailure failure;

    /**
     * @param failure 稳定失败类别
     */
    public IncidentTriageException(IncidentTriageFailure failure) {
        super(failure.name());
        this.failure = failure;
    }

    /**
     * @param failure 稳定失败类别
     * @param cause   原始异常（仅服务端诊断；不进入消息与响应）
     */
    public IncidentTriageException(IncidentTriageFailure failure, Throwable cause) {
        super(failure.name(), cause);
        this.failure = failure;
    }

    /**
     * @return 稳定失败类别
     */
    public IncidentTriageFailure failure() {
        return this.failure;
    }
}
