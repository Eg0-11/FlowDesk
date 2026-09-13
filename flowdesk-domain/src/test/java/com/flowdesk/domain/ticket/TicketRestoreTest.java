package com.flowdesk.domain.ticket;

import static com.flowdesk.domain.ticket.TicketTestSupport.ALICE;
import static com.flowdesk.domain.ticket.TicketTestSupport.BASE;
import static com.flowdesk.domain.ticket.TicketTestSupport.BOB;
import static com.flowdesk.domain.ticket.TicketTestSupport.CATEGORY;
import static com.flowdesk.domain.ticket.TicketTestSupport.DESCRIPTION;
import static com.flowdesk.domain.ticket.TicketTestSupport.PRIORITY;
import static com.flowdesk.domain.ticket.TicketTestSupport.RESOLUTION;
import static com.flowdesk.domain.ticket.TicketTestSupport.TICKET_ID;
import static com.flowdesk.domain.ticket.TicketTestSupport.TITLE;
import static com.flowdesk.domain.ticket.TicketTestSupport.assertErrorCode;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * 恢复快照的状态一致性测试。
 */
class TicketRestoreTest {

    @Test
    void restoresNewTicket() {
        Ticket ticket = snapshot(TicketStatus.NEW).restore();

        assertThat(ticket.status()).isEqualTo(TicketStatus.NEW);
        assertThat(ticket.assigneeId()).isEmpty();
        assertThat(ticket.resolution()).isEmpty();
        assertThat(ticket.resolvedAt()).isEmpty();
        assertThat(ticket.closedAt()).isEmpty();
        assertThat(ticket.createdAt()).isEqualTo(BASE);
        assertThat(ticket.updatedAt()).isEqualTo(BASE);
    }

    @Test
    void restoresAssignedTicket() {
        Ticket ticket = snapshot(TicketStatus.ASSIGNED).restore();

        assertThat(ticket.status()).isEqualTo(TicketStatus.ASSIGNED);
        assertThat(ticket.assigneeId()).contains(BOB);
        assertThat(ticket.resolution()).isEmpty();
        assertThat(ticket.resolvedAt()).isEmpty();
        assertThat(ticket.closedAt()).isEmpty();
    }

