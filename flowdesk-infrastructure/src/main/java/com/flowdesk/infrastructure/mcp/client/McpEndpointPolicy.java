package com.flowdesk.infrastructure.mcp.client;

import java.net.URI;
import java.util.Locale;

/**
 * MCP 端点校验（FD-0016）。
 *
 * <h2>规则</h2>
 * <p>本阶段只允许连接<b>本机回环上的明文 HTTP 服务</b>，而且必须写成完整字面量：</p>
 * <ul>
 *   <li>scheme 只能是 {@code http}（{@code https} 与其它 scheme 一律拒绝）；</li>
 *   <li>host 必须是<b>完整字面量回环地址</b>：四段十进制 IPv4（{@code 127.0.0.0/8}，
 *       每段 1~3 位、取值 {@code 0..255}、不接受前导零），或 IPv6 回环 {@code ::1}
 *       及其八组完整写法；</li>
 *   <li>必须显式给出端口，且取值 {@code 1..65535}；</li>
 *   <li><b>拒绝</b>主机名（含 {@code localhost}）、{@code 0.0.0.0}、非回环地址、
 *       userinfo（{@code user@host}）、query、fragment 与任何自定义路径。</li>
 * </ul>
 *
 * <p>判定只做<b>字面匹配、不做 DNS 解析</b>，因此不会有「名字被 hosts 文件或 DNS 指向别处」
 * 的问题；也刻意<b>不</b>用 {@code startsWith("127.")} 之类的宽松判断，
 * 那会把 {@code 127.0.0.1.evil.example} 放进来。</p>
 *
 * <p>这套判定与监控 MCP 服务用于 {@code server.address} 的 {@code LoopbackAddressPolicy}
 * 同源（同一批规则、同一个理由），但对象不同（那边是监听地址，这边是出站 URL），
 * 因此各自实现一份，由测试锁定（见 ADR 0013）。</p>
 *
 * <p>错误信息是固定文案：<b>不回显</b>完整端点或配置原值。</p>
 */
public final class McpEndpointPolicy {

    /** 本阶段固定的 MCP 路径。 */
    public static final String MCP_PATH = "/mcp";

    private static final int IPV4_SEGMENTS = 4;

    private static final int IPV6_GROUPS = 8;

    private static final int MAX_PORT = 65535;

    /** 固定错误文案（不含端点原值与主机名）。 */
    static final String MESSAGE = "flowdesk.mcp.client 的 base-url 只允许 http + 完整字面量回环地址，"
            + "必须显式带端口，且不能带 userinfo、query、fragment 或路径（例如 http://127.0.0.1:8091）";

    private McpEndpointPolicy() {
    }

    /**
     * 校验并规范化一个 base-url。
     *
     * @param baseUrl 配置里的原始文本（可以是 {@code null}）
     * @return 规范化后的 base uri（例如 {@code http://127.0.0.1:8091}）
     * @throws IllegalStateException 不符合上述任何一条规则
     */
    public static String requireLoopbackHttpBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(MESSAGE);
        }

        URI uri;
        try {
            uri = URI.create(baseUrl);
        }
        catch (IllegalArgumentException ex) {
            // URI.create 的异常消息会带上原值，因此这里不转发它
            throw new IllegalStateException(MESSAGE);
        }

        if (!"http".equalsIgnoreCase(uri.getScheme())
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || !isBasePath(uri.getPath())) {
            throw new IllegalStateException(MESSAGE);
        }

        int port = uri.getPort();
        if (port < 1 || port > MAX_PORT) {
            throw new IllegalStateException(MESSAGE);
        }

        String host = uri.getHost();
        if (host == null || !isLoopbackLiteral(host)) {
            throw new IllegalStateException(MESSAGE);
        }

        return "http://" + host.toLowerCase(Locale.ROOT) + ":" + port;
    }

    /**
     * @param path URI 路径
     * @return 是否为空或仅为 {@code /}（即「没有自定义路径」）
     */
    private static boolean isBasePath(String path) {
        return path == null || path.isEmpty() || "/".equals(path);
    }

    /**
     * @param host URI 解析出的主机（IPv6 会带方括号）
     * @return 是否为字面量回环地址
     */
    private static boolean isLoopbackLiteral(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (normalized.indexOf(':') >= 0) {
            return isIpv6Loopback(normalized);
        }
        return isIpv4Loopback(normalized);
    }

    private static boolean isIpv4Loopback(String host) {
        String[] segments = host.split("\\.", -1);
        if (segments.length != IPV4_SEGMENTS) {
            return false;
        }
        for (int index = 0; index < IPV4_SEGMENTS; index++) {
            Integer octet = parseDecimalOctet(segments[index]);
            if (octet == null) {
                return false;
            }
            if (index == 0 && octet != 127) {
                return false;
            }
        }
        return true;
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
        int doubleColon = address.indexOf("::");
        if (doubleColon >= 0 && doubleColon != address.lastIndexOf("::")) {
            return false;
        }

        String[] groups;
        if (doubleColon < 0) {
            groups = address.split(":", -1);
        }
        else {
            String[] left = doubleColon == 0 ? new String[0] : address.substring(0, doubleColon).split(":", -1);
            String remainder = address.substring(doubleColon + 2);
            String[] right = remainder.isEmpty() ? new String[0] : remainder.split(":", -1);
            if (left.length + right.length >= IPV6_GROUPS) {
                return false;
            }
            groups = new String[IPV6_GROUPS];
            System.arraycopy(left, 0, groups, 0, left.length);
            for (int index = left.length; index < IPV6_GROUPS - right.length; index++) {
                groups[index] = "0";
            }
            System.arraycopy(right, 0, groups, IPV6_GROUPS - right.length, right.length);
        }

        if (groups.length != IPV6_GROUPS) {
            return false;
        }
        for (int index = 0; index < IPV6_GROUPS - 1; index++) {
            String group = groups[index];
            if (group.isEmpty() || group.length() > 4 || !group.chars().allMatch(ch -> ch == '0')) {
                return false;
            }
        }
        String last = groups[IPV6_GROUPS - 1];
        if (last.isEmpty() || last.length() > 4) {
            return false;
        }
        for (int index = 0; index < last.length() - 1; index++) {
            if (last.charAt(index) != '0') {
                return false;
            }
        }
        return last.charAt(last.length() - 1) == '1';
    }
}
