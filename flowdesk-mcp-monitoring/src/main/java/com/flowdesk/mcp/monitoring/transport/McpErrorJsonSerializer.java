package com.flowdesk.mcp.monitoring.transport;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;

/**
 * 让 {@link McpError} 永远不按 {@code Throwable} 序列化（FD-0015；等价基线见 FD-0014-R2）。
 *
 * <p>MCP SDK 0.17.0 的传输层在多条错误路径上执行
 * {@code ServerResponse.badRequest().body(new McpError(...))}，而 {@code McpError}
 * 继承自 {@link RuntimeException}：Jackson 会走 {@code Throwable} 序列化器，把
 * {@code stackTrace}（含类名、文件名与行号）、{@code cause}、{@code suppressed}
 * 以及原始异常消息一起写进响应体。有两条路径甚至直接传 {@code ex.getMessage()}。</p>
 *
 * <p>本序列化器把这类响应体固定成 {@code {"code":…,"message":…}}：错误码沿用 SDK 给出的
 * JSON-RPC 错误码（没有就给 {@code -32600}），文案只取**固定枚举**，从不转发 SDK 或异常自己的
 * 消息。也就是说：错误响应仍然存在、仍然是可解析的 JSON，只是不再夹带服务端内部信息
 * —— 细节留在服务端日志里。</p>
 *
 * <p>它是**兜底**：能提前拦下的请求（畸形报文、未实现的方法、非对象 {@code arguments}）
 * 由 {@link McpRequestGateFilter} 用更精确的固定错误回答，这里的规则只负责那些
 * 仍然会走到 SDK 错误路径的情形（例如缺少会话标识、Accept 头不合法、SDK 内部异常）。</p>
 */
public final class McpErrorJsonSerializer extends JsonSerializer<McpError> {

    /** 固定文案：报文不是合法 JSON。 */
    private static final String PARSE_ERROR_MESSAGE = "Parse error";

    /** 固定文案：不是合法的 JSON-RPC 请求。 */
    private static final String INVALID_REQUEST_MESSAGE = "Invalid request";

    /** 固定文案：方法未实现。 */
    private static final String METHOD_NOT_FOUND_MESSAGE = "Method not found";

    /** 固定文案：参数不合法。 */
    private static final String INVALID_PARAMS_MESSAGE = "Invalid params";

    /** 固定文案：服务端内部错误。 */
    private static final String INTERNAL_ERROR_MESSAGE = "Internal error";

    @Override
    public void serialize(McpError value, JsonGenerator generator, SerializerProvider serializers)
            throws IOException {

        int code = codeOf(value);

        generator.writeStartObject();
        generator.writeNumberField("code", code);
        generator.writeStringField("message", messageOf(code));
        generator.writeEndObject();
    }

    /**
     * 取 SDK 给出的 JSON-RPC 错误码；没有就给 {@code -32600}（无效请求）。
     *
     * @param error SDK 异常
     * @return 错误码
     */
    private static int codeOf(McpError error) {
        McpSchema.JSONRPCResponse.JSONRPCError jsonRpcError = error.getJsonRpcError();
        return jsonRpcError != null && jsonRpcError.code() != null
                ? jsonRpcError.code()
                : McpProtocolError.INVALID_REQUEST;
    }

    /**
     * 错误码对应的固定文案。
     *
     * @param code 错误码
     * @return 固定文案
     */
    static String messageOf(int code) {
        return switch (code) {
            case McpProtocolError.PARSE_ERROR -> PARSE_ERROR_MESSAGE;
            case McpProtocolError.METHOD_NOT_FOUND -> METHOD_NOT_FOUND_MESSAGE;
            case McpProtocolError.INVALID_PARAMS -> INVALID_PARAMS_MESSAGE;
            case McpProtocolError.INTERNAL_ERROR -> INTERNAL_ERROR_MESSAGE;
            default -> INVALID_REQUEST_MESSAGE;
        };
    }

    /**
     * 装配用的模块（只注册这一条规则，不动其它序列化行为）。
     *
     * @return Jackson 模块
     */
    public static SimpleModule module() {
        SimpleModule module = new SimpleModule("flowdesk-mcp-error-serialization");
        module.addSerializer(McpError.class, new McpErrorJsonSerializer());
        return module;
    }
}
