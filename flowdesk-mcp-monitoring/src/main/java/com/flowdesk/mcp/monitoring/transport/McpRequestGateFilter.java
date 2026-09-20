package com.flowdesk.mcp.monitoring.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.HttpHeaders;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * MCP 传输层入口闸门（FD-0015；等价基线见 FD-0014-R2）。
 *
 * <p>它只做一件事：在请求进入 Spring AI / MCP SDK 的传输实现之前，把三类已知会产生
 * 「不安全响应」或「永不结束的响应流」的请求拦下来，用**固定、可解析的 JSON-RPC 错误**回答。
 * 其余请求原样放行（请求体通过包装器完整重放，传输层读到的是一个字都不差的原文）。</p>
 *
 * <h2>为什么需要它（实测的 SDK 0.17.0 行为）</h2>
 * <table border="1">
 *   <caption>修复前的实测结果</caption>
 *   <tr><th>请求</th><th>修复前</th></tr>
 *   <tr><td>报文不是合法 JSON / 顶层不是对象 / 缺 {@code method}</td>
 *       <td>HTTP 400，响应体是 {@code McpError}（{@code RuntimeException}）的 Throwable 序列化：
 *           含 {@code stackTrace}、SDK 与业务类名、文件名与行号</td></tr>
 *   <tr><td>{@code tools/call} 的 {@code arguments} 不是对象</td>
 *       <td>HTTP 500、响应体为空、{@code Content-Type: text/event-stream}</td></tr>
 *   <tr><td>未实现的方法（{@code resources/list}、{@code prompts/list}、任意未知方法）</td>
 *       <td>HTTP 200，但错误只写进 {@code text/event-stream} 且**流永不结束**：服务端一直挂着
 *           一个活跃请求，Tomcat 优雅关停要等它，测试 JVM 因此在 30 秒后被 Surefire 强杀</td></tr>
 * </table>
 *
 * <h2>闸门规则</h2>
 * <ol>
 *   <li><b>只拦 POST</b>：GET（服务端事件流）与 DELETE（会话清理）原样放行；</li>
 *   <li>请求体不是合法 JSON → {@code 400} + {@code -32700 Parse error}；</li>
 *   <li>合法 JSON 但不是 JSON-RPC 请求对象（数组、裸字符串、缺 {@code jsonrpc}/{@code method}）
 *       → {@code 400} + {@code -32600 Invalid request}；
 *       第 2、3 条<b>不要求会话</b>：传输层自己也是在会话校验<b>之前</b>解析报文的
 *       （实测：畸形报文 + 无会话标识得到的是解析失败，而不是「缺少会话」）；</li>
 *   <li>{@code initialize} → 原样放行：握手不要求会话，协议版本在会话里协商；</li>
 *   <li><b>会话校验（FD-0015；等价基线见 FD-0014-R3）</b>：其余请求必须带上<b>活跃</b>的 {@code Mcp-Session-Id}。
 *       没有该头、值为空、伪造、或会话已被 {@code DELETE} 结束 —— 一律<b>原样放行</b>，
 *       让传输层按它自己的语义回答 {@code 400} / {@code 404}（这些响应同样已脱敏）。
 *       「会话标识非空」不算有效：必须真的在传输层的会话表里；</li>
 *   <li>通知（没有 {@code id}）原样放行 —— 包括 {@code notifications/initialized}，
 *       握手语义不能被闸门吞掉；实测未知通知由传输层回 202，流本来就是有界的；</li>
 *   <li><b>协议版本校验（FD-0015；等价基线见 FD-0014-R3）</b>：会话有效之后，请求头
 *       {@code MCP-Protocol-Version} 必须是传输层公布的受支持版本之一（缺省视为受支持，
 *       与传输层的行为一致）。不受支持 → {@code 400} + 固定 {@code Unsupported protocol version}。
 *       传输层自己不校验这个头（实测：带一个不存在的版本仍然照常处理），但规范要求 400；
 *       而且只有在这里拒绝，未实现的方法才不会退化成一条永不结束的流；</li>
 *   <li>带 {@code id} 的请求且方法不在实现清单
 *       （{@code initialize}、{@code ping}、{@code tools/list}、{@code tools/call}、{@code logging/setLevel}）
 *       → {@code 200} + {@code -32601 Method not found}，**普通 JSON 响应**，因此流立即结束；</li>
 *   <li>{@code tools/call} 的 {@code params} 必须是对象、{@code name} 必须是字符串、
 *       {@code arguments} 必须**缺省或为对象** → 否则 {@code 200} + {@code -32602 Invalid params}，
 *       工具与监控数据源都不会被调用；</li>
 *   <li>请求体超过 {@value #MAX_BODY_BYTES} 字节 → {@code 413} + 固定错误：本服务的入参只有一个
 *       短标识，不存在合法的大请求体，而读不完的报文无法判定，只能拒绝
 *       （与容器自身的体积上限同类，因此不依赖会话）。</li>
 * </ol>
 *
 * <p><b>为什么不在 SDK 里修</b>：这三处都在 MCP Java SDK 0.17.0 的传输实现内部
 * （{@code WebMvcStreamableServerTransportProvider}），Spring AI 1.1.2 没有对应开关，
 * 唯一「修源」的办法是升级依赖或改包名劫持类 —— 前者被本任务明确禁止，后者会让依赖升级变得危险。
 * 因此选择在**我们自己的入口**上收口，并把 SDK 缺陷、最小复现与替代方案写进 ADR 0011。
 * 闸门不丢弃错误：它给出的仍是标准 JSON-RPC 错误，只是安全且流有界。</p>
 */
public final class McpRequestGateFilter extends OncePerRequestFilter {

    /** 请求体上限（字节）。合法请求只含一个短标识，1 MiB 已是极大的余量。 */
    public static final int MAX_BODY_BYTES = 1024 * 1024;

    /** 本服务**真正实现了**的 JSON-RPC 请求方法；其余一律 {@code -32601}。 */
    public static final Set<String> IMPLEMENTED_REQUEST_METHODS = Set.of(
            "initialize", "ping", "tools/list", "tools/call", "logging/setLevel");

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final McpTransportState transportState;

    /**
     * @param transportState 传输层状态查询（会话活跃性与受支持协议版本）
     */
    public McpRequestGateFilter(McpTransportState transportState) {
        this.transportState = transportState;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        byte[] body = readBounded(request);
        if (body == null) {
            writeBody(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                    McpProtocolError.body(null, McpProtocolError.INVALID_REQUEST,
                            McpProtocolError.REQUEST_TOO_LARGE_MESSAGE));
            return;
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        }
        catch (JsonProcessingException ex) {
            // 只回固定文案：不记也不回传报文内容与异常消息
            writeBody(response, HttpServletResponse.SC_BAD_REQUEST,
                    McpProtocolError.body(null, McpProtocolError.PARSE_ERROR,
                            McpProtocolError.PARSE_ERROR_MESSAGE));
            return;
        }

        if (root == null || !root.isObject()
                || !"2.0".equals(textOf(root.get("jsonrpc")))
                || textOf(root.get("method")) == null) {

            writeBody(response, HttpServletResponse.SC_BAD_REQUEST,
                    McpProtocolError.body(idOf(root), McpProtocolError.INVALID_REQUEST,
                            McpProtocolError.INVALID_REQUEST_MESSAGE));
            return;
        }

        String method = textOf(root.get("method"));

        // 握手不要求会话（那是建立会话的请求），也不在这里校验协议版本：
        // 客户端在 initialize 里请求一个版本，服务端在会话里协商，响应里给出最终版本
        if ("initialize".equals(method)) {
            chain.doFilter(replay(request, body), response);
            return;
        }

        // FD-0015（等价基线 FD-0014-R3）：提前回答的前提是「传输层本来也会接受这个请求」。
        // 非 initialize 的请求必须先证明会话真的活跃 —— 缺失、伪造、已删除（含空串）一律放行，
        // 让传输层按它自己的语义给出 400 / 404（这些响应同样已被脱敏）。
        if (this.transportState.sessionState(request.getHeader(HttpHeaders.MCP_SESSION_ID))
                != McpTransportState.SessionState.LIVE) {

            chain.doFilter(replay(request, body), response);
            return;
        }

        // FD-0015（等价基线 FD-0014-R3/R4）：会话有效之后、**请求/通知分流之前**校验协议版本。
        // 传输层自己不校验这个头（实测会把不支持的版本照常处理），而规范要求对不受支持的版本回 400；
        // 放在分流之前意味着合法请求与通知遵守同一条规则（通知也回 400，错误体 id 为 null）。
        // 缺失版本头继续兼容（返回 null → 视为受支持）；存在但为空白的值按无效版本处理。
        if (!this.transportState.supportsProtocolVersion(protocolVersionOf(request))) {
            writeBody(response, HttpServletResponse.SC_BAD_REQUEST,
                    McpProtocolError.body(idOf(root), McpProtocolError.INVALID_REQUEST,
                            McpProtocolError.UNSUPPORTED_PROTOCOL_VERSION_MESSAGE));
            return;
        }

        // 通知没有任何响应语义：原样交给传输层（它回 202，流是有界的）
        if (!root.has("id")) {
            chain.doFilter(replay(request, body), response);
            return;
        }

        if (!IMPLEMENTED_REQUEST_METHODS.contains(method)) {
            writeBody(response, HttpServletResponse.SC_OK,
                    McpProtocolError.methodNotFoundBody(idOf(root), method));
            return;
        }

        String invalidParams = invalidParamsOf(method, root.get("params"));
        if (invalidParams != null) {
            writeBody(response, HttpServletResponse.SC_OK,
                    McpProtocolError.body(idOf(root), McpProtocolError.INVALID_PARAMS, invalidParams));
            return;
        }

        chain.doFilter(replay(request, body), response);
    }

    /**
     * {@code tools/call} 的参数形状检查。
     *
     * @param method 方法名
     * @param params 参数节点
     * @return 不合法时的固定文案；合法时为 {@code null}
     */
    private static String invalidParamsOf(String method, JsonNode params) {
        if (!"tools/call".equals(method)) {
            return null;
        }
        if (params == null || !params.isObject()) {
            return McpProtocolError.INVALID_PARAMS_MESSAGE;
        }
        JsonNode name = params.get("name");
        if (name == null || !name.isTextual() || name.asText().isBlank()) {
            return McpProtocolError.INVALID_PARAMS_MESSAGE;
        }
        JsonNode arguments = params.get("arguments");
        if (arguments != null && !arguments.isObject()) {
            // 缺省允许（无参工具），但显式给成字符串/数组/数字/null 一律拒绝：
            // 它们到不了工具层，而传输层此前会以 500 空响应收场
            return McpProtocolError.ARGUMENTS_NOT_OBJECT_MESSAGE;
        }
        return null;
    }

    /**
     * 取协议版本头，并区分「缺失」与「存在但为空」。
     *
     * <p>Servlet API 的 {@code getHeader} 对这两种情况都返回 {@code null} 或空串，无法区分，
     * 因此这里读头的所有取值：一个都没有 → 缺失（{@code null}，兼容现有客户端）；
     * 有取值 → 取第一个（可能是空串或纯空白，按无效版本处理）。</p>
     *
     * @param request 请求
     * @return 版本头的值；缺失时为 {@code null}
     */
    private static String protocolVersionOf(HttpServletRequest request) {
        List<String> values = Collections.list(request.getHeaders(HttpHeaders.PROTOCOL_VERSION));
        return values.isEmpty() ? null : values.get(0);
    }

    /**
     * 读取请求体；超过上限返回 {@code null}。
     *
     * @param request 请求
     * @return 请求体字节
     * @throws IOException 读取失败
     */
    private static byte[] readBounded(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            return null;
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        try (ServletInputStream input = request.getInputStream()) {
            int read;
            while ((read = input.read(chunk)) != -1) {
                if (buffer.size() + read > MAX_BODY_BYTES) {
                    return null;
                }
                buffer.write(chunk, 0, read);
            }
        }
        return buffer.toByteArray();
    }

    /**
     * 把已经读掉的请求体原样「放回去」。
     *
     * @param request 原请求
     * @param body    已读取的请求体
     * @return 可重复读取的实现
     */
    private static HttpServletRequest replay(HttpServletRequest request, byte[] body) {
        return new HttpServletRequestWrapper(request) {

            @Override
            public ServletInputStream getInputStream() {
                ByteArrayInputStream source = new ByteArrayInputStream(body);
                return new ServletInputStream() {

                    @Override
                    public int read() {
                        return source.read();
                    }

                    @Override
                    public int read(byte[] target, int offset, int length) {
                        return source.read(target, offset, length);
                    }

                    @Override
                    public boolean isFinished() {
                        return source.available() == 0;
                    }

                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    @Override
                    public void setReadListener(ReadListener listener) {
                        throw new UnsupportedOperationException("闸门不支持异步读取");
                    }
                };
            }

            @Override
            public BufferedReader getReader() {
                Charset charset = request.getCharacterEncoding() == null
                        ? StandardCharsets.UTF_8
                        : Charset.forName(request.getCharacterEncoding());
                return new BufferedReader(new InputStreamReader(getInputStream(), charset));
            }
        };
    }

    /**
     * 写出固定错误响应。
     *
     * @param response 响应
     * @param status   HTTP 状态码
     * @param json     已经确定的固定响应体
     * @throws IOException 写出失败
     */
    private static void writeBody(HttpServletResponse response, int status, String json) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(json);
        response.getWriter().flush();
    }

    /**
     * @param node JSON 节点
     * @return 文本值；不是文本时为 {@code null}
     */
    private static String textOf(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    /**
     * @param root 报文体
     * @return JSON-RPC 标识节点；不是字符串/数字时为 {@code null}
     */
    private static JsonNode idOf(JsonNode root) {
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode id = root.get("id");
        if (id == null || id.isNull() || (!id.isTextual() && !id.isNumber())) {
            return null;
        }
        return id;
    }
}
