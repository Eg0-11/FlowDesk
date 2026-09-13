package com.flowdesk.application.ticket.view;

import com.flowdesk.domain.ticket.Ticket;
import com.flowdesk.domain.ticket.TicketCategory;
import com.flowdesk.domain.ticket.TicketId;
import com.flowdesk.domain.ticket.TicketPriority;
import com.flowdesk.domain.ticket.TicketStatus;
import com.flowdesk.domain.ticket.UserId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 工单只读视图：应用层对外的唯一输出形态。
 *
 * <p>不可变 record。输入适配器只拿到本视图，<b>拿不到可变聚合</b>，因此不可能绕过用例
 * 直接改动工单状态。四个可空字段用非空的 {@link Optional} 表达，
 * 调用方无需再判 {@code null}。</p>
 *
 * @param id          工单标识
 * @param title       标题
 * @param description 描述
 * @param category    分类
 * @param priority    优先级
 * @param requesterId 请求人
 * @param assigneeId  处理人；未分配时为空
 * @param status      当前状态
 * @param resolution  处理结论；未提交时为空
 * @param createdAt   创建时间
 * @param updatedAt   最近更新时间
 * @param resolvedAt  解决时间；未解决时为空
 * @param closedAt    关闭时间；未关闭时为空
 * @param version     乐观并发版本
 */
public record TicketView(TicketId id,
                         String title,
                         String description,
                         TicketCategory category,
                         TicketPriority priority,
                         UserId requesterId,
                         Optional<UserId> assigneeId,
                         TicketStatus status,
                         Optional<String> resolution,
                         Instant createdAt,
                         Instant updatedAt,
                         Optional<Instant> resolvedAt,
                         Optional<Instant> closedAt,
                         long version) {

    public TicketView {
        Objects.requireNonNull(id, "id 不能为 null");
        Objects.requireNonNull(status, "status 不能为 null");
        Objects.requireNonNull(createdAt, "createdAt 不能为 null");
        Objects.requireNonNull(updatedAt, "updatedAt 不能为 null");
        assigneeId = Objects.requireNonNull(assigneeId, "assigneeId 必须是非 null 的 Optional");
        resolution = Objects.requireNonNull(resolution, "resolution 必须是非 null 的 Optional");
        resolvedAt = Objects.requireNonNull(resolvedAt, "resolvedAt 必须是非 null 的 Optional");
        closedAt = Objects.requireNonNull(closedAt, "closedAt 必须是非 null 的 Optional");
    }

    /**
     * 由聚合与版本构造视图，完整映射全部领域字段。
     *
     * @param ticket  工单聚合
     * @param version 该聚合的当前版本
     * @return 只读视图
     */
    public static TicketView from(Ticket ticket, long version) {
        return new TicketView(
                ticket.id(),
                ticket.title(),
                ticket.description(),
                ticket.category(),
                ticket.priority(),
                ticket.requesterId(),
                ticket.assigneeId(),
                ticket.status(),
                ticket.resolution(),
                ticket.createdAt(),
                ticket.updatedAt(),
                ticket.resolvedAt(),
                ticket.closedAt(),
                version);
    }
}
