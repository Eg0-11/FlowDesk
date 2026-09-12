package com.flowdesk.application.ai;

/**
 * 调用方请求不合法（对应 HTTP 400，业务错误码 {@code INVALID_REQUEST}）。
 *
 * <p>本异常的信息由 FlowDesk 自己构造，因此可以安全返回给客户端；
 * 不得把上游系统或框架的原始异常信息塞进这里。</p>
 */
public class AiRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 构造请求异常。
     *
     * @param message 面向调用方的安全描述
     */
    public AiRequestException(String message) {
        super(message);
    }
}
