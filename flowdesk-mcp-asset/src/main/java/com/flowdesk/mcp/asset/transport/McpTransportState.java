package com.flowdesk.mcp.asset.transport;

import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 传输层状态查询（FD-0014-R3）。
 *
 * <p>入口闸门要在**请求进入传输实现之前**决定「能不能自己回答这个请求」。为此它必须能回答两个
 * 传输层才会回答的问题，而且答案必须与传输层一致：</p>
 * <ol>
 *   <li><b>这个会话标识是不是一个活跃会话？</b> 传输层对非 {@code initialize} 的 POST 要求
 *       {@code Mcp-Session-Id}：缺失 → 400，会话表里查不到（伪造或已被 {@code DELETE} 结束）→ 404。
 *       所以「非空」远远不够 —— 只有会话真的在会话表里，闸门才敢替它回答；</li>
 *   <li><b>这个 {@code MCP-Protocol-Version} 受支持吗？</b> 支持的版本列表由传输层自己公布
 *       （{@link McpStreamableServerTransportProvider#protocolVersions()}），闸门不另外维护一份。</li>
 * </ol>
 *
 * <p>会话表在 MCP Java SDK 0.17.0 里是传输实现（{@code WebMvcStreamableServerTransportProvider}）
 * 的私有字段，接口上没有查询方法，因此这里用只读反射读取它。反射失败（SDK 升级后字段改名等）
 * <b>不会</b>让服务出错，也不会让闸门「猜」：那时它会返回
 * {@link SessionState#INDETERMINATE}，闸门据此一律放行，由传输层按自己的语义回答。</p>
 */
public final class McpTransportState {

    private static final Logger log = LoggerFactory.getLogger(McpTransportState.class);

    private final McpStreamableServerTransportProvider transport;

    /** SDK 传输实现的会话表私有字段；不可用时为 {@code null}。 */
    private final Field sessionsField;

    /**
     * @param transport MCP 传输实现（Spring AI 自动装配的 Streamable HTTP provider）
     */
    public McpTransportState(McpStreamableServerTransportProvider transport) {
        this.transport = transport;
        this.sessionsField = findSessionsField(transport.getClass());
        if (this.sessionsField == null) {
            log.warn("读不到 MCP 会话表（SDK 内部结构可能已变化）：闸门不再提前回答请求，全部交给传输层");
        }
    }

    /**
     * 会话状态。
     *
     * @param sessionId 请求头里的会话标识
     * @return 状态
     */
    public SessionState sessionState(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return SessionState.ABSENT;
        }
        if (this.sessionsField == null) {
            return SessionState.INDETERMINATE;
        }
        try {
            if (this.sessionsField.get(this.transport) instanceof Map<?, ?> sessions) {
                return sessions.containsKey(sessionId) ? SessionState.LIVE : SessionState.ABSENT;
            }
            return SessionState.INDETERMINATE;
        }
        catch (ReflectiveOperationException | RuntimeException ex) {
            // 只记异常类名：不记会话标识，也不记堆栈
            log.warn("读取 MCP 会话表失败：{}", ex.getClass().getName());
            return SessionState.INDETERMINATE;
        }
    }

    /**
     * 协议版本是否受支持。
     *
     * <p>缺省（请求头不存在或为空）视为受支持：MCP Java SDK 0.17.0 的服务端根本不校验这个头
     * （实测：带一个不受支持的版本仍然照常处理），因此把「缺省」判成不支持会凭空拒绝正常客户端。
     * 传输层没有公布版本列表时同样放行（不拿我们自己的猜测替代它的答案）。</p>
     *
     * @param protocolVersion 请求头 {@code MCP-Protocol-Version} 的值
     * @return 是否受支持
     */
    public boolean supportsProtocolVersion(String protocolVersion) {
        if (protocolVersion == null || protocolVersion.isBlank()) {
            return true;
        }
        List<String> supported = this.transport.protocolVersions();
        if (supported == null || supported.isEmpty()) {
            return true;
        }
        return supported.contains(protocolVersion);
    }

    /**
     * 传输层公布的受支持协议版本（测试与文档都以此为准）。
     *
     * @return 版本列表
     */
    public List<String> supportedProtocolVersions() {
        List<String> supported = this.transport.protocolVersions();
        return supported == null ? List.of() : List.copyOf(supported);
    }

    /**
     * 在类层次里找会话表字段：类型是 {@code Map}，名字里含 {@code session}。
     *
     * @param type 传输实现类型
     * @return 可读字段；找不到时为 {@code null}
     */
    private static Field findSessionsField(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                boolean looksLikeSessions = Map.class.isAssignableFrom(field.getType())
                        && field.getName().toLowerCase(Locale.ROOT).contains("session");
                if (looksLikeSessions && field.trySetAccessible()) {
                    return field;
                }
            }
        }
        return null;
    }

    /** 会话状态：与传输层一致的三态。 */
    public enum SessionState {

        /** 会话表里有这个会话：闸门可以按传输层之后的语义替它回答。 */
        LIVE,

        /** 没有会话标识，或会话表里查不到（伪造、已删除、空串）。 */
        ABSENT,

        /** 读不到会话表（SDK 内部结构变化）：调用方必须放行。 */
        INDETERMINATE
    }
}
