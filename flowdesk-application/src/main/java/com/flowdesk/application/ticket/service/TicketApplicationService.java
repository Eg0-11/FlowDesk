package com.flowdesk.application.ticket.service;

import com.flowdesk.application.ticket.TicketApplicationErrorCode;
import com.flowdesk.application.ticket.TicketApplicationException;
import com.flowdesk.application.ticket.command.AssignTicketCommand;
import com.flowdesk.application.ticket.command.CloseTicketCommand;
import com.flowdesk.application.ticket.command.CreateTicketCommand;
import com.flowdesk.application.ticket.command.ReassignTicketCommand;
import com.flowdesk.application.ticket.command.ResolveTicketCommand;
import com.flowdesk.application.ticket.command.StartTicketCommand;
import com.flowdesk.application.ticket.port.in.TicketCommandUseCase;
import com.flowdesk.application.ticket.port.in.TicketQueryUseCase;
import com.flowdesk.application.ticket.port.out.TicketIdGenerator;
import com.flowdesk.application.ticket.port.out.TicketRepository;
import com.flowdesk.application.ticket.port.out.TicketSearchCriteria;
import com.flowdesk.application.ticket.port.out.TicketSearchResult;
import com.flowdesk.application.ticket.port.out.TimeProvider;
import com.flowdesk.application.ticket.port.out.VersionedTicket;
import com.flowdesk.application.ticket.query.GetTicketQuery;
import com.flowdesk.application.ticket.query.SearchTicketsQuery;
import com.flowdesk.application.ticket.query.TicketSearchQueryNormalizer;
import com.flowdesk.application.ticket.view.TicketPageView;
import com.flowdesk.application.ticket.view.TicketView;
import com.flowdesk.domain.ticket.Ticket;
import com.flowdesk.domain.ticket.TicketId;
import java.time.Instant;
import java.util.Objects;

/**
 * 工单用例服务：纯 Java、无状态、框架无关。
 *
 * <p>依赖全部经构造器注入（{@link TicketRepository}、{@link TicketIdGenerator}、{@link TimeProvider}），
 * 不使用 {@code UUID.randomUUID()}、{@code Instant.now()}、{@code Clock.system*}、Spring 注解
 * 或任何静态全局依赖 —— 因此同一份输入永远得到同一份输出，可直接单元测试。</p>
 *
 * <h2>创建流程</h2>
 * <ol>
 *   <li>校验命令非 {@code null}；</li>
 *   <li>取新标识与当前时间；</li>
 *   <li>{@code Ticket.create(...)} 由领域聚合完成全部字段校验；</li>
 *   <li>{@code repository.insert(...)}，返回的新版本为 0；</li>
 *   <li>映射为 {@link TicketView}。</li>
 * </ol>
 *
 * <h2>状态变更流程</h2>
 * <ol>
 *   <li>校验命令非 {@code null}、工单标识非 {@code null}、预期版本非负；</li>
 *   <li>{@code repository.findById(...)}，不存在则 {@code TICKET_NOT_FOUND}；</li>
 *   <li>比较预期版本与当前版本，<b>先于任何领域修改</b>；不一致则 {@code TICKET_VERSION_CONFLICT}，
 *       此时不读取时间、不调用领域方法、不写存储；</li>
 *   <li>读取一次当前时间；</li>
 *   <li>调用对应的领域状态转换；领域规则失败时异常直接向上抛出，
 *       <b>不写存储</b>；</li>
 *   <li>{@code repository.update(ticket, expectedVersion)} 以 compare-and-set 写回；
 *       若读取后、写入前发生并发写入，存储抛出的
 *       {@code TICKET_VERSION_CONFLICT} 原样向上传播；</li>
 *   <li>用存储返回的新版本生成视图。</li>
 * </ol>
 *
 * <p>查询流程只读取一次存储，不读时间、不写存储、不推进版本、不生成标识。</p>
 *
 * <h2>列表 / 搜索流程</h2>
 * <ol>
 *   <li>校验查询非 {@code null}；</li>
 *   <li>{@link TicketSearchQueryNormalizer#normalize} 套用默认值、strip 字符串、
 *       解析枚举与排序白名单 —— 不合法即抛 {@code INVALID_COMMAND}，此时<b>不触碰存储</b>；</li>
 *   <li>调用 {@link TicketRepository#search} 一次取回「当前页 + 总数」；</li>
 *   <li>把 {@link VersionedTicket} 映射成 {@link TicketView} 并推导分页元数据。</li>
 * </ol>
 * <p>全程不生成标识、不读取时间、不写存储、不推进版本，因此列表查询永远是无副作用的。</p>
 *
 * <p>错误码语义见 {@link TicketApplicationErrorCode}；领域层抛出的
 * {@code TicketDomainException} 不被包装，保持原始领域错误码向上传递。</p>
 */
public final class TicketApplicationService implements TicketCommandUseCase, TicketQueryUseCase {

    private final TicketRepository ticketRepository;

    private final TicketIdGenerator ticketIdGenerator;

    private final TimeProvider timeProvider;

