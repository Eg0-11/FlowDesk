package com.flowdesk.application.knowledge.port.out;

import java.time.Instant;

/**
 * 知识文档时间端口。
 *
 * <p>时间必须来自端口而不是 {@code Instant.now()}：用例因此完全确定，可测试。</p>
 */
public interface KnowledgeTimeProvider {

    /**
     * @return 当前时间
     */
    Instant now();
}
