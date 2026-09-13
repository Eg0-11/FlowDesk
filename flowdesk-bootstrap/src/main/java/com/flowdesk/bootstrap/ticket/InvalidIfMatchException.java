package com.flowdesk.bootstrap.ticket;

/**
 * {@code If-Match} 头格式非法。
 *
 * <p>对应 HTTP 400：只接受单个、强类型、规范十进制的 ETag。</p>
 */
class InvalidIfMatchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    InvalidIfMatchException() {
        super("If-Match 头格式非法");
    }
}
