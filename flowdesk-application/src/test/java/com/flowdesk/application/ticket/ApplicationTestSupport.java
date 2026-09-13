package com.flowdesk.application.ticket;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowdesk.domain.ticket.TicketCategory;
import com.flowdesk.domain.ticket.TicketErrorCode;
import com.flowdesk.domain.ticket.TicketDomainException;
import com.flowdesk.domain.ticket.TicketId;
import com.flowdesk.domain.ticket.TicketPriority;
import com.flowdesk.domain.ticket.UserId;
import java.time.Instant;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

/**
 * 应用层测试共享夹具。
 *
 * <p>纯 JUnit 5 + AssertJ，不启动 Spring 上下文。</p>
 */
final class ApplicationTestSupport {

    static final TicketId TICKET_ID = TicketId.of(UUID.fromString("11111111-2222-3333-4444-555555555555"));

    static final TicketId MISSING_TICKET_ID = TicketId.of(UUID.fromString("99999999-8888-7777-6666-555555555555"));

    static final UserId ALICE = UserId.of("alice");

    static final UserId BOB = UserId.of("bob");

    static final String TITLE = "无法登录办公系统";

    static final String DESCRIPTION = "用户反馈输入正确密码后仍提示认证失败。";

    static final String RESOLUTION = "重置账号密码并通知用户重新登录。";

    static final TicketCategory CATEGORY = TicketCategory.ACCOUNT_ACCESS;

    static final TicketPriority PRIORITY = TicketPriority.P2;

    static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    private ApplicationTestSupport() {
    }

    /** 断言抛出应用层异常，且错误码为期望值（不解析文案）。 */
    static void assertApplicationError(ThrowingCallable callable, TicketApplicationErrorCode expected) {
        assertThatThrownBy(callable)
                .isInstanceOf(TicketApplicationException.class)
                .extracting(thrown -> ((TicketApplicationException) thrown).errorCode())
                .isEqualTo(expected);
    }

    /** 断言领域异常原样穿透应用层。 */
    static void assertDomainError(ThrowingCallable callable, TicketErrorCode expected) {
        assertThatThrownBy(callable)
                .isInstanceOf(TicketDomainException.class)
                .extracting(thrown -> ((TicketDomainException) thrown).errorCode())
                .isEqualTo(expected);
    }

    /** @return 抛出的异常实例，便于进一步断言文案 */
    static RuntimeException capture(ThrowingCallable callable) {
        try {
            callable.call();
        } catch (RuntimeException ex) {
            return ex;
        } catch (Throwable ex) {
            throw new AssertionError("期望 RuntimeException，实际为 " + ex.getClass().getName(), ex);
        }
        throw new AssertionError("期望抛出异常，但调用正常返回");
    }
}
