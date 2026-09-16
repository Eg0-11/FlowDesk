package com.flowdesk.infrastructure.knowledge.rerank;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 重排 Endpoint 的传输安全边界（FD-0013-R1 / R2）。
 *
 * <h2>规则</h2>
 * <ul>
 *   <li><b>HTTPS</b>：允许（Bearer Key 在 TLS 内传输）；</li>
 *   <li><b>明文 HTTP + 本机回环</b>：允许 —— 这是自动化测试用本地合成端点验证真实报文的唯一途径，
 *       流量不出本机。回环的判定见下；</li>
 *   <li><b>其它明文 HTTP</b>：拒绝。Bearer Key 会随请求头以明文离开本机，
 *       任何中间节点都能直接取走它。</li>
 * </ul>
 *
 * <h2>什么算本机回环（FD-0013-R2 收紧）</h2>
 * <p>只认三种<b>字面量</b>写法，任何「看起来像」的域名或含糊数字都<b>不</b>算：</p>
 * <ol>
 *   <li>{@code localhost}（大小写不敏感）；</li>
 *   <li><b>完整的四段十进制 IPv4 字面量</b>且首段为 127（{@code 127.0.0.0/8}）：
 *       恰好四段、每段 1~3 位十进制数字且取值 {@code 0..255}、不接受前导零
 *       （{@code 0127.0.0.1} 这类写法在不同解析器里有八进制歧义）。
 *       因此 {@code 127.example.com}、{@code 127.0.0.1.attacker.example}、
 *       {@code 127.5}、{@code 127.999.999.999}、{@code 2130706433} 全部<b>不是</b>回环；</li>
 *   <li>IPv6 回环：{@code ::1} 及其完整写法 {@code 0:0:0:0:0:0:0:1}（允许零填充），
 *       判定方式是展开 {@code ::} 后恰好八组、除最后一组（必须为 1）外全为 0。
 *       内嵌 IPv4 的写法（{@code ::ffff:127.0.0.1} 等）不接受。</li>
 * </ol>
 *
 * <p>早期实现用 {@code host.startsWith("127.")} 判断回环 —— 那等于把
 * {@code 127.0.0.1.attacker.example} 这种「前缀是回环、实际由攻击者控制」的域名放进了白名单，
 * 于是 Bearer Key 会被明文发往远端。因此现在按<b>完整字面量</b>逐段校验。</p>
 *
 * <h2>不做 DNS 查询</h2>
 * <p>判定只做字面匹配，<b>不</b>解析域名：校验阶段不得联网，
 * 而且「名字解析到 127.0.0.1」并不等于「这就是本机端点」（DNS 可以被指向任何地方）。
 * 也正因如此，这里的白名单是「写法」而不是「解析结果」，不会随环境变化。</p>
 *
 * <h2>共同约束</h2>
 * <p>配置校验与适配器构造器<b>都</b>调用本类：只在 Spring 装配层拦一次挡不住
 * 「直接 new 出适配器」的路径。失败信息只说明规则，<b>不</b>回显 Endpoint、Key、主机名
 * 或任何请求内容。</p>
 */
final class RerankEndpointPolicy {

    /** IPv4 回环前缀（完整四段字面量的首段）。 */
    private static final int IPV4_LOOPBACK_FIRST_OCTET = 127;

    /** IPv6 地址展开后的组数。 */
    private static final int IPV6_GROUP_COUNT = 8;

    private RerankEndpointPolicy() {
    }

