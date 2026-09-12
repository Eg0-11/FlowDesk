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
 * <p>当前装配会激活三部分组件：{@code com.flowdesk.agent.ai} 中的 AI 用例实现与本地只读工具、
 * {@code com.flowdesk.infrastructure.ai} 中的 DeepSeek 传输适配与命名 ChatClient
 * {@code deepSeekChatClient}、以及 {@code com.flowdesk.bootstrap.ai} 中的 AI HTTP 接口与异常映射。
 * 它们都受 {@code flowdesk.ai.enabled} 控制：默认 profile 下不会创建任何模型相关 Bean，
 * 因此没有 API Key 也能启动。</p>
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
