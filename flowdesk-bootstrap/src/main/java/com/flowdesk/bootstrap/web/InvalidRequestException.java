package com.flowdesk.bootstrap.web;

/**
 * HTTP 边界上发现的请求格式问题。
 *
 * <p>用于路径参数等「HTTP 层就能判定不合法」的情形，统一映射为
 * 400 {@code INVALID_REQUEST}，与 Bean Validation、JSON 解析失败保持同一契约。</p>
 *
 * <p>文案必须是固定说明，不得包含调用方传入的原始值。</p>
 */
public class InvalidRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param detail 面向调用方的固定说明，不含原始输入
     */
    public InvalidRequestException(String detail) {
        super(detail);
    }
}
