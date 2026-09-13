package com.flowdesk.application.ticket;

import java.util.Objects;

/**
 * 工单应用层异常。
 *
 * <p>始终携带非空的 {@link TicketApplicationErrorCode}；异常文案只用于人工排查，
 * 不得作为契约，也<b>不得回显标题、描述、处理结论或用户原始输入</b>。</p>
 *
 * <p>领域层抛出的 {@code TicketDomainException} 不会被本异常包装，
 * 因此 {@code TicketErrorCode} 会原样到达输入适配器。</p>
 */
public class TicketApplicationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final TicketApplicationErrorCode errorCode;

    /**
     * @param errorCode 非空的应用层错误码
     * @param message   面向开发者的说明，不含业务原始输入
     */
    public TicketApplicationException(TicketApplicationErrorCode errorCode, String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode 不能为 null");
    }

    /**
     * @return 应用层错误码
     */
    public TicketApplicationErrorCode errorCode() {
        return this.errorCode;
    }
}