    @Test
    void restoresInProgressTicket() {
        Ticket ticket = snapshot(TicketStatus.IN_PROGRESS).restore();

        assertThat(ticket.status()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(ticket.assigneeId()).contains(BOB);
        assertThat(ticket.resolution()).isEmpty();
    }

    @Test
    void restoresResolvedTicket() {
        Ticket ticket = snapshot(TicketStatus.RESOLVED).restore();

        assertThat(ticket.status()).isEqualTo(TicketStatus.RESOLVED);
        assertThat(ticket.assigneeId()).contains(BOB);
        assertThat(ticket.resolution()).contains(RESOLUTION);
        assertThat(ticket.resolvedAt()).contains(BASE.plusSeconds(10));
        assertThat(ticket.closedAt()).isEmpty();
    }

    @Test
    void restoresClosedTicket() {
        Ticket ticket = snapshot(TicketStatus.CLOSED).restore();

        assertThat(ticket.status()).isEqualTo(TicketStatus.CLOSED);
        assertThat(ticket.assigneeId()).contains(BOB);
        assertThat(ticket.resolution()).contains(RESOLUTION);
        assertThat(ticket.resolvedAt()).contains(BASE.plusSeconds(10));
        assertThat(ticket.closedAt()).contains(BASE.plusSeconds(20));
    }

    @Test
    void restoredTicketContinuesTheLifecycle() {
        Ticket ticket = snapshot(TicketStatus.RESOLVED).restore();

        ticket.close(BASE.plusSeconds(30));

        assertThat(ticket.status()).isEqualTo(TicketStatus.CLOSED);
        assertThat(ticket.closedAt()).contains(BASE.plusSeconds(30));
    }

    // ---------- NEW 状态的不一致 ----------

    @Test
    void rejectsNewTicketWithAssignee() {
        Snapshot snapshot = snapshot(TicketStatus.NEW);
        snapshot.assigneeId = BOB;

        assertErrorCode(snapshot::restore, TicketErrorCode.INVALID_RESTORED_STATE);
    }

    @Test
    void rejectsNewTicketWithResolution() {
        Snapshot snapshot = snapshot(TicketStatus.NEW);
        snapshot.resolution = RESOLUTION;

        assertErrorCode(snapshot::restore, TicketErrorCode.INVALID_RESTORED_STATE);
    }

    @Test
    void rejectsNewTicketWithResolvedAt() {
        Snapshot snapshot = snapshot(TicketStatus.NEW);
        snapshot.resolvedAt = BASE.plusSeconds(10);

        assertErrorCode(snapshot::restore, TicketErrorCode.INVALID_RESTORED_STATE);
    }

    @Test
    void rejectsNewTicketWithClosedAt() {
        Snapshot snapshot = snapshot(TicketStatus.NEW);
        snapshot.closedAt = BASE.plusSeconds(10);

        assertErrorCode(snapshot::restore, TicketErrorCode.INVALID_RESTORED_STATE);
    }

    // ---------- ASSIGNED / IN_PROGRESS 状态的不一致 ----------

    @Test
    void rejectsAssignedTicketWithoutAssignee() {
        Snapshot snapshot = snapshot(TicketStatus.ASSIGNED);
        snapshot.assigneeId = null;

        assertErrorCode(snapshot::restore, TicketErrorCode.INVALID_RESTORED_STATE);
    }

    @Test
    void rejectsAssignedTicketWithResolutionOrTimestamps() {
        Snapshot withResolution = snapshot(TicketStatus.ASSIGNED);
        withResolution.resolution = RESOLUTION;
        assertErrorCode(withResolution::restore, TicketErrorCode.INVALID_RESTORED_STATE);

        Snapshot withResolvedAt = snapshot(TicketStatus.ASSIGNED);
        withResolvedAt.resolvedAt = BASE.plusSeconds(10);
        assertErrorCode(withResolvedAt::restore, TicketErrorCode.INVALID_RESTORED_STATE);

        Snapshot withClosedAt = snapshot(TicketStatus.ASSIGNED);
        withClosedAt.closedAt = BASE.plusSeconds(10);
        assertErrorCode(withClosedAt::restore, TicketErrorCode.INVALID_RESTORED_STATE);
    }

    @Test
    void rejectsInProgressTicketWithoutAssignee() {
        Snapshot snapshot = snapshot(TicketStatus.IN_PROGRESS);
        snapshot.assigneeId = null;

        assertErrorCode(snapshot::restore, TicketErrorCode.INVALID_RESTORED_STATE);
    }

    @Test
    void rejectsInProgressTicketWithResolutionOrTimestamps() {
        Snapshot withResolution = snapshot(TicketStatus.IN_PROGRESS);
        withResolution.resolution = RESOLUTION;
        assertErrorCode(withResolution::restore, TicketErrorCode.INVALID_RESTORED_STATE);

        Snapshot withResolvedAt = snapshot(TicketStatus.IN_PROGRESS);
        withResolvedAt.resolvedAt = BASE.plusSeconds(10);
        assertErrorCode(withResolvedAt::restore, TicketErrorCode.INVALID_RESTORED_STATE);

        Snapshot withClosedAt = snapshot(TicketStatus.IN_PROGRESS);
        withClosedAt.closedAt = BASE.plusSeconds(10);
        assertErrorCode(withClosedAt::restore, TicketErrorCode.INVALID_RESTORED_STATE);
    }

    // ---------- RESOLVED 状态的不一致 ----------

    @Test
    void rejectsResolvedTicketWithoutAssignee() {
        Snapshot snapshot = snapshot(TicketStatus.RESOLVED);
        snapshot.assigneeId = null;

        assertErrorCode(snapshot::restore, TicketErrorCode.INVALID_RESTORED_STATE);
    }

    @Test
    void rejectsResolvedTicketWithoutResolution() {
        Snapshot snapshot = snapshot(TicketStatus.RESOLVED);
        snapshot.resolution = null;

        assertErrorCode(snapshot::restore, TicketErrorCode.INVALID_RESTORED_STATE);
    }

    @Test
    void rejectsResolvedTicketWithoutResolvedAt() {
        Snapshot snapshot = snapshot(TicketStatus.RESOLVED);
        snapshot.resolvedAt = null;

        assertErrorCode(snapshot::restore, TicketErrorCode.INVALID_RESTORED_STATE);
    }

    @Test
    void rejectsResolvedTicketWithClosedAt() {
        Snapshot snapshot = snapshot(TicketStatus.RESOLVED);
        snapshot.closedAt = BASE.plusSeconds(20);

        assertErrorCode(snapshot::restore, TicketErrorCode.INVALID_RESTORED_STATE);
    }

    // ---------- CLOSED 状态的不一致 ----------

    @Test
    void rejectsClosedTicketMissingAnyRequiredField() {
        Snapshot withoutAssignee = snapshot(TicketStatus.CLOSED);
        withoutAssignee.assigneeId = null;
        assertErrorCode(withoutAssignee::restore, TicketErrorCode.INVALID_RESTORED_STATE);

        Snapshot withoutResolution = snapshot(TicketStatus.CLOSED);
        withoutResolution.resolution = null;
        assertErrorCode(withoutResolution::restore, TicketErrorCode.INVALID_RESTORED_STATE);

        Snapshot withoutResolvedAt = snapshot(TicketStatus.CLOSED);
        withoutResolvedAt.resolvedAt = null;
        assertErrorCode(withoutResolvedAt::restore, TicketErrorCode.INVALID_RESTORED_STATE);

        Snapshot withoutClosedAt = snapshot(TicketStatus.CLOSED);
        withoutClosedAt.closedAt = null;
        assertErrorCode(withoutClosedAt::restore, TicketErrorCode.INVALID_RESTORED_STATE);
    }

    // ---------- 时间先后关系 ----------

    @Test
    void rejectsCreatedAtAfterUpdatedAt() {
        Snapshot snapshot = new Snapshot();
        snapshot.createdAt = BASE.plusSeconds(10);
        snapshot.updatedAt = BASE;

        assertErrorCode(snapshot::restore, TicketErrorCode.INVALID_RESTORED_STATE);
    }

    @Test
    void rejectsResolvedAtBeforeCreatedAt() {
        Snapshot snapshot = snapshot(TicketStatus.RESOLVED);
        snapshot.resolvedAt = BASE.minusSeconds(1);
        snapshot.updatedAt = BASE;

        assertErrorCode(snapshot::restore, TicketErrorCode.INVALID_RESTORED_STATE);
    }

    @Test
    void rejectsClosedAtBeforeResolvedAt() {
        Snapshot snapshot = snapshot(TicketStatus.CLOSED);
        snapshot.closedAt = BASE.plusSeconds(5);

        assertErrorCode(snapshot::restore, TicketErrorCode.INVALID_RESTORED_STATE);
    }

    @Test
    void acceptsEqualTimestamps() {
        Snapshot snapshot = snapshot(TicketStatus.CLOSED);
        snapshot.resolvedAt = BASE;
        snapshot.closedAt = BASE;
        snapshot.updatedAt = BASE;

        Ticket ticket = snapshot.restore();

        assertThat(ticket.resolvedAt()).contains(BASE);
        assertThat(ticket.closedAt()).contains(BASE);
    }

    @Test
    void rejectsResolvedAtAfterUpdatedAtForResolvedSnapshot() {
        Snapshot snapshot = snapshot(TicketStatus.RESOLVED);
        snapshot.resolvedAt = BASE.plusSeconds(20);
        snapshot.updatedAt = BASE.plusSeconds(10);

        assertErrorCode(snapshot::restore, TicketErrorCode.INVALID_RESTORED_STATE);
    }

    @Test
    void rejectsResolvedAtAfterUpdatedAtForClosedSnapshot() {
        Snapshot snapshot = snapshot(TicketStatus.CLOSED);
        snapshot.resolvedAt = BASE.plusSeconds(30);
        snapshot.closedAt = BASE.plusSeconds(40);
        snapshot.updatedAt = BASE.plusSeconds(20);

        assertErrorCode(snapshot::restore, TicketErrorCode.INVALID_RESTORED_STATE);
    }

    @Test
    void rejectsClosedAtAfterUpdatedAt() {
        Snapshot snapshot = snapshot(TicketStatus.CLOSED);
        snapshot.resolvedAt = BASE.plusSeconds(5);
        snapshot.closedAt = BASE.plusSeconds(30);
        snapshot.updatedAt = BASE.plusSeconds(20);

        assertErrorCode(snapshot::restore, TicketErrorCode.INVALID_RESTORED_STATE);
    }

    @Test
    void acceptsResolvedAtEqualToUpdatedAt() {
        Snapshot snapshot = snapshot(TicketStatus.RESOLVED);
        snapshot.resolvedAt = BASE.plusSeconds(10);
        snapshot.updatedAt = BASE.plusSeconds(10);

        Ticket ticket = snapshot.restore();

        assertThat(ticket.resolvedAt()).contains(BASE.plusSeconds(10));
        assertThat(ticket.updatedAt()).isEqualTo(BASE.plusSeconds(10));
    }

    @Test
    void acceptsClosedAtEqualToUpdatedAt() {
        Snapshot snapshot = snapshot(TicketStatus.CLOSED);
        snapshot.resolvedAt = BASE.plusSeconds(5);
        snapshot.closedAt = BASE.plusSeconds(20);
        snapshot.updatedAt = BASE.plusSeconds(20);

        Ticket ticket = snapshot.restore();

        assertThat(ticket.closedAt()).contains(BASE.plusSeconds(20));
        assertThat(ticket.updatedAt()).isEqualTo(BASE.plusSeconds(20));
    }

    @Test
    void acceptsTheWholeChainAtTheSameInstant() {
        Snapshot snapshot = snapshot(TicketStatus.CLOSED);
        snapshot.createdAt = BASE;
        snapshot.resolvedAt = BASE;
        snapshot.closedAt = BASE;
        snapshot.updatedAt = BASE;

        Ticket ticket = snapshot.restore();

        assertThat(ticket.createdAt()).isEqualTo(BASE);
        assertThat(ticket.resolvedAt()).contains(BASE);
        assertThat(ticket.closedAt()).contains(BASE);
        assertThat(ticket.updatedAt()).isEqualTo(BASE);
    }

    /**
     * 回归测试：修复前，{@code resolvedAt} 晚于 {@code updatedAt} 的 {@code RESOLVED} 快照可以被恢复，
     * 随后 {@code close(updatedAt)} 会把 {@code closedAt} 写到 {@code resolvedAt} 之前。
     * 现在这样的快照一律无法恢复，因此该路径不再存在。
     */
    @Test
    void rejectsAnyResolvedSnapshotThatWouldLetTimeFlowBackwardsOnClose() {
        Instant[] grid = {
                BASE,
                BASE.plusSeconds(5),
                BASE.plusSeconds(10),
                BASE.plusSeconds(20)
        };

        for (Instant resolvedAt : grid) {
            for (Instant updatedAt : grid) {
                Snapshot snapshot = snapshot(TicketStatus.RESOLVED);
                snapshot.resolvedAt = resolvedAt;
                snapshot.updatedAt = updatedAt;

                try {
                    Ticket ticket = snapshot.restore();
                    assertThat(resolvedAt.isAfter(updatedAt))
                            .as("resolvedAt=%s 晚于 updatedAt=%s 的快照不该被恢复", resolvedAt, updatedAt)
                            .isFalse();

                    // 恢复后立即以 updatedAt 关闭，也绝不能让时间倒流
                    ticket.close(ticket.updatedAt());
                    assertThat(ticket.closedAt().orElseThrow())
                            .as("resolvedAt=%s updatedAt=%s", resolvedAt, updatedAt)
                            .isAfterOrEqualTo(ticket.resolvedAt().orElseThrow());
                    assertThat(ticket.updatedAt()).isEqualTo(ticket.closedAt().orElseThrow());
                } catch (TicketDomainException ex) {
                    assertThat(ex.errorCode())
                            .as("resolvedAt=%s updatedAt=%s", resolvedAt, updatedAt)
                            .isEqualTo(TicketErrorCode.INVALID_RESTORED_STATE);
                }
            }
        }
    }

    @Test
    void keepsTheChainForEveryRestorableClosedSnapshot() {
        Instant[] grid = { BASE, BASE.plusSeconds(5), BASE.plusSeconds(10) };

        for (Instant resolvedAt : grid) {
            for (Instant closedAt : grid) {
                for (Instant updatedAt : grid) {
                    Snapshot snapshot = snapshot(TicketStatus.CLOSED);
                    snapshot.resolvedAt = resolvedAt;
                    snapshot.closedAt = closedAt;
                    snapshot.updatedAt = updatedAt;

                    try {
                        Ticket ticket = snapshot.restore();
                        Instant resolved = ticket.resolvedAt().orElseThrow();
                        Instant closed = ticket.closedAt().orElseThrow();
                        assertThat(ticket.createdAt()).isBeforeOrEqualTo(resolved);
                        assertThat(resolved).isBeforeOrEqualTo(closed);
                        assertThat(closed).isBeforeOrEqualTo(ticket.updatedAt());
                    } catch (TicketDomainException ex) {
                        assertThat(ex.errorCode())
                                .as("resolvedAt=%s closedAt=%s updatedAt=%s", resolvedAt, closedAt, updatedAt)
                                .isEqualTo(TicketErrorCode.INVALID_RESTORED_STATE);
                    }
                }
            }
        }
    }

    // ---------- 字段级校验不被绕过 ----------

    @Test
    void stillAppliesTitleRules() {
        Snapshot tooLong = new Snapshot();
        tooLong.title = "t".repeat(Ticket.MAX_TITLE_LENGTH + 1);
        assertErrorCode(tooLong::restore, TicketErrorCode.INVALID_TITLE);

        Snapshot blank = new Snapshot();
        blank.title = "   ";
        assertErrorCode(blank::restore, TicketErrorCode.INVALID_TITLE);

        Snapshot missing = new Snapshot();
        missing.title = null;
        assertErrorCode(missing::restore, TicketErrorCode.INVALID_TITLE);
    }

    @Test
    void stillAppliesDescriptionRules() {
        Snapshot tooLong = new Snapshot();
        tooLong.description = "d".repeat(Ticket.MAX_DESCRIPTION_LENGTH + 1);
        assertErrorCode(tooLong::restore, TicketErrorCode.INVALID_DESCRIPTION);

        Snapshot blank = new Snapshot();
        blank.description = "   ";
        assertErrorCode(blank::restore, TicketErrorCode.INVALID_DESCRIPTION);
    }

    @Test
    void stillAppliesResolutionRules() {
        Snapshot tooLong = snapshot(TicketStatus.RESOLVED);
        tooLong.resolution = "r".repeat(Ticket.MAX_RESOLUTION_LENGTH + 1);
        assertErrorCode(tooLong::restore, TicketErrorCode.INVALID_RESOLUTION);

        Snapshot blank = snapshot(TicketStatus.RESOLVED);
        blank.resolution = "   ";
        assertErrorCode(blank::restore, TicketErrorCode.INVALID_RESOLUTION);
    }

    @Test
    void stripsStringsWhileRestoring() {
        Snapshot snapshot = new Snapshot();
        snapshot.title = "  " + TITLE + "  ";
        snapshot.description = "  " + DESCRIPTION + "  ";

        Ticket ticket = snapshot.restore();

        assertThat(ticket.title()).isEqualTo(TITLE);
        assertThat(ticket.description()).isEqualTo(DESCRIPTION);
    }

    @Test
    void rejectsMissingMandatoryReferences() {
        Snapshot withoutId = new Snapshot();
        withoutId.id = null;
        assertErrorCode(withoutId::restore, TicketErrorCode.INVALID_RESTORED_STATE);

        Snapshot withoutCategory = new Snapshot();
        withoutCategory.category = null;
        assertErrorCode(withoutCategory::restore, TicketErrorCode.INVALID_RESTORED_STATE);

        Snapshot withoutPriority = new Snapshot();
        withoutPriority.priority = null;
        assertErrorCode(withoutPriority::restore, TicketErrorCode.INVALID_RESTORED_STATE);

        Snapshot withoutRequester = new Snapshot();
        withoutRequester.requesterId = null;
        assertErrorCode(withoutRequester::restore, TicketErrorCode.INVALID_RESTORED_STATE);

        Snapshot withoutStatus = new Snapshot();
        withoutStatus.status = null;
        assertErrorCode(withoutStatus::restore, TicketErrorCode.INVALID_RESTORED_STATE);

        Snapshot withoutCreatedAt = new Snapshot();
        withoutCreatedAt.createdAt = null;
        assertErrorCode(withoutCreatedAt::restore, TicketErrorCode.INVALID_RESTORED_STATE);

        Snapshot withoutUpdatedAt = new Snapshot();
        withoutUpdatedAt.updatedAt = null;
        assertErrorCode(withoutUpdatedAt::restore, TicketErrorCode.INVALID_RESTORED_STATE);
    }

    /** @return 与给定状态自洽的快照，可按字段逐项破坏以构造不一致用例 */
    private static Snapshot snapshot(TicketStatus status) {
        Snapshot snapshot = new Snapshot();
        snapshot.status = status;
        switch (status) {
            case NEW -> { }
            case ASSIGNED, IN_PROGRESS -> snapshot.assigneeId = BOB;
            case RESOLVED -> {
                snapshot.assigneeId = BOB;
                snapshot.resolution = RESOLUTION;
                snapshot.resolvedAt = BASE.plusSeconds(10);
                snapshot.updatedAt = BASE.plusSeconds(10);
            }
            case CLOSED -> {
                snapshot.assigneeId = BOB;
                snapshot.resolution = RESOLUTION;
                snapshot.resolvedAt = BASE.plusSeconds(10);
                snapshot.closedAt = BASE.plusSeconds(20);
                snapshot.updatedAt = BASE.plusSeconds(20);
            }
        }
        return snapshot;
    }

    /** 可逐字段篡改的恢复参数快照。 */
    private static final class Snapshot {

        private TicketId id = TICKET_ID;

        private String title = TITLE;

        private String description = DESCRIPTION;

        private TicketCategory category = CATEGORY;

        private TicketPriority priority = PRIORITY;

        private UserId requesterId = ALICE;

        private UserId assigneeId;

        private TicketStatus status = TicketStatus.NEW;

        private String resolution;

        private Instant createdAt = BASE;

        private Instant updatedAt = BASE;

        private Instant resolvedAt;

        private Instant closedAt;

        private Ticket restore() {
            return Ticket.restore(this.id, this.title, this.description, this.category, this.priority,
                    this.requesterId, this.assigneeId, this.status, this.resolution, this.createdAt,
                    this.updatedAt, this.resolvedAt, this.closedAt);
        }
    }
}
