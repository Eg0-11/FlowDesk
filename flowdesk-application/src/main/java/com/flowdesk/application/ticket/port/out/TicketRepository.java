package com.flowdesk.application.ticket.port.out;

import com.flowdesk.application.ticket.TicketApplicationErrorCode;
import com.flowdesk.application.ticket.TicketApplicationException;
import com.flowdesk.domain.ticket.Ticket;
import com.flowdesk.domain.ticket.TicketId;
import java.util.Optional;

/**
 * 工单存储输出端口（乐观并发）。适配器可以是内存、关系数据库或任何其它存储。
 *
 * <h2>版本语义</h2>
 * <ul>
 *   <li>新插入的工单初始版本为 {@code 0}；</li>
 *   <li>每次成功更新后版本<b>严格加 1</b>，不存在跳号或回退；</li>
 *   <li>版本由存储维护，调用方不能指定新版本，只能声明自己读到的版本。</li>
 * </ul>
 *
 * <h2>并发语义</h2>
 * <ul>
 *   <li>{@link #insert(Ticket)} 必须<b>原子地</b>拒绝重复标识：两个并发插入同一个
 *       {@link TicketId} 时，最多只有一个成功，另一个必须以
 *       {@link TicketApplicationErrorCode#TICKET_ALREADY_EXISTS} 失败；</li>
 *   <li>{@link #update(Ticket, long)} 必须实现<b>原子 compare-and-set</b>：
 *       只有当存储中的当前版本等于 {@code expectedVersion} 时才写入并把版本加 1，
 *       否则不产生任何写入；</li>
 *   <li>更新失败时，<b>记录不存在</b>（{@code TICKET_NOT_FOUND}）与
 *       <b>版本不匹配</b>（{@code TICKET_VERSION_CONFLICT}）必须被区分，
 *       不能合并成同一种失败。</li>
 * </ul>
 *
 * <h2>隔离语义</h2>
 * <ul>
 *   <li>{@link #findById(TicketId)} 必须返回<b>独立恢复</b>的聚合：适配器不得把内部可变存储
 *       的引用、缓存实例或同一对象暴露给调用方；调用方修改返回的聚合绝不能影响存储内容；</li>
 *   <li>同理，写入完成后适配器不得继续持有调用方传入的聚合实例；</li>
 *   <li>任何方法都<b>不得返回 {@code null}</b> 来代替 {@link Optional#empty()}。</li>
 * </ul>
 *
 * <h2>失败语义</h2>
 * <p>三种存储级失败都以 {@link TicketApplicationException} 抛出，并使用应用层错误码，
 * 不泄漏存储实现细节。</p>
 */
public interface TicketRepository {

    /**
     * 按标识读取工单。
     *
     * @param ticketId 工单标识
     * @return 工单及其当前版本；不存在时返回 {@link Optional#empty()}，绝不返回 {@code null}
     */
    Optional<VersionedTicket> findById(TicketId ticketId);

    /**
     * 插入新工单。
     *
     * @param ticket 待插入的工单
     * @return 已存储的工单及其版本，版本恒为 {@code 0}
     * @throws TicketApplicationException 标识已存在时抛出
     *                                    {@link TicketApplicationErrorCode#TICKET_ALREADY_EXISTS}
     */
    VersionedTicket insert(Ticket ticket);

    /**
     * 以 compare-and-set 方式更新工单。
     *
     * @param ticket          更新后的工单聚合
     * @param expectedVersion 调用方读取到的版本
     * @return 已存储的工单及其新版本，新版本等于 {@code expectedVersion + 1}
     * @throws TicketApplicationException 记录不存在时抛出 {@link TicketApplicationErrorCode#TICKET_NOT_FOUND}；
     *                                    版本不匹配时抛出
     *                                    {@link TicketApplicationErrorCode#TICKET_VERSION_CONFLICT}
     */
    VersionedTicket update(Ticket ticket, long expectedVersion);
}
