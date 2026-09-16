package com.flowdesk.infrastructure.knowledge.rerank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.application.knowledge.port.out.KnowledgeRerankPort;
import com.flowdesk.application.knowledge.port.out.KnowledgeRerankResult;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DashScope 文本重排适配器（RAG 6/6）：qwen3-rerank 的扁平 HTTP 协议。
 *
 * <h2>协议</h2>
 * <pre>
 * POST {endpoint}
 * Authorization: Bearer &lt;DASHSCOPE_API_KEY&gt;
 * Content-Type: application/json
 *
 * {"model":"qwen3-rerank","query":"…","documents":["…","…"]}
 * </pre>
 * <p>响应（成功）里 {@code results} <b>直接位于顶层</b>，每项含
 * {@code index}（候选在本次输入列表中的原始下标）与 {@code relevance_score}（{@code 0..1}）。</p>
 *
 * <p>刻意<b>不</b>发送 {@code top_n}：本阶段要用重排给<b>全部</b>候选打分并整体重排，
 * 而 {@code top_n} 会让上游只返回前 N 条。也不发送 {@code instruct}、{@code return_documents}：
 * 前者会改变排序任务语义（本阶段要的是官方默认的问答检索策略），后者会浪费带宽。</p>
 *
 * <p>请求体里<b>没有</b>文档标识、版本、切片摘要、向量或任何数据库信息：重排只需要
 * 「问题 + 候选正文」，多发的字段既没用处，也会把内部标识暴露给上游。</p>
 *
 * <h2>失败分类</h2>
 * <table border="1">
 *   <caption>适配器的失败映射</caption>
 *   <tr><th>情形</th><th>错误码</th><th>HTTP</th></tr>
 *   <tr><td>连接失败、超时、被中断</td><td>{@code RERANK_PROVIDER_ERROR}</td><td>502</td></tr>
 *   <tr><td>上游非 200（限流 429、5xx、鉴权失败…）</td><td>{@code RERANK_PROVIDER_ERROR}</td><td>502</td></tr>
 *   <tr><td>响应不是合法 JSON、缺少 {@code results} 数组、元素不是对象、
 *       或 {@code index}/{@code relevance_score} 存在但不是数字</td>
 *       <td>{@code KNOWLEDGE_RETRIEVAL_FAILURE}</td><td>500</td></tr>
 *   <tr><td>{@code index} 是小数、指数形式、字符串、布尔值，或超出 {@code int} 范围
 *       （FD-0013-R1：不能用 {@code intValue()} 截断/溢出）</td>
 *       <td>{@code KNOWLEDGE_RETRIEVAL_FAILURE}</td><td>500</td></tr>
 * </table>
 * <p>「字段缺失」（而不是类型不对）不在适配器里判定：适配器把缺失表示为 {@code null}，
 * 由应用层的重排结果契约统一拒绝 —— 契约只有一处实现，不因换适配器而漂移。</p>
 *
 * <h2>传输安全（FD-0013-R1）</h2>
 * <p>API Key 通过 {@code Authorization: Bearer} 发送，因此 Endpoint 必须是 HTTPS；
 * 明文 HTTP 只允许<b>本机回环地址</b>（{@code 127.0.0.1} / {@code localhost} / {@code ::1}），
 * 供自动化测试使用本地合成端点。这条边界在构造器里就检查（见 {@link RerankEndpointPolicy}），
 * 因此「直接 new 出适配器」也绕不过去。</p>
 *
 * <h2>不重试、不降级</h2>
 * <p>一次检索只发起一次请求；失败即失败，绝不返回「原向量排序」之类的降级结果
 * （静默降级会让调用方以为重排生效了）。</p>
 *
 * <h2>不记录敏感内容</h2>
 * <p>本类<b>不</b>打印 query、候选正文、请求体、响应体、Endpoint 或 API Key。
 * 自有日志只写稳定错误码与异常类名，且只记条数（不记内容）。</p>
 */
public final class DashScopeKnowledgeRerankAdapter implements KnowledgeRerankPort {

