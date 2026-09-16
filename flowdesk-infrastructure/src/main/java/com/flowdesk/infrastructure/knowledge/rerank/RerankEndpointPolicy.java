package com.flowdesk.infrastructure.knowledge.rerank;

import java.net.URI;
import java.util.Locale;

/**
 * 重排 Endpoint 的传输安全边界（FD-0013-R1）。
 *
 * <h2>规则</h2>
 * <ul>
 *   <li><b>HTTPS</b>：允许（Bearer Key 在 TLS 内传输）；</li>
 *   <li><b>明文 HTTP + 本机回环</b>（{@code 127.0.0.0/8}、{@code localhost}、{@code ::1}）：允许 ——
 *       这是自动化测试用本地合成端点验证真实请求体的唯一途径，流量不出本机；</li>
 *   <li><b>其它明文 HTTP</b>：拒绝。Bearer Key 会随请求头以明文离开本机，
 *       任何中间节点都能直接取走它。</li>
 * </ul>
 *
 * <h2>为什么这是一个「共同约束」而不是配置层的一条校验</h2>
 * <p>只在 Spring 装配层拦一次是不够的：适配器可以被直接 {@code new} 出来（测试、其它装配方式、
 * 将来的脚本），那时配置校验根本不会执行。因此边界写成这个小策略类，
 * <b>配置校验与适配器构造器都调用它</b>，两处拿到的都是同一条判断与同一条错误信息。</p>
 *
 * <h2>不做的网络操作</h2>
 * <p>回环判定只做<b>字面匹配</b>，<b>不</b>做 DNS 解析：校验阶段不得联网，
 * 而且「名字解析到 127.0.0.1」并不等于「这就是本机端点」。</p>
 *
 * <h2>错误信息不泄漏</h2>
 * <p>失败信息只说明规则（必须 HTTPS、明文仅限回环），
 * <b>不</b>回显 Endpoint、Key、主机名或任何请求内容。</p>
 */
final class RerankEndpointPolicy {

    private RerankEndpointPolicy() {
    }

    /**
     * 要求重排 Endpoint 使用安全传输。
     *
     * @param endpoint 重排接口地址
     * @throws IllegalStateException 明文 HTTP 且不是本机回环地址
     */
    static void requireSecureEndpoint(URI endpoint) {
        String scheme = endpoint.getScheme();
        if (scheme != null && scheme.equalsIgnoreCase("https")) {
            return;
        }
        if (scheme != null && scheme.equalsIgnoreCase("http") && isLoopbackHost(endpoint.getHost())) {
            return;
        }
        throw new IllegalStateException("重排 Endpoint 必须使用 HTTPS："
                + "DashScope API Key 会随 Authorization 请求头一起发送，明文 HTTP 会让它在链路上直接暴露。"
                + "明文 HTTP 只允许本机回环地址（127.0.0.1 / localhost / ::1），"
                + "用于本地合成端点测试。请把 flowdesk.knowledge.rerank.endpoint 改为 https 地址。");
    }

    /**
     * 判断主机名是否为字面量的本机回环地址（<b>不</b>做 DNS 解析）。
     *
     * @param host 主机名（可带 IPv6 方括号）
     * @return 是否为本机回环
     */
    static boolean isLoopbackHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (normalized.equals("localhost") || normalized.equals("::1")
                || normalized.equals("0:0:0:0:0:0:0:1")) {
            return true;
        }
        // RFC 1122：整个 127.0.0.0/8 都是回环地址
        return normalized.startsWith("127.");
    }
}
