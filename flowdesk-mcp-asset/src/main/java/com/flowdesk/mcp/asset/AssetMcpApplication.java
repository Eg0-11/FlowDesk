package com.flowdesk.mcp.asset;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 资产 MCP 服务启动类。
 *
 * <p>本服务作为独立进程运行，只启动 Spring 上下文并启用 Actuator 健康检查，
 * 便于后续接入 MCP 协议；资产相关的 MCP 工具与能力尚未实现。</p>
 */
@SpringBootApplication
public class AssetMcpApplication {

    /**
     * 启动资产 MCP 服务。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(AssetMcpApplication.class, args);
    }
}