    private static final Logger log = LoggerFactory.getLogger(DashScopeKnowledgeRerankAdapter.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String FIELD_MODEL = "model";

    private static final String FIELD_QUERY = "query";

    private static final String FIELD_DOCUMENTS = "documents";

    private static final String FIELD_RESULTS = "results";

    private static final String FIELD_INDEX = "index";

    private static final String FIELD_RELEVANCE_SCORE = "relevance_score";

    private final HttpClient httpClient;

    private final URI endpoint;

    private final String model;

    private final String apiKey;

    private final Duration readTimeout;

    /**
     * @param endpoint       重排接口地址（不含业务空间占位符；必须是 HTTPS 或本机回环 HTTP）
     * @param model          重排模型标识（本版本要求逐字为 {@code qwen3-rerank}）
     * @param apiKey         DashScope API Key（只放进 Authorization 头，不写日志）
     * @param connectTimeout 连接超时
     * @param readTimeout    读取（响应）超时
     * @throws IllegalStateException Endpoint 使用明文 HTTP 且不是本机回环地址
     */
    public DashScopeKnowledgeRerankAdapter(URI endpoint, String model, String apiKey,
            Duration connectTimeout, Duration readTimeout) {

        this.endpoint = Objects.requireNonNull(endpoint, "endpoint 不能为 null");
        // FD-0013-R1：适配器自己也守这条边界 —— 直接构造（绕过 Spring 配置校验）同样不能把
        // Bearer Key 发往明文 HTTP；判断逻辑与配置校验共用同一个策略类
        RerankEndpointPolicy.requireSecureEndpoint(this.endpoint);
        this.model = Objects.requireNonNull(model, "model 不能为 null");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey 不能为 null");
        this.readTimeout = Objects.requireNonNull(readTimeout, "readTimeout 不能为 null");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Objects.requireNonNull(connectTimeout, "connectTimeout 不能为 null"))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public List<KnowledgeRerankResult> rerank(String normalizedQuery, List<String> candidateContents) {
        Objects.requireNonNull(normalizedQuery, "normalizedQuery 不能为 null");
        Objects.requireNonNull(candidateContents, "candidateContents 不能为 null");
        if (normalizedQuery.isEmpty()) {
            throw new IllegalArgumentException("normalizedQuery 不能为空");
        }
        if (candidateContents.isEmpty()) {
            throw new IllegalArgumentException("candidateContents 不能为空");
        }

        HttpRequest request = HttpRequest.newBuilder(this.endpoint)
                .timeout(this.readTimeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + this.apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(normalizedQuery, candidateContents),
                        StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = send(request);
        if (response.statusCode() != 200) {
            // 429 / 5xx / 401 / 403 都属于「上游不可用」：稳定 502，且不记录上游响应体
            log.error("DashScope 文本重排调用失败：errorCode={} status={}",
                    KnowledgeApplicationErrorCode.RERANK_PROVIDER_ERROR, response.statusCode());
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.RERANK_PROVIDER_ERROR,
                    "重排服务返回了非 200 响应");
        }
        return parseResults(response.body());
    }

    /**
     * 发起请求，把连接期/读取期的失败统一映射为「上游不可用」。
     *
     * @param request 请求
     * @return 响应
     */
    private HttpResponse<String> send(HttpRequest request) {
        try {
            return this.httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        catch (IOException ex) {
            // 连接失败、超时（HttpTimeoutException 也是 IOException 的子类）：细节只留在 cause
            log.error("DashScope 文本重排调用失败：errorCode={} exception={}",
                    KnowledgeApplicationErrorCode.RERANK_PROVIDER_ERROR, ex.getClass().getName());
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.RERANK_PROVIDER_ERROR,
                    "重排服务调用失败", ex);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.error("DashScope 文本重排调用被中断：errorCode={}",
                    KnowledgeApplicationErrorCode.RERANK_PROVIDER_ERROR);
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.RERANK_PROVIDER_ERROR,
                    "重排服务调用被中断", ex);
        }
    }

