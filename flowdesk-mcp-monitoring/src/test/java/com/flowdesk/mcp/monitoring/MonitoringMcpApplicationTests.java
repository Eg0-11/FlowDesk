package com.flowdesk.mcp.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/**
 * 监控 MCP 服务上下文测试：校验 Spring 上下文可以正常装配，并钉住随包交付的启动契约。
 */
@SpringBootTest
class MonitoringMcpApplicationTests {

    @Autowired
    private Environment environment;

    @Test
    void contextLoads() {
        // 上下文装配失败时该测试即失败，无需断言。
    }

    /**
     * 随包交付的启动契约：端口 {@code 8092}、只监听回环 {@code 127.0.0.1}、
     * MCP 端点 {@code /mcp}、数据源模式默认 {@code unavailable}。
     *
     * <p>「只能监听回环」本身由启动期闸门证明（任何非回环的 {@code server.address}
     * 都会在创建 Web 服务器之前失败，见 {@code MonitoringMcpBindingFailFastTests}）；
     * 本用例补的是另一半：把交付默认值本身钉住，避免它在无意中被改成别的端口、
     * 别的监听地址或别的端点路径 —— 这三项都是对外公布的接口，不能只靠约定。</p>
     *
     * <p>本用例是 {@code MOCK} Web 环境，<b>不会</b>真的绑定端口，
     * 因此断言的是配置解析结果，而不是「端口是否被占用」。</p>
     */
    @Test
    void theShippedStartupContractIsPinned() {
        assertThat(this.environment.getProperty("server.port")).isEqualTo("8092");
        assertThat(this.environment.getProperty("server.address")).isEqualTo("127.0.0.1");
        assertThat(this.environment.getProperty("spring.ai.mcp.server.streamable-http.mcp-endpoint"))
                .isEqualTo("/mcp");
        assertThat(this.environment.getProperty("flowdesk.monitoring.source.mode"))
                .as("默认必须是没有数据源，而不是演示数据")
                .isEqualTo("unavailable");
    }
}
