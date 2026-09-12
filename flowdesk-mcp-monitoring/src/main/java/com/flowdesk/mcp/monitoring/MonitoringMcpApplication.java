package com.flowdesk.mcp.monitoring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 监控 MCP 服务启动类。
 *
 * <p>本服务作为独立进程运行，只启动 Spring 上下文并启用 Actuator 健康检查，
 * 便于后续接入 MCP 协议；监控相关的 MCP 工具与能力尚未实现。</p>
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
