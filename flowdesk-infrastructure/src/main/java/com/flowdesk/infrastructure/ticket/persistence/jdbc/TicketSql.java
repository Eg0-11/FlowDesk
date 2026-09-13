package com.flowdesk.infrastructure.ticket.persistence.jdbc;

/**
 * 工单持久化 SQL 常量。
 *
 * <p>全部为静态常量，参数一律用占位符绑定，不存在任何字符串拼接 SQL 的路径。
 * 列顺序与 {@link #INSERT} 的取值顺序、{@link TicketRowMapper} 的读取顺序保持一致。</p>
 */
final class TicketSql {

    static final String COLUMNS = "id, title, description, category, priority, requester_id, assignee_id, "
            + "status, resolution, created_at, updated_at, resolved_at, closed_at, version";

    static final String SELECT_BY_ID = "SELECT " + COLUMNS + " FROM tickets WHERE id = ?";

    /** 在事务内锁定目标行并读取当前版本。 */
    static final String SELECT_VERSION_FOR_UPDATE = "SELECT version FROM tickets WHERE id = ? FOR UPDATE";

    /** 插入时版本固定写 0，不接受调用方传入。 */
    static final String INSERT = "INSERT INTO tickets (" + COLUMNS + ") "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)";

    /** 条件更新：只有 id 与 version 同时匹配才写入，并把版本加 1。 */
    static final String UPDATE = "UPDATE tickets SET title = ?, description = ?, category = ?, priority = ?, "
            + "requester_id = ?, assignee_id = ?, status = ?, resolution = ?, created_at = ?, updated_at = ?, "
            + "resolved_at = ?, closed_at = ?, version = version + 1 "
            + "WHERE id = ? AND version = ?";

    private TicketSql() {
    }
}
