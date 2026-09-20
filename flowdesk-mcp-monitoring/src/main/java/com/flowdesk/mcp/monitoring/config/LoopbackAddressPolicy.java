package com.flowdesk.mcp.monitoring.config;

import java.util.List;
import java.util.Locale;

/**
 * 监听地址的「只认字面量」回环判定（FD-0015）。
 *
 * <p>监控 MCP 服务只应该在本机回环上监听：它不是给浏览器或外部网络用的服务。
 * 因此启动期校验要求 {@code server.address} 是一个<b>字面量</b>回环地址：</p>
 * <ul>
 *   <li>完整的四段十进制 IPv4 字面量，首段为 127（{@code 127.0.0.0/8}）；
 *       每段 1~3 位十进制数字、取值 {@code 0..255}、不接受前导零
 *       （{@code 0127.0.0.1} 在不同解析器里有八进制歧义）；</li>
 *   <li>IPv6 回环 {@code ::1} 及其完整写法 {@code 0:0:0:0:0:0:0:1}（允许零填充）。</li>
 * </ul>
 *
 * <p><b>刻意不接受主机名</b>（包括 {@code localhost}）：绑定地址必须是字面量，
 * 否则「到底绑定到哪」取决于 {@code hosts} 文件或 DNS，而那是可以被指向任何地方的。
 * 也<b>不</b>接受 {@code 0.0.0.0}、{@code ::}、具体外网地址或任何含糊写法。</p>
 *
 * <p>本类与 {@code RerankEndpointPolicy} 的判定思路一致（同样是「只认完整字面量、不做 DNS」），
 * 但两者位于不同模块、服务于不同对象（一个是监听地址，一个是出站 Endpoint），
 * 因此各自实现、各自被测试，不引入跨模块的共享工具 —— 本任务只改必要范围。</p>
 */
public final class LoopbackAddressPolicy {

    private static final int IPV4_ONLY_SEGMENTS = 4;

    private static final int IPV6_GROUP_COUNT = 8;

    private static final int IPV4_LOOPBACK_FIRST_OCTET = 127;

    private LoopbackAddressPolicy() {
    }

    /**
     * @param address 配置里的 {@code server.address} 原始文本
     * @return 是否为字面量回环地址
     */
    public static boolean isLoopbackLiteral(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        String normalized = address.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (normalized.indexOf(':') >= 0) {
            return isIpv6Loopback(normalized);
        }
        return isIpv4Loopback(normalized);
    }

    private static boolean isIpv4Loopback(String address) {
        String[] segments = address.split("\\.", -1);
        if (segments.length != IPV4_ONLY_SEGMENTS) {
            return false;
        }
        int[] octets = new int[IPV4_ONLY_SEGMENTS];
        for (int index = 0; index < IPV4_ONLY_SEGMENTS; index++) {
            Integer octet = parseDecimalOctet(segments[index]);
            if (octet == null) {
                return false;
            }
            octets[index] = octet;
        }
        return octets[0] == IPV4_LOOPBACK_FIRST_OCTET;
    }

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
            return null;
        }
        int value = Integer.parseInt(segment);
        return value <= 255 ? value : null;
    }

    private static boolean isIpv6Loopback(String address) {
        if (address.indexOf('.') >= 0) {
            return false;
        }
        int firstDoubleColon = address.indexOf("::");
        if (firstDoubleColon >= 0 && firstDoubleColon != address.lastIndexOf("::")) {
            return false;
        }

        List<String> groups;
        if (firstDoubleColon < 0) {
            groups = List.of(address.split(":", -1));
        }
        else {
            List<String> left = firstDoubleColon == 0
                    ? List.of()
                    : List.of(address.substring(0, firstDoubleColon).split(":", -1));
            String remainder = address.substring(firstDoubleColon + 2);
            List<String> right = remainder.isEmpty() ? List.of() : List.of(remainder.split(":", -1));
            int zeros = IPV6_GROUP_COUNT - left.size() - right.size();
            if (zeros < 1) {
                return false;
            }
            String[] expanded = new String[IPV6_GROUP_COUNT];
            int cursor = 0;
            for (String group : left) {
                expanded[cursor++] = group;
            }
            for (int index = 0; index < zeros; index++) {
                expanded[cursor++] = "0";
            }
            for (String group : right) {
                expanded[cursor++] = group;
            }
            groups = List.of(expanded);
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

    private static boolean isZeroGroup(String group) {
        return !group.isEmpty() && group.length() <= 4 && group.chars().allMatch(ch -> ch == '0');
    }

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
