package com.flowdesk.application.ticket.port.out;

import java.time.Instant;

/**
 * 时间输出端口。
 *
 * <p>应用层与领域层都不读取系统时钟：时间一律由本端口提供，
 * 因此用例的时间行为可被测试完全掌控。</p>
 */
public interface TimeProvider {

    /**
     * @return 当前时刻，永不为 {@code null}
     */
    Instant now();
}
