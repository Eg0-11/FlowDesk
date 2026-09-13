package com.flowdesk.domain.ticket;

import java.time.Instant;
import java.util.Optional;

/**
 * 工单聚合根。
 *
 * <p>纯 Java 实现：不依赖 Spring、持久化框架或任何外部系统，时间与标识一律由调用方传入，
 * 聚合内部从不读取系统时钟。</p>
 *
 * <h2>生命周期状态机</h2>
 * <table border="1">
 *   <caption>合法状态转换</caption>
 *   <tr><th>操作</th><th>允许的当前状态</th><th>目标状态</th><th>附加约束</th></tr>
 *   <tr><td>{@link #assign(UserId, Instant)}</td><td>{@code NEW}</td><td>{@code ASSIGNED}</td>
 *       <td>处理人必填</td></tr>
 *   <tr><td>{@link #reassign(UserId, Instant)}</td><td>{@code ASSIGNED}、{@code IN_PROGRESS}</td>
 *       <td>状态不变</td><td>新处理人必须与当前处理人不同</td></tr>
 *   <tr><td>{@link #start(Instant)}</td><td>{@code ASSIGNED}</td><td>{@code IN_PROGRESS}</td>
 *       <td>—</td></tr>
 *   <tr><td>{@link #resolve(String, Instant)}</td><td>{@code IN_PROGRESS}</td><td>{@code RESOLVED}</td>
 *       <td>处理结论必填；同时写入 {@code resolvedAt}</td></tr>
 *   <tr><td>{@link #close(Instant)}</td><td>{@code RESOLVED}</td><td>{@code CLOSED}</td>
 *       <td>同时写入 {@code closedAt}</td></tr>
 * </table>
 *
 * <p>其余任何组合都抛出 {@link TicketErrorCode#ILLEGAL_STATUS_TRANSITION}。
 * 状态字段没有公共 setter，只能经上述方法流转。</p>
 *
 * <h2>聚合不变量</h2>
 * <ul>
 *   <li>时间线为单链：{@code createdAt <= resolvedAt <= closedAt <= updatedAt}，
 *       其中 {@code resolvedAt} 与 {@code closedAt} 不存在时直接跳过对应比较；</li>
 *   <li>状态与可选字段共存：{@code NEW} 无处理人/结论/时间戳；{@code ASSIGNED}、{@code IN_PROGRESS}
 *       有处理人、无结论与时间戳；{@code RESOLVED} 有处理人与结论及 {@code resolvedAt}、无 {@code closedAt}；
 *       {@code CLOSED} 全部齐备。</li>
 * </ul>
 *
 * <h2>实体语义</h2>
 * <p>相等性只由 {@link TicketId} 决定：两个不同工单即使字段完全相同也不相等。
 * {@code toString} 刻意只输出标识、状态与时间线，不包含标题、描述与处理结论。</p>
 */
public class Ticket {

    /** 标题最大长度（strip 之后）。 */
    public static final int MAX_TITLE_LENGTH = 200;

    /** 描述最大长度（strip 之后）。 */
    public static final int MAX_DESCRIPTION_LENGTH = 4000;

    /** 处理结论最大长度（strip 之后）。 */
    public static final int MAX_RESOLUTION_LENGTH = 2000;

    private final TicketId id;

    private final String title;

    private final String description;

    private final TicketCategory category;

    private final TicketPriority priority;

    private final UserId requesterId;

    private final Instant createdAt;

    private UserId assigneeId;

    private TicketStatus status;

    private String resolution;

    private Instant updatedAt;

    private Instant resolvedAt;

    private Instant closedAt;

