package com.flowdesk.domain.ticket;

/**
 * 工单领域错误码。
 *
 * <p>错误码是领域层对外暴露的稳定契约：上层（应用层、HTTP 适配器）据此映射响应，
 * 因此测试断言错误码而不是异常文案。</p>
 */
public enum TicketErrorCode {

    /** 工单标识缺失或格式非法。 */
    INVALID_TICKET_ID,

    /** 用户标识缺失、为空白或超长。 */
    INVALID_USER_ID,

    /** 标题缺失、为空白或超长。 */
    INVALID_TITLE,

    /** 描述缺失、为空白或超长。 */
    INVALID_DESCRIPTION,

    /** 处理结论缺失、为空白或超长。 */
    INVALID_RESOLUTION,

    /** 工单分类缺失。 */
    INVALID_CATEGORY,

    /** 工单优先级缺失。 */
    INVALID_PRIORITY,

    /** 时间戳缺失，或早于当前更新时间。 */
    INVALID_TIMESTAMP,

    /** 当前状态不允许该操作。 */
    ILLEGAL_STATUS_TRANSITION,

    /** 重新分配时新处理人与当前处理人相同。 */
    SAME_ASSIGNEE,

    /** 恢复的工单快照在状态与字段组合上不自洽。 */
    INVALID_RESTORED_STATE
}
