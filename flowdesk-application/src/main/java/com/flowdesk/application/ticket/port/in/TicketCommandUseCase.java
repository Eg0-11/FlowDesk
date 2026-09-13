package com.flowdesk.application.ticket.port.in;

import com.flowdesk.application.ticket.command.AssignTicketCommand;
import com.flowdesk.application.ticket.command.CloseTicketCommand;
import com.flowdesk.application.ticket.command.CreateTicketCommand;
import com.flowdesk.application.ticket.command.ReassignTicketCommand;
import com.flowdesk.application.ticket.command.ResolveTicketCommand;
import com.flowdesk.application.ticket.command.StartTicketCommand;
import com.flowdesk.application.ticket.view.TicketView;

/**
 * 工单写入用例的输入端口。
 *
 * <p>六个用例覆盖工单的完整生命周期：创建、分配、重新分配、开始处理、提交结论、关闭。
 * 每个成功调用返回命令执行后的工单视图，其中 {@code version} 为写入后的新版本。</p>
 *
 * <p>失败语义：命令不合法抛 {@code TicketApplicationException(INVALID_COMMAND)}；
 * 工单不存在抛 {@code TICKET_NOT_FOUND}；版本不匹配抛 {@code TICKET_VERSION_CONFLICT}；
 * 领域规则失败则原样抛出 {@code TicketDomainException}。</p>
 */
public interface TicketCommandUseCase {

    /**
     * 创建工单。
     *
     * @param command 创建命令
     * @return 新建工单视图，状态为 {@code NEW}，版本为 0
     */
    TicketView create(CreateTicketCommand command);

    /**
     * 分配处理人。
     *
     * @param command 分配命令
     * @return 更新后的工单视图
     */
    TicketView assign(AssignTicketCommand command);

    /**
     * 重新分配处理人。
     *
     * @param command 重新分配命令
     * @return 更新后的工单视图
     */
    TicketView reassign(ReassignTicketCommand command);

    /**
     * 开始处理。
     *
     * @param command 开始处理命令
     * @return 更新后的工单视图
     */
    TicketView start(StartTicketCommand command);

    /**
     * 提交处理结论。
     *
     * @param command 提交结论命令
     * @return 更新后的工单视图
     */
    TicketView resolve(ResolveTicketCommand command);

    /**
     * 关闭工单。
     *
     * @param command 关闭命令
     * @return 更新后的工单视图
     */
    TicketView close(CloseTicketCommand command);
}
