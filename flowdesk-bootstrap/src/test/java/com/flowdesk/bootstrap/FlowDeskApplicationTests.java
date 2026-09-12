package com.flowdesk.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * FlowDesk 主服务上下文测试：校验 Spring 上下文可以正常装配。
 */
@SpringBootTest
class FlowDeskApplicationTests {

    @Test
    void contextLoads() {
        // 上下文装配失败时该测试即失败，无需断言。
    }
}