    /**
     * @param ticketRepository  工单存储端口
     * @param ticketIdGenerator 标识生成端口
     * @param timeProvider      时间端口
     */
    public TicketApplicationService(TicketRepository ticketRepository, TicketIdGenerator ticketIdGenerator,
            TimeProvider timeProvider) {
        this.ticketRepository = Objects.requireNonNull(ticketRepository, "ticketRepository 不能为 null");
        this.ticketIdGenerator = Objects.requireNonNull(ticketIdGenerator, "ticketIdGenerator 不能为 null");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider 不能为 null");
    }

    @Override
    public TicketView create(CreateTicketCommand command) {
        requireCommand(command);

        TicketId ticketId = this.ticketIdGenerator.nextId();
        Instant now = this.timeProvider.now();
        Ticket ticket = Ticket.create(ticketId, command.title(), command.description(), command.category(),
                command.priority(), command.requesterId(), now);

        return toView(this.ticketRepository.insert(ticket));
    }

    @Override
    public TicketView assign(AssignTicketCommand command) {
        requireCommand(command);
        TicketId ticketId = requireTicketId(command.ticketId());
        long expectedVersion = requireVersion(command.expectedVersion());

        Ticket ticket = loadForUpdate(ticketId, expectedVersion);
        ticket.assign(command.assigneeId(), this.timeProvider.now());

        return toView(this.ticketRepository.update(ticket, expectedVersion));
    }

    @Override
    public TicketView reassign(ReassignTicketCommand command) {
        requireCommand(command);
        TicketId ticketId = requireTicketId(command.ticketId());
        long expectedVersion = requireVersion(command.expectedVersion());

        Ticket ticket = loadForUpdate(ticketId, expectedVersion);
        ticket.reassign(command.newAssigneeId(), this.timeProvider.now());

        return toView(this.ticketRepository.update(ticket, expectedVersion));
    }

    @Override
    public TicketView start(StartTicketCommand command) {
        requireCommand(command);
        TicketId ticketId = requireTicketId(command.ticketId());
        long expectedVersion = requireVersion(command.expectedVersion());

        Ticket ticket = loadForUpdate(ticketId, expectedVersion);
        ticket.start(this.timeProvider.now());

        return toView(this.ticketRepository.update(ticket, expectedVersion));
    }

    @Override
    public TicketView resolve(ResolveTicketCommand command) {
        requireCommand(command);
        TicketId ticketId = requireTicketId(command.ticketId());
        long expectedVersion = requireVersion(command.expectedVersion());

        Ticket ticket = loadForUpdate(ticketId, expectedVersion);
        ticket.resolve(command.resolution(), this.timeProvider.now());

        return toView(this.ticketRepository.update(ticket, expectedVersion));
    }

    @Override
    public TicketView close(CloseTicketCommand command) {
        requireCommand(command);
        TicketId ticketId = requireTicketId(command.ticketId());
        long expectedVersion = requireVersion(command.expectedVersion());

        Ticket ticket = loadForUpdate(ticketId, expectedVersion);
        ticket.close(this.timeProvider.now());

        return toView(this.ticketRepository.update(ticket, expectedVersion));
    }

    @Override
    public TicketView get(GetTicketQuery query) {
        requireCommand(query);
        TicketId ticketId = requireTicketId(query.ticketId());

        VersionedTicket found = this.ticketRepository.findById(ticketId)
                .orElseThrow(() -> new TicketApplicationException(TicketApplicationErrorCode.TICKET_NOT_FOUND,
                        "工单不存在"));

        return toView(found);
    }

    @Override
    public TicketPageView search(SearchTicketsQuery query) {
        requireCommand(query);

        // 校验与规范化集中在校验器里：本方法只负责编排（校验 → 查询 → 映射视图）
        TicketSearchCriteria criteria = TicketSearchQueryNormalizer.normalize(query);

        TicketSearchResult result = this.ticketRepository.search(criteria);

        return TicketPageView.from(result, criteria);
    }

    /**
     * 读取并校验版本：版本检查发生在任何领域修改之前。
     */
    private Ticket loadForUpdate(TicketId ticketId, long expectedVersion) {
        VersionedTicket current = this.ticketRepository.findById(ticketId)
                .orElseThrow(() -> new TicketApplicationException(TicketApplicationErrorCode.TICKET_NOT_FOUND,
                        "工单不存在"));

        if (current.version() != expectedVersion) {
            throw new TicketApplicationException(TicketApplicationErrorCode.TICKET_VERSION_CONFLICT,
                    "工单版本已变化，请重新读取后再试");
        }
        return current.ticket();
    }

    private static TicketView toView(VersionedTicket versioned) {
        return TicketView.from(versioned.ticket(), versioned.version());
    }

    private static <T> T requireCommand(T command) {
        if (command == null) {
            throw new TicketApplicationException(TicketApplicationErrorCode.INVALID_COMMAND, "命令不能为空");
        }
        return command;
    }

    private static TicketId requireTicketId(TicketId ticketId) {
        if (ticketId == null) {
            throw new TicketApplicationException(TicketApplicationErrorCode.INVALID_COMMAND, "工单标识不能为空");
        }
        return ticketId;
    }

    private static long requireVersion(long expectedVersion) {
        if (expectedVersion < 0) {
            throw new TicketApplicationException(TicketApplicationErrorCode.INVALID_COMMAND,
                    "预期版本不能为负数");
        }
        return expectedVersion;
    }
}
