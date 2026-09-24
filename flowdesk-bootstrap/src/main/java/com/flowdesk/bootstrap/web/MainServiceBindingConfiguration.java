package com.flowdesk.bootstrap.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 监听地址的<b>第二道</b>闸门（FD-0021）。
 *
 * <p>真正的时间点保证在第一道闸门（{@link MainServiceBindingGuard}，
 * 监听 {@code ApplicationEnvironmentPreparedEvent}，发生在创建 Web 服务器之前）。
 * 这里保留一份装配期校验，用于兜住「绕过监听器直接刷新上下文」的路径，
 * 共享同一份判定 {@link LoopbackAddressPolicy} 与同一条固定错误信息。</p>
 */
@Configuration(proxyBeanMethods = false)
public class MainServiceBindingConfiguration {

    /**
     * 装配期校验：非回环的 {@code server.address} 直接让上下文装配失败。
     *
     * @param environment 配置环境
     * @return 校验通过标记
     */
    @Bean
    public Boolean mainServiceBindingConsistency(Environment environment) {
        MainServiceBindingGuard.requireLoopbackBinding(environment);
        return Boolean.TRUE;
    }
}