    private Ticket(TicketId id, String title, String description, TicketCategory category,
            TicketPriority priority, UserId requesterId, Instant createdAt) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.category = category;
        this.priority = priority;
        this.requesterId = requesterId;
        this.createdAt = createdAt;
    }

    /**
     * 创建一个新工单。
     *
     * <p>创建后保证：状态为 {@code NEW}，处理人、处理结论、{@code resolvedAt}、{@code closedAt} 均为空，
     * 且 {@code updatedAt} 等于 {@code createdAt}。</p>
     *
     * @param id          工单标识，必填
     * @param title       标题，strip 后 1..200 字符
     * @param description 描述，strip 后 1..4000 字符
     * @param category    分类，必填
     * @param priority    优先级，必填
     * @param requesterId 请求人，必填
     * @param createdAt   创建时间，必填
     * @return 处于 {@code NEW} 状态的新工单
     * @throws TicketDomainException 参数不合法时抛出对应的稳定错误码
     */
    public static Ticket create(TicketId id, String title, String description, TicketCategory category,
            TicketPriority priority, UserId requesterId, Instant createdAt) {

        TicketId validId = requireField(id, TicketErrorCode.INVALID_TICKET_ID, "工单标识");
        String validTitle = requireText(title, MAX_TITLE_LENGTH, TicketErrorCode.INVALID_TITLE, "标题");
        String validDescription =
                requireText(description, MAX_DESCRIPTION_LENGTH, TicketErrorCode.INVALID_DESCRIPTION, "描述");
        TicketCategory validCategory = requireField(category, TicketErrorCode.INVALID_CATEGORY, "工单分类");
        TicketPriority validPriority = requireField(priority, TicketErrorCode.INVALID_PRIORITY, "工单优先级");
        UserId validRequester = requireField(requesterId, TicketErrorCode.INVALID_USER_ID, "请求人标识");
        Instant validCreatedAt = requireField(createdAt, TicketErrorCode.INVALID_TIMESTAMP, "创建时间");

        Ticket ticket = new Ticket(validId, validTitle, validDescription, validCategory, validPriority,
                validRequester, validCreatedAt);
        ticket.status = TicketStatus.NEW;
        ticket.updatedAt = validCreatedAt;
        return ticket;
    }

    /**
     * 从持久化快照恢复工单，供数据库适配器使用。
     *
     * <p>恢复时完整校验：字段必填与字符串长度规则（与创建一致，不会被绕过）、状态与可选字段的共存关系、
     * 以及完整时间线 {@code createdAt <= resolvedAt <= closedAt <= updatedAt}（不存在的字段跳过）。
     * 任何不自洽的快照都抛出
     * {@link TicketErrorCode#INVALID_RESTORED_STATE}；字符串字段自身违规仍使用字段级错误码。</p>
     *
     * @param id          工单标识
     * @param title       标题
     * @param description 描述
     * @param category    分类
     * @param priority    优先级
     * @param requesterId 请求人
     * @param assigneeId  处理人，可为空
     * @param status      状态
     * @param resolution  处理结论，可为空
     * @param createdAt   创建时间
     * @param updatedAt   最近更新时间
     * @param resolvedAt  解决时间，可为空
     * @param closedAt    关闭时间，可为空
     * @return 恢复后的工单
     * @throws TicketDomainException 快照不自洽或字段非法时抛出
     */
    public static Ticket restore(TicketId id, String title, String description, TicketCategory category,
            TicketPriority priority, UserId requesterId, UserId assigneeId, TicketStatus status, String resolution,
            Instant createdAt, Instant updatedAt, Instant resolvedAt, Instant closedAt) {

        TicketId validId = requireRestoredField(id, "工单标识");
        TicketCategory validCategory = requireRestoredField(category, "工单分类");
        TicketPriority validPriority = requireRestoredField(priority, "工单优先级");
        UserId validRequester = requireRestoredField(requesterId, "请求人标识");
        TicketStatus validStatus = requireRestoredField(status, "工单状态");
        Instant validCreatedAt = requireRestoredField(createdAt, "创建时间");
        Instant validUpdatedAt = requireRestoredField(updatedAt, "更新时间");

        String validTitle = requireText(title, MAX_TITLE_LENGTH, TicketErrorCode.INVALID_TITLE, "标题");
        String validDescription =
                requireText(description, MAX_DESCRIPTION_LENGTH, TicketErrorCode.INVALID_DESCRIPTION, "描述");
        String validResolution = resolution == null
                ? null
                : requireText(resolution, MAX_RESOLUTION_LENGTH, TicketErrorCode.INVALID_RESOLUTION, "处理结论");

        requireTimeline(validCreatedAt, validUpdatedAt, resolvedAt, closedAt);
        requireStatusConsistency(validStatus, assigneeId, validResolution, resolvedAt, closedAt);

        Ticket ticket = new Ticket(validId, validTitle, validDescription, validCategory, validPriority,
                validRequester, validCreatedAt);
        ticket.assigneeId = assigneeId;
        ticket.status = validStatus;
        ticket.resolution = validResolution;
        ticket.updatedAt = validUpdatedAt;
        ticket.resolvedAt = resolvedAt;
        ticket.closedAt = closedAt;
        return ticket;
    }

    /**
     * 分配处理人：{@code NEW → ASSIGNED}。
     *
     * @param newAssigneeId 处理人，必填
     * @param occurredAt    发生时间，不能早于当前 {@code updatedAt}
     */
    public void assign(UserId newAssigneeId, Instant occurredAt) {
        requireStatus(TicketStatus.NEW);
        Instant occurred = requireOccurredAt(occurredAt);
        UserId validAssignee = requireField(newAssigneeId, TicketErrorCode.INVALID_USER_ID, "处理人标识");

        this.assigneeId = validAssignee;
        this.status = TicketStatus.ASSIGNED;
        this.updatedAt = occurred;
    }

    /**
     * 重新分配处理人：{@code ASSIGNED → ASSIGNED}、{@code IN_PROGRESS → IN_PROGRESS}。
     *
     * @param newAssigneeId 新处理人，必填且必须与当前处理人不同
     * @param occurredAt    发生时间，不能早于当前 {@code updatedAt}
     */
    public void reassign(UserId newAssigneeId, Instant occurredAt) {
        requireStatus(TicketStatus.ASSIGNED, TicketStatus.IN_PROGRESS);
        Instant occurred = requireOccurredAt(occurredAt);
        UserId validAssignee = requireField(newAssigneeId, TicketErrorCode.INVALID_USER_ID, "处理人标识");
        if (validAssignee.equals(this.assigneeId)) {
            throw new TicketDomainException(TicketErrorCode.SAME_ASSIGNEE, "新处理人与当前处理人相同");
        }

        this.assigneeId = validAssignee;
        this.updatedAt = occurred;
    }

    /**
     * 开始处理：{@code ASSIGNED → IN_PROGRESS}。
     *
     * @param occurredAt 发生时间，不能早于当前 {@code updatedAt}
     */
    public void start(Instant occurredAt) {
        requireStatus(TicketStatus.ASSIGNED);
        Instant occurred = requireOccurredAt(occurredAt);

        this.status = TicketStatus.IN_PROGRESS;
        this.updatedAt = occurred;
    }

    /**
     * 提交处理结论：{@code IN_PROGRESS → RESOLVED}。
     *
     * @param newResolution 处理结论，strip 后 1..2000 字符
     * @param occurredAt    发生时间，不能早于当前 {@code updatedAt}；同时写入 {@code resolvedAt}
     */
    public void resolve(String newResolution, Instant occurredAt) {
        requireStatus(TicketStatus.IN_PROGRESS);
        Instant occurred = requireOccurredAt(occurredAt);
        String validResolution = requireText(newResolution, MAX_RESOLUTION_LENGTH,
                TicketErrorCode.INVALID_RESOLUTION, "处理结论");

        this.resolution = validResolution;
        this.resolvedAt = occurred;
        this.updatedAt = occurred;
        this.status = TicketStatus.RESOLVED;
    }

    /**
     * 关闭工单：{@code RESOLVED → CLOSED}。
     *
     * @param occurredAt 发生时间，不能早于当前 {@code updatedAt}；同时写入 {@code closedAt}
     */
    public void close(Instant occurredAt) {
        requireStatus(TicketStatus.RESOLVED);
        Instant occurred = requireOccurredAt(occurredAt);

        this.closedAt = occurred;
        this.updatedAt = occurred;
        this.status = TicketStatus.CLOSED;
    }

    /**
     * @return 工单标识
     */
    public TicketId id() {
        return this.id;
    }

    /**
     * @return 标题
     */
    public String title() {
        return this.title;
    }

    /**
     * @return 描述
     */
    public String description() {
        return this.description;
    }

    /**
     * @return 分类
     */
    public TicketCategory category() {
        return this.category;
    }

    /**
     * @return 优先级
     */
    public TicketPriority priority() {
        return this.priority;
    }

    /**
     * @return 请求人
     */
    public UserId requesterId() {
        return this.requesterId;
    }

    /**
     * @return 处理人；未分配时为空
     */
    public Optional<UserId> assigneeId() {
        return Optional.ofNullable(this.assigneeId);
    }

    /**
     * @return 当前状态
     */
    public TicketStatus status() {
        return this.status;
    }

    /**
     * @return 处理结论；未提交时为空
     */
    public Optional<String> resolution() {
        return Optional.ofNullable(this.resolution);
    }

    /**
     * @return 创建时间
     */
    public Instant createdAt() {
        return this.createdAt;
    }

    /**
     * @return 最近更新时间
     */
    public Instant updatedAt() {
        return this.updatedAt;
    }

    /**
     * @return 解决时间；未解决时为空
     */
    public Optional<Instant> resolvedAt() {
        return Optional.ofNullable(this.resolvedAt);
    }

    /**
     * @return 关闭时间；未关闭时为空
     */
    public Optional<Instant> closedAt() {
        return Optional.ofNullable(this.closedAt);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof Ticket ticket && this.id.equals(ticket.id);
    }

    @Override
    public int hashCode() {
        return this.id.hashCode();
    }

    @Override
    public String toString() {
        return "Ticket{id=" + this.id
                + ", status=" + this.status
                + ", priority=" + this.priority
                + ", category=" + this.category
                + ", requesterId=" + this.requesterId
                + ", assigneeId=" + this.assigneeId
                + ", createdAt=" + this.createdAt
                + ", updatedAt=" + this.updatedAt
                + ", resolvedAt=" + this.resolvedAt
                + ", closedAt=" + this.closedAt
                + '}';
    }

    // ---------- 校验辅助 ----------

    private static <T> T requireField(T value, TicketErrorCode errorCode, String label) {
        if (value == null) {
            throw new TicketDomainException(errorCode, label + "不能为空");
        }
        return value;
    }

    private static <T> T requireRestoredField(T value, String label) {
        if (value == null) {
            throw new TicketDomainException(TicketErrorCode.INVALID_RESTORED_STATE, label + "不能为空");
        }
        return value;
    }

    /**
     * 文本字段校验：strip 后判空、判长度。异常信息只包含字段名与上限，不回显原始输入。
     */
    private static String requireText(String raw, int maxLength, TicketErrorCode errorCode, String label) {
        if (raw == null) {
            throw new TicketDomainException(errorCode, label + "不能为空");
        }
        String normalized = raw.strip();
        if (normalized.isEmpty()) {
            throw new TicketDomainException(errorCode, label + "不能为空");
        }
        if (normalized.length() > maxLength) {
            throw new TicketDomainException(errorCode, label + "长度不能超过 " + maxLength + " 个字符");
        }
        return normalized;
    }

    private void requireStatus(TicketStatus... allowedStatuses) {
        for (TicketStatus allowed : allowedStatuses) {
            if (this.status == allowed) {
                return;
            }
        }
        throw new TicketDomainException(TicketErrorCode.ILLEGAL_STATUS_TRANSITION,
                "当前状态 " + this.status + " 不允许该操作");
    }

    private Instant requireOccurredAt(Instant occurredAt) {
        if (occurredAt == null) {
            throw new TicketDomainException(TicketErrorCode.INVALID_TIMESTAMP, "操作时间不能为空");
        }
        if (occurredAt.isBefore(this.updatedAt)) {
            throw new TicketDomainException(TicketErrorCode.INVALID_TIMESTAMP,
                    "操作时间不能早于工单的最近更新时间");
        }
        return occurredAt;
    }

    /**
     * 校验时间线：非空时间必须满足
     * {@code createdAt <= resolvedAt <= closedAt <= updatedAt}，不存在的字段直接跳过。
     *
     * <p>{@code resolvedAt <= updatedAt} 与 {@code closedAt <= updatedAt} 这两条不能省：
     * 否则一个 {@code resolvedAt} 晚于 {@code updatedAt} 的 {@code RESOLVED} 快照可以被恢复，
     * 随后调用 {@code close(updatedAt)} 就会把 {@code closedAt} 写到 {@code resolvedAt} 之前，
     * 破坏 {@code closedAt >= resolvedAt}。</p>
     */
    private static void requireTimeline(Instant createdAt, Instant updatedAt, Instant resolvedAt, Instant closedAt) {
        if (createdAt.isAfter(updatedAt)) {
            throw new TicketDomainException(TicketErrorCode.INVALID_RESTORED_STATE,
                    "创建时间不能晚于最近更新时间");
        }
        if (resolvedAt != null) {
            if (resolvedAt.isBefore(createdAt)) {
                throw new TicketDomainException(TicketErrorCode.INVALID_RESTORED_STATE,
                        "解决时间不能早于创建时间");
            }
            if (resolvedAt.isAfter(updatedAt)) {
                throw new TicketDomainException(TicketErrorCode.INVALID_RESTORED_STATE,
                        "解决时间不能晚于最近更新时间");
            }
        }
        if (closedAt != null) {
            if (resolvedAt == null || closedAt.isBefore(resolvedAt)) {
                throw new TicketDomainException(TicketErrorCode.INVALID_RESTORED_STATE,
                        "关闭时间不能早于解决时间");
            }
            if (closedAt.isAfter(updatedAt)) {
                throw new TicketDomainException(TicketErrorCode.INVALID_RESTORED_STATE,
                        "关闭时间不能晚于最近更新时间");
            }
        }
    }

    private static void requireStatusConsistency(TicketStatus status, UserId assigneeId, String resolution,
            Instant resolvedAt, Instant closedAt) {

        switch (status) {
            case NEW -> {
                requireAbsentForState(assigneeId, "处理人");
                requireAbsentForState(resolution, "处理结论");
                requireAbsentForState(resolvedAt, "解决时间");
                requireAbsentForState(closedAt, "关闭时间");
            }
            case ASSIGNED, IN_PROGRESS -> {
                requirePresentForState(assigneeId, "处理人");
                requireAbsentForState(resolution, "处理结论");
                requireAbsentForState(resolvedAt, "解决时间");
                requireAbsentForState(closedAt, "关闭时间");
            }
            case RESOLVED -> {
                requirePresentForState(assigneeId, "处理人");
                requirePresentForState(resolution, "处理结论");
                requirePresentForState(resolvedAt, "解决时间");
                requireAbsentForState(closedAt, "关闭时间");
            }
            case CLOSED -> {
                requirePresentForState(assigneeId, "处理人");
                requirePresentForState(resolution, "处理结论");
                requirePresentForState(resolvedAt, "解决时间");
                requirePresentForState(closedAt, "关闭时间");
            }
        }
    }

    private static void requirePresentForState(Object value, String label) {
        if (value == null) {
            throw new TicketDomainException(TicketErrorCode.INVALID_RESTORED_STATE,
                    label + "在该状态下不能为空");
        }
    }

    private static void requireAbsentForState(Object value, String label) {
        if (value != null) {
            throw new TicketDomainException(TicketErrorCode.INVALID_RESTORED_STATE,
                    label + "在该状态下必须为空");
        }
    }
}
