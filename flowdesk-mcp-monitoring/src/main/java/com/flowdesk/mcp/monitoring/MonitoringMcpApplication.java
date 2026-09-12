package com.flowdesk.mcp.monitoring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 监控 MCP 服务启动类。
 *
 * <p>当前阶段（FD-0001）只负责启动 Spring 上下文并启用 Actuator 健康检查，
 * 尚未实现任何 MCP 工具或监控能力。</p>
 */
@SpringBootApplication
public class MonitoringMcpApplication {

    /**
     * 启动监控 MCP 服务。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(MonitoringMcpApplication.class, args);
    }
}
