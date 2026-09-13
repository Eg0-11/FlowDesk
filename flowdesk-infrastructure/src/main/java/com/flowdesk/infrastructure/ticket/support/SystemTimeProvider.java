package com.flowdesk.infrastructure.ticket.support;

import com.flowdesk.application.ticket.port.out.TimeProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * 基于注入时钟的时间端口实现。
 *
 * <p>时钟由外部注入（便于测试替换），并且<b>截断到微秒</b>：
 * PostgreSQL 的 {@code TIMESTAMP(6)} 只有微秒精度，若不截断，
 * 纳秒部分的差异会在写库后消失，导致内存中的时间戳与数据库中的不一致。</p>
 *
 * <p>业务服务不得直接调用 {@code Instant.now()}；时间一律经本端口获取。</p>
 */
public final class SystemTimeProvider implements TimeProvider {

    private final Clock clock;

    /**
     * @param clock 系统时钟，通常由 Spring 注入
     */
    public SystemTimeProvider(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock 不能为 null");
    }

    @Override
    public Instant now() {
        return this.clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
