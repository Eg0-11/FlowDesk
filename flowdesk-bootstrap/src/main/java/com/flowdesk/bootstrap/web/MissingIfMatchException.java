package com.flowdesk.bootstrap.web;

/**
 * 缺少版本前置条件（{@code If-Match}）：映射为 428 {@code PRECONDITION_REQUIRED}。
 *
 * <p>放在 {@code bootstrap.web} 而不是某个具体资源包里：工单与知识文档都用它。</p>
 */
public class MissingIfMatchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 固定文案：不携带任何请求内容。
     */
    public MissingIfMatchException() {
        super("状态变更请求必须携带 If-Match 头");
    }
}
