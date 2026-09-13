package com.flowdesk.application.ticket;

import com.flowdesk.application.ticket.port.out.TimeProvider;
import java.time.Duration;
import java.time.Instant;

/**
 * 记录调用次数的时间端口（测试替身）。
 *
 * <p>可选「每次调用后前进一个固定步长」，让连续命令获得递增但完全确定的时间戳。</p>
 */
final class RecordingTimeProvider implements TimeProvider {

    private final Duration step;

    private Instant current;

    private int calls;

    RecordingTimeProvider(Instant initial) {
        this(initial, Duration.ZERO);
    }

    RecordingTimeProvider(Instant initial, Duration step) {
        this.current = initial;
        this.step = step;
    }

    @Override
    public Instant now() {
        this.calls++;
        Instant value = this.current;
        this.current = this.current.plus(this.step);
        return value;
    }

    int calls() {
        return this.calls;
    }

    Instant current() {
        return this.current;
    }

    void advanceTo(Instant instant) {
        this.current = instant;
    }
}
