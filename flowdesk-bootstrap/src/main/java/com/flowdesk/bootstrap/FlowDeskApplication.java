package com.flowdesk.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * FlowDesk 主服务启动类。
 *
 * <p>扫描范围被显式限定为 {@code com.flowdesk.bootstrap}、{@code com.flowdesk.agent}
 * 与 {@code com.flowdesk.infrastructure} 三个包。两个 MCP 服务各自拥有独立的启动类与进程，
 * 它们的 {@code com.flowdesk.mcp.asset}、{@code com.flowdesk.mcp.monitoring} 包
 * 刻意不在主服务的扫描范围内。</p>
 *
 * <p>当前阶段（FD-0001-R1）只负责启动 Spring 上下文并启用 Actuator 健康检查，
 * 不注册任何业务组件。</p>
 */
@SpringBootApplication(scanBasePackages = {
        "com.flowdesk.bootstrap",
        "com.flowdesk.agent",
        "com.flowdesk.infrastructure"
})
public class FlowDeskApplication {

    /**
     * 启动 FlowDesk 主服务。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(FlowDeskApplication.class, args);
    }
}