    /**
     * 要求重排 Endpoint 使用安全传输。
     *
     * @param endpoint 重排接口地址
     * @throws IllegalStateException 明文 HTTP 且主机不是本机回环字面量
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
                + "明文 HTTP 只允许本机回环地址（localhost、完整的 127.x.x.x 四段 IPv4 字面量、::1），"
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
            // java.net.URI 对 IPv6 主机保留方括号：http://[::1]/x 的 host 是 "[::1]"
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (normalized.equals("localhost")) {
            return true;
        }
        if (normalized.indexOf(':') >= 0) {
            return isIpv6Loopback(normalized);
        }
        return isIpv4Loopback(normalized);
    }

    /**
     * 判断是否为「完整的四段十进制 IPv4 字面量 + 首段 127」。
     *
     * @param host 已去方括号、已小写化的主机名
     * @return 是否落在 127.0.0.0/8
     */
    private static boolean isIpv4Loopback(String host) {
        String[] segments = host.split("\\.", -1);
        if (segments.length != 4) {
            return false;
        }
        int[] octets = new int[4];
        for (int index = 0; index < 4; index++) {
            Integer octet = parseDecimalOctet(segments[index]);
            if (octet == null) {
                return false;
            }
            octets[index] = octet;
        }
        return octets[0] == IPV4_LOOPBACK_FIRST_OCTET;
    }

    /**
     * 解析单个 IPv4 段：必须是 1~3 位十进制数字、取值 {@code 0..255}，且不接受前导零。
     *
     * @param segment 段文本
     * @return 取值；不合法时为 {@code null}
     */
    private static Integer parseDecimalOctet(String segment) {
        if (segment.isEmpty() || segment.length() > 3) {
            return null;
        }
        for (int index = 0; index < segment.length(); index++) {
            char digit = segment.charAt(index);
            if (digit < '0' || digit > '9') {
                return null;
            }
        }
        if (segment.length() > 1 && segment.charAt(0) == '0') {
            // 前导零在不同解析器里可能是八进制（0127 = 87）：含糊写法一律拒绝
            return null;
        }
        int value = Integer.parseInt(segment);
        return value <= 255 ? value : null;
    }

    /**
     * 判断是否为 IPv6 回环（{@code ::1} 及其完整写法）。
     *
     * @param host 已去方括号、已小写化的主机名
     * @return 是否等价于 {@code ::1}
     */
    private static boolean isIpv6Loopback(String host) {
        if (host.indexOf('.') >= 0) {
            // 内嵌 IPv4（::ffff:127.0.0.1 等）：不接受，避免多一种需要判断的写法
            return false;
        }
        int firstDoubleColon = host.indexOf("::");
        if (firstDoubleColon >= 0 && firstDoubleColon != host.lastIndexOf("::")) {
            // 出现多个 "::"：非法写法
            return false;
        }

        List<String> groups = new ArrayList<>(IPV6_GROUP_COUNT);
        if (firstDoubleColon < 0) {
            groups.addAll(List.of(host.split(":", -1)));
        }
        else {
            List<String> left = firstDoubleColon == 0
                    ? List.of()
                    : List.of(host.substring(0, firstDoubleColon).split(":", -1));
            String remainder = host.substring(firstDoubleColon + 2);
            List<String> right = remainder.isEmpty() ? List.of() : List.of(remainder.split(":", -1));
            int zeros = IPV6_GROUP_COUNT - left.size() - right.size();
            if (zeros < 1) {
                // "::" 至少要代表一组 0，否则地址组数会超过 8
                return false;
            }
            groups.addAll(left);
            for (int index = 0; index < zeros; index++) {
                groups.add("0");
            }
            groups.addAll(right);
        }

        if (groups.size() != IPV6_GROUP_COUNT) {
            return false;
        }
        for (int index = 0; index < IPV6_GROUP_COUNT - 1; index++) {
            if (!isZeroGroup(groups.get(index))) {
                return false;
            }
        }
        return isOneGroup(groups.get(IPV6_GROUP_COUNT - 1));
    }

    /**
     * @param group 一组十六进制文本
     * @return 是否为全零组（1~4 位十六进制的 0）
     */
    private static boolean isZeroGroup(String group) {
        return !group.isEmpty() && group.length() <= 4 && group.chars().allMatch(ch -> ch == '0');
    }

    /**
     * @param group 一组十六进制文本
     * @return 是否取值为 1（允许零填充，如 {@code 0001}）
     */
    private static boolean isOneGroup(String group) {
        if (group.isEmpty() || group.length() > 4) {
            return false;
        }
        for (int index = 0; index < group.length() - 1; index++) {
            if (group.charAt(index) != '0') {
                return false;
            }
        }
        return group.charAt(group.length() - 1) == '1';
    }
}
