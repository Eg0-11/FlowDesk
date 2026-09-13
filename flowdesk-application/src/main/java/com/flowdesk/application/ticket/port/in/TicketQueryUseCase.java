package com.flowdesk.application.ticket.port.in;

import com.flowdesk.application.ticket.query.GetTicketQuery;
import com.flowdesk.application.ticket.view.TicketView;

/**
 * 工单查询用例的输入端口。
 *
 * <p>查询是只读操作：不得写入存储、不得推进版本、不得读取时间。</p>
 */
public interface TicketQueryUseCase {

    /**
     * 查询单个工单。
     *
     * @param query 查询条件
     * @return 工单视图
     * @throws com.flowdesk.application.ticket.TicketApplicationException 查询不合法或工单不存在
     */
    TicketView get(GetTicketQuery query);
}
