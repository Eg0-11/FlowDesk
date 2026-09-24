package com.flowdesk.bootstrap.web;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

/**
 * 主服务监听地址的<b>最早</b>一道闸门（FD-0021）。
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
 * <h2>检查的是「最终生效」的值</h2>
 * <p>读取的是合并后的 {@code Environment}，因此命令行参数、环境变量、配置文件与
 * 任何更高优先级的属性源<b>都绕不过去</b>：把 {@code application.yml} 的默认值改成
 * {@code 127.0.0.1} 只是默认值，闸门才是边界。</p>
 *
 * <p>装配期校验 Bean（见 {@link MainServiceBindingConfiguration}）作为第二道闸门保留：
 * 它共享同一份判定与同一条错误信息，用于兜住「绕过本监听器直接刷新上下文」的路径。</p>
 *
 * <h2>只认字面量</h2>
 * <p>判定只在 {@link LoopbackAddressPolicy} 里实现一次：完整四段十进制 IPv4 回环字面量，
 * 或 IPv6 回环 {@code ::1} 的完整写法；不做 DNS 解析，也不接受 {@code localhost} 这样的主机名
 * ——「名字」可能被 hosts 文件或 DNS 指向任何地方。</p>
 */
public class MainServiceBindingGuard implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    /** 固定的错误文案（不回显配置值、不联网、不含任何环境信息）。 */
    static final String MESSAGE = "主服务只允许监听本机回环地址："
            + "请把 server.address 设为 127.0.0.1（或 IPv6 回环 ::1 的完整写法）。"
            + "不接受 0.0.0.0、::、具体局域网/公网地址、主机名（如 localhost）与其它含糊写法。"
            + "主服务当前没有鉴权，只供本机演示；远程访问必须先单独设计鉴权与授权。";

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        requireLoopbackBinding(event.getEnvironment());
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
}
