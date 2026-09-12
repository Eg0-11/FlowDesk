package com.flowdesk.mcp.asset;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 资产 MCP 服务上下文测试：校验 Spring 上下文可以正常装配。
 */
@SpringBootTest
class AssetMcpApplicationTests {

    @Test
    void contextLoads() {
        // 上下文装配失败时该测试即失败，无需断言。
    }
}
