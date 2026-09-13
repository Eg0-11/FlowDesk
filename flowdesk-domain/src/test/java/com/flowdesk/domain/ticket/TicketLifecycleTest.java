package com.flowdesk.domain.ticket;

import static com.flowdesk.domain.ticket.TicketTestSupport.ALICE;
import static com.flowdesk.domain.ticket.TicketTestSupport.BASE;
import static com.flowdesk.domain.ticket.TicketTestSupport.BOB;
import static com.flowdesk.domain.ticket.TicketTestSupport.RESOLUTION;
import static com.flowdesk.domain.ticket.TicketTestSupport.assertErrorCode;
import static com.flowdesk.domain.ticket.TicketTestSupport.captureDomainException;
import static com.flowdesk.domain.ticket.TicketTestSupport.newTicket;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 工单生命周期状态机测试：合法流程、重新分配、非法转换矩阵、时间规则与状态封装。
 */
class TicketLifecycleTest {

    private static final List<String> OPERATIONS = List.of("assign", "reassign", "start", "resolve", "close");

    /** 每个状态允许的操作，其余组合都必须抛 {@code ILLEGAL_STATUS_TRANSITION}。 */
    private static final Map<TicketStatus, Set<String>> ALLOWED_OPERATIONS = Map.of(
            TicketStatus.NEW, Set.of("assign"),
            TicketStatus.ASSIGNED, Set.of("reassign", "start"),
            TicketStatus.IN_PROGRESS, Set.of("reassign", "resolve"),
            TicketStatus.RESOLVED, Set.of("close"),
            TicketStatus.CLOSED, Set.of());

