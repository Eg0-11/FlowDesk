package com.flowdesk.application.ticket;

/**
 * 工单应用层错误码。
 *
 * <p>与领域错误码一样，这是对外暴露的稳定契约：输入适配器据此映射响应，
 * 因此上层与测试都断言错误码，而不是解析异常文案。</p>
 *
 * <p>领域层自身的失败（字段非法、非法状态转换等）不使用这里的错误码，
 * 而是原样抛出 {@code com.flowdesk.domain.ticket.TicketDomainException}，
 * 保持 {@code TicketErrorCode} 向上传递。</p>
 */
public enum TicketApplicationErrorCode {

    /** 命令或查询本身不合法：命令为空、工单标识为空、预期版本为负数。 */
    INVALID_COMMAND,

    /** 目标工单不存在（或在本用例读取前已被删除）。 */
    TICKET_NOT_FOUND,

    /** 插入的工单标识已存在。 */
    TICKET_ALREADY_EXISTS,

    /** 版本不匹配：调用方持有的版本已过期，或并发写入导致 compare-and-set 失败。 */
    TICKET_VERSION_CONFLICT
}
