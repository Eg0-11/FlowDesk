package com.flowdesk.bootstrap.ticket;

/**
 * {@code If-Match} 头缺失。
 *
 * <p>对应 HTTP 428 Precondition Required：状态变更接口必须携带版本前置条件。</p>
 */
class MissingIfMatchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    MissingIfMatchException() {
        super("缺少 If-Match 头");
    }
}