    @Test
    void completesTheFullLifecycle() {
        Ticket ticket = newTicket();
        assertThat(ticket.status()).isEqualTo(TicketStatus.NEW);

        ticket.assign(ALICE, BASE.plusSeconds(60));
        assertThat(ticket.status()).isEqualTo(TicketStatus.ASSIGNED);
        assertThat(ticket.assigneeId()).contains(ALICE);
        assertThat(ticket.updatedAt()).isEqualTo(BASE.plusSeconds(60));

        ticket.start(BASE.plusSeconds(120));
        assertThat(ticket.status()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(ticket.assigneeId()).contains(ALICE);
        assertThat(ticket.resolution()).isEmpty();
        assertThat(ticket.resolvedAt()).isEmpty();

        ticket.resolve("  重置账号密码并通知用户  ", BASE.plusSeconds(180));
        assertThat(ticket.status()).isEqualTo(TicketStatus.RESOLVED);
        assertThat(ticket.resolution()).contains("重置账号密码并通知用户");
        assertThat(ticket.resolvedAt()).contains(BASE.plusSeconds(180));
        assertThat(ticket.updatedAt()).isEqualTo(BASE.plusSeconds(180));
        assertThat(ticket.closedAt()).isEmpty();

        ticket.close(BASE.plusSeconds(240));
        assertThat(ticket.status()).isEqualTo(TicketStatus.CLOSED);
        assertThat(ticket.closedAt()).contains(BASE.plusSeconds(240));
        assertThat(ticket.updatedAt()).isEqualTo(BASE.plusSeconds(240));
    }

    @Test
    void keepsTimelineInvariantsAfterTheFullLifecycle() {
        Ticket ticket = newTicket();
        ticket.assign(ALICE, BASE.plusSeconds(60));
        ticket.start(BASE.plusSeconds(120));
        ticket.resolve(RESOLUTION, BASE.plusSeconds(180));
        ticket.close(BASE.plusSeconds(240));

        assertThat(ticket.updatedAt()).isAfterOrEqualTo(ticket.createdAt());
        assertThat(ticket.resolvedAt().orElseThrow()).isAfterOrEqualTo(ticket.createdAt());
        assertThat(ticket.closedAt().orElseThrow()).isAfterOrEqualTo(ticket.resolvedAt().orElseThrow());
    }

    @Test
    void allowsReassignWhileAssigned() {
        Ticket ticket = newTicket();
        ticket.assign(ALICE, BASE.plusSeconds(60));

        ticket.reassign(BOB, BASE.plusSeconds(120));

        assertThat(ticket.status()).isEqualTo(TicketStatus.ASSIGNED);
        assertThat(ticket.assigneeId()).contains(BOB);
        assertThat(ticket.updatedAt()).isEqualTo(BASE.plusSeconds(120));
    }

    @Test
    void allowsReassignWhileInProgress() {
        Ticket ticket = newTicket();
        ticket.assign(ALICE, BASE.plusSeconds(60));
        ticket.start(BASE.plusSeconds(120));

        ticket.reassign(BOB, BASE.plusSeconds(180));

        assertThat(ticket.status()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(ticket.assigneeId()).contains(BOB);
        assertThat(ticket.resolution()).isEmpty();
    }

    @Test
    void rejectsReassignToTheSameAssignee() {
        Ticket assigned = ticketWithAssignee();
        assertErrorCode(() -> assigned.reassign(ALICE, BASE.plusSeconds(120)), TicketErrorCode.SAME_ASSIGNEE);

        Ticket inProgress = ticketWithAssignee();
        inProgress.start(BASE.plusSeconds(90));
        assertErrorCode(() -> inProgress.reassign(ALICE, BASE.plusSeconds(120)), TicketErrorCode.SAME_ASSIGNEE);
    }

    @Test
    void rejectsEveryIllegalStatusTransition() {
        for (TicketStatus status : TicketStatus.values()) {
            for (String operation : OPERATIONS) {
                Ticket ticket = TicketTestSupport.ticketInStatus(status);
                Instant occurredAt = BASE.plusSeconds(60);

                if (ALLOWED_OPERATIONS.get(status).contains(operation)) {
                    apply(ticket, operation, occurredAt);
                } else {
                    assertErrorCode(() -> apply(ticket, operation, occurredAt),
                            TicketErrorCode.ILLEGAL_STATUS_TRANSITION);
                }
            }
        }
    }

    @Test
    void allowsRepeatedOperationsWhenTheStateAllowsThem() {
        Ticket ticket = ticketWithAssignee();

        ticket.reassign(BOB, BASE.plusSeconds(60));
        ticket.reassign(ALICE, BASE.plusSeconds(120));

        assertThat(ticket.status()).isEqualTo(TicketStatus.ASSIGNED);
        assertThat(ticket.assigneeId()).contains(ALICE);
    }

    @Test
    void rejectsBlankResolution() {
        Ticket ticket = inProgressTicket();

        assertErrorCode(() -> ticket.resolve("   ", BASE.plusSeconds(180)), TicketErrorCode.INVALID_RESOLUTION);
        assertErrorCode(() -> ticket.resolve("\u3000", BASE.plusSeconds(180)), TicketErrorCode.INVALID_RESOLUTION);
        assertErrorCode(() -> ticket.resolve(null, BASE.plusSeconds(180)), TicketErrorCode.INVALID_RESOLUTION);
    }

    @Test
    void acceptsMaxLengthResolution() {
        Ticket ticket = inProgressTicket();
        String resolution = "r".repeat(Ticket.MAX_RESOLUTION_LENGTH);

        ticket.resolve(resolution, BASE.plusSeconds(180));

        assertThat(ticket.resolution()).contains(resolution);
    }

    @Test
    void acceptsMaxLengthResolutionWithSurroundingWhitespace() {
        Ticket ticket = inProgressTicket();
        String resolution = "r".repeat(Ticket.MAX_RESOLUTION_LENGTH);

        ticket.resolve("  " + resolution + "  ", BASE.plusSeconds(180));

        assertThat(ticket.resolution()).contains(resolution);
    }

    @Test
    void rejectsResolutionOneCharacterBeyondMaxLength() {
        Ticket ticket = inProgressTicket();

        assertErrorCode(() -> ticket.resolve("r".repeat(Ticket.MAX_RESOLUTION_LENGTH + 1), BASE.plusSeconds(180)),
                TicketErrorCode.INVALID_RESOLUTION);
    }

    @Test
    void doesNotEchoInvalidResolution() {
        Ticket ticket = inProgressTicket();
        String sentinel = "SENTINELRESOLUTION" + "r".repeat(Ticket.MAX_RESOLUTION_LENGTH);

        TicketDomainException ex = captureDomainException(() -> ticket.resolve(sentinel, BASE.plusSeconds(180)));

        assertThat(ex.getMessage()).doesNotContain("SENTINELRESOLUTION");
    }

    @Test
    void rejectsNullAssigneeOnAssign() {
        Ticket ticket = newTicket();

        assertErrorCode(() -> ticket.assign(null, BASE.plusSeconds(60)), TicketErrorCode.INVALID_USER_ID);
    }

    @Test
    void acceptsAnOccurredAtEqualToUpdatedAt() {
        Ticket ticket = newTicket();

        ticket.assign(ALICE, BASE);

        assertThat(ticket.updatedAt()).isEqualTo(BASE);
        assertThat(ticket.status()).isEqualTo(TicketStatus.ASSIGNED);
    }

    @Test
    void rejectsAnOccurredAtBeforeUpdatedAt() {
        Ticket ticket = newTicket();
        ticket.assign(ALICE, BASE.plusSeconds(60));

        assertErrorCode(() -> ticket.start(BASE.plusSeconds(59)), TicketErrorCode.INVALID_TIMESTAMP);
        assertErrorCode(() -> ticket.start(BASE.minusSeconds(1)), TicketErrorCode.INVALID_TIMESTAMP);
    }

    @Test
    void rejectsNullOccurredAt() {
        Ticket ticket = newTicket();

        assertErrorCode(() -> ticket.assign(ALICE, null), TicketErrorCode.INVALID_TIMESTAMP);

        Ticket assigned = ticketWithAssignee();
        assertErrorCode(() -> assigned.start(null), TicketErrorCode.INVALID_TIMESTAMP);
    }

    @Test
    void doesNotChangeStateWhenATransitionIsRejected() {
        Ticket ticket = ticketWithAssignee();

        assertErrorCode(() -> ticket.start(BASE.minusSeconds(1)), TicketErrorCode.INVALID_TIMESTAMP);

        assertThat(ticket.status()).isEqualTo(TicketStatus.ASSIGNED);
        assertThat(ticket.assigneeId()).contains(ALICE);
        assertThat(ticket.updatedAt()).isEqualTo(BASE.plusSeconds(30));
    }

    @Test
    void exposesNoWayToSetStatusOrAssigneeDirectly() {
        assertThat(Ticket.class.getMethods())
                .as("不得存在任意修改状态的公共方法")
                .noneMatch(method -> method.getName().equals("setStatus"))
                .noneMatch(method -> method.getParameterCount() == 1
                        && method.getParameterTypes()[0] == TicketStatus.class)
                .noneMatch(method -> method.getName().startsWith("set"));

        assertThat(Ticket.class.getFields())
                .as("不得存在公共实例字段（只允许公开常量）")
                .allMatch(field -> Modifier.isStatic(field.getModifiers()));
    }

    @Test
    void keepsAllInstanceFieldsPrivate() {
        for (Field field : Ticket.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            assertThat(Modifier.isPrivate(field.getModifiers()))
                    .as("实例字段 %s 必须是 private", field.getName())
                    .isTrue();
        }
    }

    private static Ticket ticketWithAssignee() {
        Ticket ticket = newTicket();
        ticket.assign(ALICE, BASE.plusSeconds(30));
        return ticket;
    }

    private static Ticket inProgressTicket() {
        Ticket ticket = ticketWithAssignee();
        ticket.start(BASE.plusSeconds(60));
        return ticket;
    }

    private static void apply(Ticket ticket, String operation, Instant occurredAt) {
        switch (operation) {
            case "assign" -> ticket.assign(ALICE, occurredAt);
            case "reassign" -> ticket.reassign(ALICE, occurredAt);
            case "start" -> ticket.start(occurredAt);
            case "resolve" -> ticket.resolve(RESOLUTION, occurredAt);
            case "close" -> ticket.close(occurredAt);
            default -> throw new IllegalArgumentException("未知操作: " + operation);
        }
    }
}
