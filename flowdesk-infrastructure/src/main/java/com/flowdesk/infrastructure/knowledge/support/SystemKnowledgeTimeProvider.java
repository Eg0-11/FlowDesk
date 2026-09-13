package com.flowdesk.infrastructure.knowledge.support;

import com.flowdesk.application.knowledge.port.out.KnowledgeTimeProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * 基于注入 {@link Clock} 的时间端口。
 *
 * <p>统一截断到微秒：数据库列是 {@code TIMESTAMP(6)}，先截断可以让「内存中的值」与
 * 「写库再读回来的值」完全一致，避免往返比较出现假失败。时钟本身由装配层提供（UTC）。</p>
 */
public final class SystemKnowledgeTimeProvider implements KnowledgeTimeProvider {

    private final Clock clock;

    /**
     * @param clock 时钟，通常为 {@code Clock.systemUTC()}
     */
    public SystemKnowledgeTimeProvider(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock 不能为 null");
    }

    @Override
    public Instant now() {
        return this.clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
