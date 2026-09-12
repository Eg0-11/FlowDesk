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
 * <p><b>受 {@code flowdesk.ai.enabled} 控制的组件</b>（默认 profile 下该属性为 {@code false}，
 * 下列 Bean 一个都不会创建）：</p>
 * <ul>
 *   <li>{@code com.flowdesk.agent.ai}：AI 用例实现 {@code ChatClientAiService}
 *       与本地只读工具 {@code SupportPolicyTools}；</li>
 *   <li>{@code com.flowdesk.infrastructure.ai}：DeepSeek 配置装配、命名 ChatClient
 *       {@code deepSeekChatClient} 与传输层拦截器；</li>
 *   <li>{@code com.flowdesk.bootstrap.ai}：{@code AiController}，即两个 AI HTTP 端点。</li>
 * </ul>
 *
 * <p><b>始终注册、不受 {@code flowdesk.ai.enabled} 影响的组件</b>：
 * {@code AiExceptionHandler} 是全局 {@code @RestControllerAdvice}，条件开关不会作用于它。
 * 它只负责把非法请求映射为 400、把上游失败映射为 502，本身不创建任何模型 Bean，
 * 也不会发起任何出网请求 —— 因此默认 profile 下没有 API Key 也能安全启动。</p>
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
