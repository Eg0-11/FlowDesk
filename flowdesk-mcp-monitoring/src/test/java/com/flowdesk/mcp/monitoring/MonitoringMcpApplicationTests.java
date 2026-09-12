package com.flowdesk.mcp.monitoring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 监控 MCP 服务上下文测试：校验 Spring 上下文可以正常装配。
 */
@SpringBootTest
class MonitoringMcpApplicationTests {

    @Test
    void contextLoads() {
        // 上下文装配失败时该测试即失败，无需断言。
    }
}
