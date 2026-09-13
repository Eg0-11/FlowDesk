package com.flowdesk.bootstrap.ticket;

import com.flowdesk.application.ticket.view.TicketView;
import com.flowdesk.domain.ticket.TicketCategory;
import com.flowdesk.domain.ticket.TicketPriority;
import com.flowdesk.domain.ticket.TicketStatus;
import com.flowdesk.domain.ticket.UserId;
import java.time.Instant;
import java.util.UUID;

/**
 * 工单响应体。
 *
 * <p>刻意不直接暴露 {@link TicketView}：视图里用的是 {@code Optional} 与领域值对象，
 * 而 HTTP 契约要求标识是普通字符串、可空字段是 JSON {@code null}、时间是 ISO-8601。
 * 这一层转换把领域表示与线上表示解耦，领域模型调整不会泄漏成 API 破坏性变更。</p>
 *
 * @param id          工单标识（UUID 字符串）
 * @param title       标题
 * @param description 描述
 * @param category    分类
 * @param priority    优先级
 * @param requesterId 请求人（普通字符串）
 * @param assigneeId  处理人；未分配时为 {@code null}
 * @param status      状态
 * @param resolution  处理结论；未提交时为 {@code null}
 * @param createdAt   创建时间（ISO-8601）
 * @param updatedAt   最近更新时间
 * @param resolvedAt  解决时间；未解决时为 {@code null}
 * @param closedAt    关闭时间；未关闭时为 {@code null}
 * @param version     乐观并发版本，必须与响应头 {@code ETag} 一致
 */
public record TicketResponse(UUID id,
                             String title,
                             String description,
                             TicketCategory category,
                             TicketPriority priority,
                             String requesterId,
                             String assigneeId,
                             TicketStatus status,
                             String resolution,
                             Instant createdAt,
                             Instant updatedAt,
                             Instant resolvedAt,
                             Instant closedAt,
                             long version) {

    /**
     * @param view 应用层只读视图
     * @return 响应体
     */
    public static TicketResponse from(TicketView view) {
        return new TicketResponse(
                view.id().value(),
                view.title(),
                view.description(),
                view.category(),
                view.priority(),
                view.requesterId().value(),
                view.assigneeId().map(UserId::value).orElse(null),
                view.status(),
                view.resolution().orElse(null),
                view.createdAt(),
                view.updatedAt(),
                view.resolvedAt().orElse(null),
                view.closedAt().orElse(null),
                view.version());
    }
}
