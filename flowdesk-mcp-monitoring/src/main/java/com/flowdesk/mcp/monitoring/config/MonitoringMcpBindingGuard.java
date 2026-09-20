package com.flowdesk.mcp.monitoring.config;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

/**
 * 监听地址的<b>最早</b>一道闸门（FD-0015）。
 *
 * <h2>为什么必须这么早</h2>
 * <p>Spring Boot 的刷新顺序是：环境准备 → … → {@code onRefresh()}（<b>此刻创建并绑定 Web 服务器</b>）
 * → {@code finishBeanFactoryInitialization}（此后才实例化普通单例 Bean）。因此把校验写成
 * 普通的「装配期校验 Bean」时，Tomcat <b>已经绑定完端口</b>了 ——
 * 即使紧接着让上下文启动失败，那一瞬间网络栈上也确实存在过一个监听的套接字。</p>
 *
 * <p>{@link ApplicationEnvironmentPreparedEvent} 发生在<b>上下文创建之前</b>：
 * 在这里拒绝一份非回环的 {@code server.address}，意味着<b>不会创建 Web 服务器、
 * 不会绑定任何端口</b>。这才是「在启动阶段拒绝」的严格含义。</p>
 *
 * <p>装配期校验 Bean（见 {@code MonitoringMcpConfiguration}）作为第二道闸门保留：
 * 它共享同一份判定与同一条错误信息，用于兜住「绕过本监听器直接刷新上下文」的路径。</p>
 *
 * <h2>只认字面量</h2>
 * <p>判定只在 {@link LoopbackAddressPolicy} 里实现一次：完整四段十进制 IPv4 回环字面量，
 * 或 IPv6 回环 {@code ::1} 的完整写法；不做 DNS 解析，也不接受 {@code localhost} 这样的主机名
 * ——「名字」可能被 hosts 文件或 DNS 指向任何地方。</p>
 */
public class MonitoringMcpBindingGuard implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    /** 固定的错误文案（不回显配置值、不联网、不含任何环境信息）。 */
    static final String MESSAGE = "flowdesk-mcp-monitoring 只允许监听本机回环地址："
            + "请把 server.address 设为 127.0.0.1（或 IPv6 回环 ::1 的完整写法）。"
            + "不接受 0.0.0.0、具体外网地址、主机名（如 localhost）与其它含糊写法。";

    /** 数据源模式的固定错误文案（同样不回显配置值）。 */
    static final String MODE_MESSAGE = "flowdesk.monitoring.source.mode 只允许精确的 "
            + MonitoringSourceProperties.MODE_UNAVAILABLE + " 或 " + MonitoringSourceProperties.MODE_DEMO
            + "（不接受未知值、大小写变体或带空白的值）";

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        requireLoopbackBinding(event.getEnvironment());
        requireSupportedMode(event.getEnvironment());
    }

    /**
     * 校验监听地址是本机回环字面量。
     *
     * @param environment 配置环境
     * @throws IllegalStateException 地址缺失、为空或不是回环字面量
     */
    static void requireLoopbackBinding(Environment environment) {
        if (!LoopbackAddressPolicy.isLoopbackLiteral(environment.getProperty("server.address"))) {
            throw new IllegalStateException(MESSAGE);
        }
    }

    /**
     * 校验数据源模式：只接受精确的 {@code unavailable} 与 {@code demo}。
     *
     * <p>放在这一道闸门里的理由与监听地址相同：错误配置应当在<b>创建 Web 服务器之前</b>就被拒绝，
     * 而不是先起一个监听端口再失败。缺省（属性不存在）是允许的 —— 那时用默认的
     * {@code unavailable}，与 {@link MonitoringSourceProperties} 的默认值一致。</p>
     *
     * @param environment 配置环境
     * @throws IllegalStateException 模式不是两个合法字面量之一
     */
    static void requireSupportedMode(Environment environment) {
        String mode = environment.getProperty("flowdesk.monitoring.source.mode");
        if (mode == null) {
            return;
        }
        if (!MonitoringSourceProperties.MODE_UNAVAILABLE.equals(mode)
                && !MonitoringSourceProperties.MODE_DEMO.equals(mode)) {
            throw new IllegalStateException(MODE_MESSAGE);
        }
    }
}