    /**
     * 构造 qwen3-rerank 的扁平请求体。
     *
     * @param normalizedQuery   规范化后的问题
     * @param candidateContents 候选正文（顺序即向量顺序）
     * @return 请求体 JSON
     */
    private String requestBody(String normalizedQuery, List<String> candidateContents) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put(FIELD_MODEL, this.model);
        root.put(FIELD_QUERY, normalizedQuery);
        ArrayNode documents = root.putArray(FIELD_DOCUMENTS);
        for (String content : candidateContents) {
            documents.add(content == null ? "" : content);
        }
        return root.toString();
    }

    /**
     * 解析响应里的 {@code results}。
     *
     * @param body 响应体
     * @return 重排结果（下标与分数可能为 {@code null}，由应用层的契约校验拒绝）
     */
    private static List<KnowledgeRerankResult> parseResults(String body) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body == null ? "" : body);
        }
        catch (IOException ex) {
            throw invalidResponse(ex);
        }
        if (root == null || !root.isObject()) {
            throw invalidResponse(null);
        }
        JsonNode results = root.path(FIELD_RESULTS);
        if (!results.isArray()) {
            // 只接受 qwen3-rerank 的顶层 results；嵌套 output.results 属于另一种协议，不做猜测
            throw invalidResponse(null);
        }

        List<KnowledgeRerankResult> parsed = new ArrayList<>(results.size());
        for (JsonNode node : results) {
            if (!node.isObject()) {
                throw invalidResponse(null);
            }
            parsed.add(new KnowledgeRerankResult(readIndex(node), readScore(node)));
        }
        return parsed;
    }

    /**
     * 读取单条结果的原始下标（FD-0013-R1 收紧）。
     *
     * <p>下标必须<b>是整数类型</b>且<b>能无损装进 Java {@code int}</b>：
     * {@code 0.9}、{@code 1.8}、{@code 1e0}（解析为浮点节点）、
     * {@code 4294967296}、{@code -4294967296}（超出 int 范围）、
     * 以及字符串、布尔值一律拒绝。</p>
     *
     * <p>为什么不能用 {@code intValue()} 了事：它会把 {@code 0.9} 变成 {@code 0}、
     * 把 {@code 4294967296} 溢出成 {@code 0} —— 于是「分数属于第 0 个候选」这种
     * 看起来合法、实际错位的绑定就悄悄成立了。这里宁可整次失败，也不接受无法无损表达的
     * 下标。</p>
     *
     * <p>字段缺失或为 {@code null} 时返回 {@code null}：那不是「默认 0」，
     * 而是「上游没有给出归属」，由应用层的重排结果契约统一拒绝（契约只有一处实现）。</p>
     *
     * @param node 单条结果
     * @return 原始下标；字段缺失时为 {@code null}
     */
    private static Integer readIndex(JsonNode node) {
        JsonNode index = node.path(FIELD_INDEX);
        if (index.isMissingNode() || index.isNull()) {
            return null;
        }
        if (!index.isIntegralNumber() || !index.canConvertToInt()) {
            throw invalidResponse(null);
        }
        return index.intValue();
    }

    /**
     * @param node 单条结果
     * @return 相关性分数；字段缺失时为 {@code null}
     */
    private static Double readScore(JsonNode node) {
        JsonNode score = node.path(FIELD_RELEVANCE_SCORE);
        if (score.isMissingNode() || score.isNull()) {
            return null;
        }
        if (!score.isNumber()) {
            throw invalidResponse(null);
        }
        return score.doubleValue();
    }

    /**
     * @param cause 原始异常（可为 {@code null}）
     * @return 内部检索失败（HTTP 500）
     */
    private static KnowledgeApplicationException invalidResponse(Throwable cause) {
        log.error("DashScope 文本重排响应无法解释：errorCode={} exception={}",
                KnowledgeApplicationErrorCode.KNOWLEDGE_RETRIEVAL_FAILURE,
                cause == null ? "none" : cause.getClass().getName());
        return new KnowledgeApplicationException(KnowledgeApplicationErrorCode.KNOWLEDGE_RETRIEVAL_FAILURE,
                "重排服务返回了无法解释的响应", cause);
    }
}
