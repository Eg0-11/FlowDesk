package com.flowdesk.mcp.asset.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowdesk.mcp.asset.directory.AssetDirectory;
import com.flowdesk.mcp.asset.directory.AssetId;
import com.flowdesk.mcp.asset.directory.AssetRecord;
import com.flowdesk.mcp.asset.directory.AssetSource;
import com.flowdesk.mcp.asset.directory.AssetSourceUnavailableException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * {@code asset_get} 工具：MCP 协议适配层（FD-0014）。
 *
 * <p>这一层只做三件事：<b>校验输入</b>、<b>调用资产目录端口</b>、<b>把结果映射成 MCP 工具结果</b>。
 * 它不认识数据从哪来（目录端口负责），也不认识 Streamable HTTP 或 Spring MVC
 * （MCP Server starter 负责）。</p>
 *
 * <h2>工具契约（对 MCP 客户端的实际契约）</h2>
 * <table border="1">
 *   <caption>asset_get 的输入与输出</caption>
 *   <tr><th>情形</th><th>{@code isError}</th><th>内容（单个 text content，JSON）</th></tr>
 *   <tr><td>命中</td><td>{@code false}</td>
 *       <td>{@code {"assetId":"AST-900001","assetType":"SERVER","status":"IN_SERVICE","source":"DEMO"}}</td></tr>
 *   <tr><td>合法但不存在</td><td>{@code false}</td>
 *       <td>{@code {"assetId":"AST-999999","found":false,"error":"ASSET_NOT_FOUND",...}}</td></tr>
 *   <tr><td>输入非法</td><td>{@code true}</td>
 *       <td>{@code {"error":"INVALID_ASSET_ID","message":"assetId 必须形如 AST-000001（AST- 加 6 位数字）"}}<br>
 *       入参必须<b>恰好</b>是「只含一个 {@code assetId} 字符串字段的 JSON 对象」：
 *       额外字段、非对象、缺失或非字符串一律按非法处理（见 {@link #rawAssetId(String)}）</td></tr>
 *   <tr><td>数据源不可用 / 内部异常</td><td>{@code true}</td>
 *       <td>{@code {"error":"ASSET_SOURCE_UNAVAILABLE","message":"资产数据源当前不可用"}}</td></tr>
 * </table>
 *
 * <p><b>为什么「未找到」是 {@code isError=false}</b>：{@code isError} 表达的是
 * 「这次<b>调用</b>有没有失败」，不是「查询结果好不好」。资产不存在是查询正常给出的答案
 * （与检索接口「无命中返回 200 空列表」同一条原则）；把它标成错误会让调用方以为工具坏了，
 * 从而去重试或降级。真正的执行失败（输入非法、数据源不可用）才置 {@code isError=true}。</p>
 *
 * <h2>不泄漏</h2>
 * <p>响应只有上面四种固定形状；异常消息、堆栈、类名、路径、配置与凭证都不进入响应。
 * 日志只记录 {@code operation}、稳定结果码、耗时与<b>异常类名</b>，
 * 不记录 <b>assetId 原始值</b>、异常消息与堆栈。</p>
 *
 * <h2>只读</h2>
 * <p>只有这一个工具、只有一个查询方法；{@link AssetDirectory} 端口本身也没有写方法。</p>
 */
public final class AssetGetTool implements ToolCallback {

    /** 工具名（客户端在 tools/list 里看到的唯一名字）。 */
    public static final String TOOL_NAME = "asset_get";

    /** 唯一入参名。 */
    public static final String ARGUMENT_ASSET_ID = "assetId";

    /** 工具描述（出现在 tools/list 里，供模型/调用方理解用途与边界）。 */
    public static final String TOOL_DESCRIPTION = """
            按资产标识查询一条只读资产记录。assetId 必须是 AST- 加 6 位数字（例如 AST-900001）。
            结果里的 source 字段标明这条记录来自哪里：DEMO 表示内置虚构演示数据，不是真实企业资产。
            本工具只读，不能新增、修改或删除资产；资产不存在时返回 ASSET_NOT_FOUND，
            数据源不可用时返回 ASSET_SOURCE_UNAVAILABLE。""";

    /**
     * 输入 schema：<b>恰好</b>一个 {@code assetId} 字符串字段，且必填（FD-0014 / FD-0014-R1）。
     *
     * <p>手写而不是由方法签名推导，是为了把三条写死：{@code required}、{@code additionalProperties=false}
     * 与 {@code pattern}/{@code maxLength}。schema 是<b>公布</b>给客户端的契约，
     * 因此它必须与 {@link #call(String)} 里的运行时校验一致：本工具<b>只</b>接受
     * 「只有一个 assetId 字段的 JSON 对象」，任何额外字段、非对象、缺失或非字符串的 assetId
     * 都在执行校验里被拒绝，而不是只写在 schema 里。</p>
     */
    public static final String INPUT_SCHEMA = "{\"type\":\"object\",\"properties\":{\"assetId\":{"
            + "\"type\":\"string\",\"description\":\"资产标识，格式为 AST- 加 6 位数字，例如 AST-900001\","
            + "\"pattern\":\"" + AssetId.SCHEMA_PATTERN + "\",\"maxLength\":" + AssetId.MAX_LENGTH + "}},"
            + "\"required\":[\"assetId\"],\"additionalProperties\":false}";

    private static final Logger log = LoggerFactory.getLogger(AssetGetTool.class);

    private static final String OPERATION = "asset.get";

    /**
     * 入参解析器（FD-0014-R1）。
     *
     * <p>打开 {@code FAIL_ON_TRAILING_TOKENS}：{@code {"assetId":"AST-900001"}} 后面再跟一段
     * JSON 不属于「只含一个 assetId 字段的 JSON 对象」，必须按非法处理，
     * 而不是默默只看第一段。默认关闭该特性会让这种输入被当成合法。</p>
     */
    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final AssetDirectory directory;

    /**
     * @param directory 资产目录查询端口
     */
    public AssetGetTool(AssetDirectory directory) {
        this.directory = directory;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description(TOOL_DESCRIPTION)
                .inputSchema(INPUT_SCHEMA)
                .build();
    }

    @Override
    public String call(String toolInput) {
        long startedAt = System.nanoTime();
        String rawAssetId = rawAssetId(toolInput);

        if (!AssetId.isValid(rawAssetId)) {
            logOutcome(AssetToolError.INVALID_ASSET_ID.name(), startedAt);
            throw new AssetToolException(AssetToolError.INVALID_ASSET_ID);
        }

        Optional<AssetRecord> found;
        AssetSource source;
        try {
            found = this.directory.findById(rawAssetId);
            // 命中时来源来自记录本身；未命中时问目录「你是哪个来源」——
            // 这样「未找到」也带血缘（演示目录说没有 ≠ 真实资产系统说没有）
            source = found.isPresent() ? found.get().source() : this.directory.source();
        }
        catch (AssetSourceUnavailableException ex) {
            logFailure(AssetToolError.ASSET_SOURCE_UNAVAILABLE, ex, startedAt);
            throw new AssetToolException(AssetToolError.ASSET_SOURCE_UNAVAILABLE);
        }
        catch (RuntimeException ex) {
            // 目录实现的任何其它运行期异常同样收敛为「数据源不可用」：
            // 具体原因属于服务端内部问题，既不进响应也不进日志正文
            logFailure(AssetToolError.ASSET_SOURCE_UNAVAILABLE, ex, startedAt);
            throw new AssetToolException(AssetToolError.ASSET_SOURCE_UNAVAILABLE);
        }

        if (found == null || found.isEmpty()) {
            logOutcome("ASSET_NOT_FOUND", startedAt);
            return notFound(rawAssetId, source);
        }
        logOutcome("OK", startedAt);
        return hit(found.get());
    }

    /**
     * 从工具入参 JSON 里取 {@code assetId}，并<b>按公布的 schema 校验入参形状</b>。
     *
     * <p>只接受「<b>恰好</b>一个 {@code assetId} 字段的 JSON 对象」：</p>
     * <ul>
     *   <li>不是 JSON 对象（数组、字符串、数字、布尔、{@code null}）→ 非法；</li>
     *   <li>字段数不是 1，或那一个字段不叫 {@code assetId}（含<b>任何额外字段</b>）→ 非法；</li>
     *   <li>{@code assetId} 不是字符串（缺失、{@code null}、数字、对象…）→ 非法；</li>
     *   <li>不是合法 JSON → 非法。</li>
     * </ul>
     *
     * <p>与 schema 的一致性由两处共同保证：{@code additionalProperties=false} / {@code required}
     * 描述的是这里的形状判定，{@code pattern} / {@code maxLength} 描述的是 {@link AssetId#isValid(String)}。
     * <b>只改 schema 不改执行校验是不允许的</b> —— 客户端可以不看 schema 直接发请求，
     * 因此执行校验才是真正的边界。非法输入一律返回固定的 {@code INVALID_ASSET_ID} 内容，
     * 不回显输入、不说明是哪一个字段错了。</p>
     *
     * @param toolInput 工具入参 JSON（可能为 {@code null}、不是 JSON 或不满足 schema）
     * @return 合法的 assetId；任何不满足上述形状的输入都返回 {@code null}
     */
    private static String rawAssetId(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(toolInput);
            if (root == null || !root.isObject()) {
                return null;
            }
            if (root.size() != 1 || !root.has(ARGUMENT_ASSET_ID)) {
                // 额外字段（或字段名不对）同样拒绝：schema 里 additionalProperties=false
                return null;
            }
            JsonNode value = root.get(ARGUMENT_ASSET_ID);
            return value != null && value.isTextual() ? value.asText() : null;
        }
        catch (JsonProcessingException ex) {
            // 入参不是合法 JSON：同样属于「输入非法」，不把解析细节带出去
            return null;
        }
    }

    private static String hit(AssetRecord record) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(ARGUMENT_ASSET_ID, record.assetId());
        payload.put("assetType", record.assetType());
        payload.put("status", record.status());
        payload.put("source", record.source().name());
        return json(payload);
    }

    private static String notFound(String assetId, AssetSource source) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(ARGUMENT_ASSET_ID, assetId);
        payload.put("found", Boolean.FALSE);
        payload.put("error", "ASSET_NOT_FOUND");
        payload.put("message", "未找到该资产");
        payload.put("source", source == null ? null : source.name());
        return json(payload);
    }

    private static String json(Map<String, Object> payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        }
        catch (JsonProcessingException ex) {
            // 固定字段 + 字符串/布尔值的组合不可能序列化失败；这属于编码缺陷，不是运行期输入问题
            throw new IllegalStateException("资产工具结果无法序列化", ex);
        }
    }

    private static void logOutcome(String outcome, long startedAt) {
        log.info("{} completed operation={} result={} durationMs={}", OPERATION, OPERATION, outcome,
                elapsedMillis(startedAt));
    }

    private static void logFailure(AssetToolError failure, RuntimeException cause, long startedAt) {
        // 只记稳定错误码与异常类名：不记 assetId、异常消息与堆栈
        log.warn("{} failed operation={} result={} exception={} durationMs={}", OPERATION, OPERATION,
                failure.name(), cause == null ? "none" : cause.getClass().getName(), elapsedMillis(startedAt));
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }
}
