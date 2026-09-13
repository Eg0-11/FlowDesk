package com.flowdesk.application.ticket.port.in;

import com.flowdesk.application.ticket.query.GetTicketQuery;
import com.flowdesk.application.ticket.query.SearchTicketsQuery;
import com.flowdesk.application.ticket.view.TicketPageView;
import com.flowdesk.application.ticket.view.TicketView;

/**
 * 工单查询用例的输入端口。
 *
 * <p>查询是只读操作：不得写入存储、不得推进版本、不得读取时间、不得生成标识。</p>
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

    /**
     * 分页查询 / 条件搜索工单。
     *
     * <p>与 {@link #get(GetTicketQuery)} 一样是纯读操作；查询条件不合法时抛
     * {@link com.flowdesk.application.ticket.TicketApplicationException}
     * （{@code INVALID_QUERY}，与其他用例的 {@code INVALID_COMMAND} 区分开，
     * 便于输入适配器精确映射），越界页则返回空 {@code items} 的正常结果。</p>
     *
     * <p><b>规范化与校验只在本用例内部发生一次</b>：调用方（包括 HTTP 适配器）
     * 不应在调用前自己再校验一遍。</p>
     *
     * @param query 原始查询条件；由应用层负责规范化与校验
     * @return 分页视图
     * @throws com.flowdesk.application.ticket.TicketApplicationException 查询条件不合法
     */
    TicketPageView search(SearchTicketsQuery query);
}
