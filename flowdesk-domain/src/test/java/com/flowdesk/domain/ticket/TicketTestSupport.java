package com.flowdesk.domain.ticket;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

/**
 * 领域测试共享夹具。
 *
 * <p>刻意不启动 Spring 上下文：领域层是纯 Java，测试只需要构造对象与断言。</p>
 */
final class TicketTestSupport {

    static final TicketId TICKET_ID = TicketId.of(UUID.fromString("11111111-2222-3333-4444-555555555555"));

    static final TicketId OTHER_TICKET_ID = TicketId.of(UUID.fromString("99999999-8888-7777-6666-555555555555"));

    static final UserId ALICE = UserId.of("alice");

    static final UserId BOB = UserId.of("bob");

    static final String TITLE = "无法登录办公系统";

    static final String DESCRIPTION = "用户反馈输入正确密码后仍提示认证失败。";

    static final String RESOLUTION = "重置账号密码并通知用户重新登录。";

    static final TicketCategory CATEGORY = TicketCategory.ACCOUNT_ACCESS;

    static final TicketPriority PRIORITY = TicketPriority.P2;

    /** 统一基准时刻，避免任何测试依赖系统时钟。 */
    static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    private TicketTestSupport() {
    }

    /** 创建一个处于 {@code NEW} 状态的新工单。 */
    static Ticket newTicket() {
        return Ticket.create(TICKET_ID, TITLE, DESCRIPTION, CATEGORY, PRIORITY, ALICE, BASE);
    }

    /** 构造一个 {@code NEW} 状态工单，用于逐字段篡改后的创建/恢复测试。 */
    static Ticket ticketInStatus(TicketStatus status) {
        return switch (status) {
            case NEW -> Ticket.restore(TICKET_ID, TITLE, DESCRIPTION, CATEGORY, PRIORITY, ALICE,
                    null, TicketStatus.NEW, null, BASE, BASE, null, null);
            case ASSIGNED -> Ticket.restore(TICKET_ID, TITLE, DESCRIPTION, CATEGORY, PRIORITY, ALICE,
                    BOB, TicketStatus.ASSIGNED, null, BASE, BASE, null, null);
            case IN_PROGRESS -> Ticket.restore(TICKET_ID, TITLE, DESCRIPTION, CATEGORY, PRIORITY, ALICE,
                    BOB, TicketStatus.IN_PROGRESS, null, BASE, BASE, null, null);
            case RESOLVED -> Ticket.restore(TICKET_ID, TITLE, DESCRIPTION, CATEGORY, PRIORITY, ALICE,
                    BOB, TicketStatus.RESOLVED, RESOLUTION, BASE, BASE.plusSeconds(10), BASE.plusSeconds(10), null);
            case CLOSED -> Ticket.restore(TICKET_ID, TITLE, DESCRIPTION, CATEGORY, PRIORITY, ALICE,
                    BOB, TicketStatus.CLOSED, RESOLUTION, BASE, BASE.plusSeconds(20), BASE.plusSeconds(10),
                    BASE.plusSeconds(20));
        };
    }

    /** 断言调用抛出领域异常，且错误码为期望值（不依赖异常文案）。 */
    static void assertErrorCode(ThrowingCallable callable, TicketErrorCode expected) {
        assertThatThrownBy(callable)
                .isInstanceOf(TicketDomainException.class)
                .extracting(thrown -> ((TicketDomainException) thrown).errorCode())
                .isEqualTo(expected);
    }

    /** @return 调用抛出的领域异常，便于进一步断言文案 */
    static TicketDomainException captureDomainException(ThrowingCallable callable) {
        try {
            callable.call();
        } catch (TicketDomainException ex) {
            return ex;
        } catch (Throwable ex) {
            throw new AssertionError("期望 TicketDomainException，实际为 " + ex.getClass().getName(), ex);
        }
        throw new AssertionError("期望抛出 TicketDomainException，但调用正常返回");
    }
}
