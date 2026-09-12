package com.flowdesk.application.ai;

/**
 * 上游模型服务调用失败（对应 HTTP 502，业务错误码 {@code AI_PROVIDER_ERROR}）。
 *
 * <p>{@link #getMessage()} 是面向上层调用方的固定安全文案，不含供应商原始报文、堆栈或配置；
 * 原始异常仅通过 {@link #getCause()} 保留，且不得写入客户端响应。</p>
 */
public class AiProviderException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String requestId;

    /**
     * 构造上游异常。
     *
     * @param requestId 服务端请求标识，便于与日志对齐
     * @param cause     原始异常，仅用于诊断，不得外泄
     */
    public AiProviderException(String requestId, Throwable cause) {
        super("上游 AI 服务调用失败", cause);
        this.requestId = requestId;
    }

    /**
     * @return 服务端请求标识；在请求标识尚未生成时可能为 {@code null}
     */
    public String requestId() {
        return requestId;
    }
}
