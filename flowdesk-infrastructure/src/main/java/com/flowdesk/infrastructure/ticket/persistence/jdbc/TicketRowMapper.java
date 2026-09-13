package com.flowdesk.infrastructure.ticket.persistence.jdbc;

import com.flowdesk.application.ticket.port.out.VersionedTicket;
import com.flowdesk.domain.ticket.Ticket;
import com.flowdesk.domain.ticket.TicketCategory;
import com.flowdesk.domain.ticket.TicketId;
import com.flowdesk.domain.ticket.TicketPriority;
import com.flowdesk.domain.ticket.TicketStatus;
import com.flowdesk.domain.ticket.UserId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 数据库行到领域聚合的映射。
 *
 * <p>每次映射都通过 {@link Ticket#restore} 构造<b>全新</b>聚合，因此读取结果永远是独立对象，
 * 既不是 JDBC 驱动缓存的实例，也不会与数据库或其它调用方共享。</p>
 *
 * <p>时间列统一按 {@link OffsetDateTime} 读取再转 {@link Instant}：数据库里的
 * {@code TIMESTAMP(6) WITH TIME ZONE} 表示绝对时刻，转成 Instant 后与时区无关，
 * 保证微秒精度往返不丢失。</p>
 */
final class TicketRowMapper {

    private TicketRowMapper() {
    }

    /**
     * 标准 {@link org.springframework.jdbc.core.RowMapper} 签名。
     *
     * <p>刻意保留 {@code rowNum} 参数：只带 {@link ResultSet} 的方法引用会被编译器解析成
     * {@code ResultSetExtractor}，从而走上完全不同的查询路径。</p>
     *
     * @param resultSet 已定位到一行的结果集
     * @param rowNum    行号，未使用
     * @return 独立恢复的工单及其版本
     * @throws SQLException 读取失败
     */
    static VersionedTicket mapRow(ResultSet resultSet, int rowNum) throws SQLException {
        UUID id = resultSet.getObject("id", UUID.class);
        String title = resultSet.getString("title");
        String description = resultSet.getString("description");
        TicketCategory category = TicketCategory.valueOf(resultSet.getString("category"));
        TicketPriority priority = TicketPriority.valueOf(resultSet.getString("priority"));
        UserId requesterId = UserId.of(resultSet.getString("requester_id"));
        UserId assigneeId = toUserId(resultSet.getString("assignee_id"));
        TicketStatus status = TicketStatus.valueOf(resultSet.getString("status"));
        String resolution = resultSet.getString("resolution");
        Instant createdAt = toInstant(resultSet.getObject("created_at", OffsetDateTime.class));
        Instant updatedAt = toInstant(resultSet.getObject("updated_at", OffsetDateTime.class));
        Instant resolvedAt = toInstant(resultSet.getObject("resolved_at", OffsetDateTime.class));
        Instant closedAt = toInstant(resultSet.getObject("closed_at", OffsetDateTime.class));
        long version = resultSet.getLong("version");

        Ticket ticket = Ticket.restore(TicketId.of(id), title, description, category, priority, requesterId,
                assigneeId, status, resolution, createdAt, updatedAt, resolvedAt, closedAt);
        return new VersionedTicket(ticket, version);
    }

    private static UserId toUserId(String value) {
        return value == null ? null : UserId.of(value);
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
