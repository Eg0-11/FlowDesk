package com.flowdesk.mcp.asset.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP 传输层的固定协议错误（FD-0014-R2）。
 *
 * <p>这里定义的是**对外契约**：错误码与文案都是常量，任何情况下都不拼接异常消息、类名、路径、
 * 配置或客户端输入（唯一例外是 {@link #methodNotFoundBody}，它会回显一个**形状受限**的方法名，
 * 见下）。这样做不是「把错误藏起来」，而是把错误表达成**可解析、稳定、可断言**的 JSON-RPC 错误：
 * 客户端拿到的仍然是标准错误码（{@code -32700}/{@code -32600}/{@code -32601}/{@code -32602}），
 * 只是不再附带服务端内部信息。</p>
 *
 * <p>MCP SDK 0.17.0 的传输层在若干路径上把 {@code McpError}（一个 {@code RuntimeException}）
 * 直接当作 HTTP 响应体返回，Jackson 会按 {@code Throwable} 序列化，于是响应里出现
 * {@code stackTrace}、SDK 与业务类名、行号；另一些路径干脆转发原始异常消息。
 * 本类与 {@link McpErrorJsonSerializer} 一起把这两条出口收口。</p>
 */
public final class McpProtocolError {

    /** JSON-RPC：报文不是合法 JSON。 */
    public static final int PARSE_ERROR = -32700;

    /** JSON-RPC：报文合法 JSON，但不是合法的 JSON-RPC 请求。 */
    public static final int INVALID_REQUEST = -32600;

    /** JSON-RPC：方法未实现。 */
    public static final int METHOD_NOT_FOUND = -32601;

    /** JSON-RPC：参数不合法。 */
    public static final int INVALID_PARAMS = -32602;

    /** JSON-RPC：服务端内部错误。 */
    public static final int INTERNAL_ERROR = -32603;

    /** 固定文案：报文不是合法 JSON。 */
    public static final String PARSE_ERROR_MESSAGE = "Parse error";

    /** 固定文案：不是合法的 JSON-RPC 请求对象。 */
    public static final String INVALID_REQUEST_MESSAGE = "Invalid request";

    /** 固定文案：请求体过大。 */
    public static final String REQUEST_TOO_LARGE_MESSAGE = "Request body too large";

    /** 固定文案：方法未实现。 */
    public static final String METHOD_NOT_FOUND_MESSAGE = "Method not found";

    /** 固定文案：{@code tools/call} 的 {@code params} 不合法。 */
    public static final String INVALID_PARAMS_MESSAGE = "Invalid params";

    /** 固定文案：{@code tools/call} 的 {@code arguments} 不是 JSON 对象。 */
    public static final String ARGUMENTS_NOT_OBJECT_MESSAGE = "Invalid params: arguments must be a JSON object";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpProtocolError() {
    }

    /**
     * 生成 JSON-RPC 错误响应体。
     *
     * @param id      JSON-RPC 标识（没有就传 {@code null}）
     * @param code    错误码
     * @param message 固定文案
     * @return JSON 文本
     */
    public static String body(JsonNode id, int code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", "2.0");
        payload.put("id", jsonRpcId(id));
        payload.put("error", error);

        try {
            return MAPPER.writeValueAsString(payload);
        }
        catch (JsonProcessingException ex) {
            // 固定结构不可能序列化失败：这属于编码缺陷，不是运行期输入问题
            throw new IllegalStateException("MCP 协议错误体无法序列化", ex);
        }
    }

    /**
     * 方法未找到的响应体：只在方法名形状受限时回显它。
     *
     * <p>回显方法名是为了让错误「明确」，但它终究是客户端输入，因此只接受
     * {@code [A-Za-z0-9_./-]{1,64}} 这一形状；否则退回固定文案，避免把任意字符串写回响应。</p>
     *
     * @param id     JSON-RPC 标识
     * @param method 客户端送来的方法名
     * @return JSON 文本
     */
    public static String methodNotFoundBody(JsonNode id, String method) {
        if (method != null && method.matches("[A-Za-z0-9_./-]{1,64}")) {
            return body(id, METHOD_NOT_FOUND, METHOD_NOT_FOUND_MESSAGE + ": " + method);
        }
        return body(id, METHOD_NOT_FOUND, METHOD_NOT_FOUND_MESSAGE);
    }

    /**
     * 取 JSON-RPC 标识：只允许字符串、数字与 {@code null}（规范允许的三种），其余一律当作 {@code null}。
     *
     * @param id 原始标识节点
     * @return 可直接序列化的标识
     */
    private static Object jsonRpcId(JsonNode id) {
        if (id == null || id.isNull()) {
            return null;
        }
        if (id.isTextual()) {
            return id.asText();
        }
        if (id.isNumber()) {
            return id.numberValue();
        }
        return null;
    }
}
