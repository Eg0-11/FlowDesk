package com.flowdesk.bootstrap.web;

/**
 * {@code If-Match} 格式非法：映射为 400 {@code INVALID_IF_MATCH}。
 *
 * <p>固定文案，不回显客户端传入的头值。</p>
 */
public class InvalidIfMatchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 固定文案：不携带任何请求内容。
     */
    public InvalidIfMatchException() {
        super("If-Match 格式非法");
    }
}
