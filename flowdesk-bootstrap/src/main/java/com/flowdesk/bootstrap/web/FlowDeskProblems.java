package com.flowdesk.bootstrap.web;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * FlowDesk 的 HTTP 错误契约。
 *
 * <p><b>由 FlowDesk 处理</b>的所有错误响应都是 {@code application/problem+json}，并且必然包含：</p>
 * <ul>
 *   <li>{@code type} —— 稳定的问题类型 URI，形如 {@code urn:flowdesk:problem:ticket-not-found}
 *       （由错误码小写化、下划线转连字符得到）；</li>
 *   <li>{@code title}、{@code status}、{@code detail}、{@code instance}；</li>
 *   <li>{@code code} —— 稳定的业务错误码，调用方应当据此分支，而不是解析文案。</li>
 * </ul>
 *
 * <p><b>覆盖范围</b>（与 README「错误契约」表逐行对应）：工单业务错误、知识文档业务错误、
 * AI 业务错误、请求解析与 Bean Validation 失败，以及五类框架错误 —— 端点不存在、方法不被支持、
 * 媒体类型不可接受（{@code Accept}）、媒体类型不受支持（{@code Content-Type}）、未预期异常。</p>
 *
 * <p>本类刻意不提供任何把异常信息、SQL、堆栈或请求原文拼进 detail 的能力：
 * detail 一律由调用方传入固定文案。</p>
 */
public final class FlowDeskProblems {

    private static final String TYPE_PREFIX = "urn:flowdesk:problem:";

    /** 请求本身不合法：JSON 无法解析、路径参数类型不匹配、Bean Validation 失败。 */
    public static final String CODE_INVALID_REQUEST = "INVALID_REQUEST";

    /** {@code If-Match} 头格式非法。 */
    public static final String CODE_INVALID_IF_MATCH = "INVALID_IF_MATCH";

    /** 缺少 {@code If-Match} 头。 */
    public static final String CODE_PRECONDITION_REQUIRED = "PRECONDITION_REQUIRED";

    /** 应用层命令本身不合法。 */
    public static final String CODE_INVALID_COMMAND = "INVALID_COMMAND";

    /** 工单不存在。 */
    public static final String CODE_TICKET_NOT_FOUND = "TICKET_NOT_FOUND";

    /** 工单标识已存在。 */
    public static final String CODE_TICKET_ALREADY_EXISTS = "TICKET_ALREADY_EXISTS";

    /** 版本冲突（调用方版本过期或 CAS 失败）。 */
    public static final String CODE_TICKET_VERSION_CONFLICT = "TICKET_VERSION_CONFLICT";

    /** 当前状态不允许该操作。 */
    public static final String CODE_ILLEGAL_STATUS_TRANSITION = "ILLEGAL_STATUS_TRANSITION";

    /** 重新分配时新处理人与当前处理人相同。 */
    public static final String CODE_SAME_ASSIGNEE = "SAME_ASSIGNEE";

    /** 持久化的工单快照不自洽（数据损坏，非调用方错误）。 */
    public static final String CODE_INVALID_PERSISTED_TICKET = "INVALID_PERSISTED_TICKET";

    /** 上游 AI 服务调用失败。 */
    public static final String CODE_AI_PROVIDER_ERROR = "AI_PROVIDER_ERROR";

    /** 请求的路径没有对应端点。 */
    public static final String CODE_ENDPOINT_NOT_FOUND = "ENDPOINT_NOT_FOUND";

    /** 路径存在但不支持该 HTTP 方法。 */
    public static final String CODE_METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";

    /** 请求的 {@code Content-Type} 不受支持。 */
    public static final String CODE_UNSUPPORTED_MEDIA_TYPE = "UNSUPPORTED_MEDIA_TYPE";

    /** 上传的知识文档类型不受支持（扩展名、媒体类型或文件内容不一致）。 */
    public static final String CODE_UNSUPPORTED_DOCUMENT_TYPE = "UNSUPPORTED_DOCUMENT_TYPE";

    /** 上传内容超过允许的最大大小。 */
    public static final String CODE_DOCUMENT_TOO_LARGE = "DOCUMENT_TOO_LARGE";

    /** 知识文档不存在。 */
    public static final String CODE_KNOWLEDGE_DOCUMENT_NOT_FOUND = "KNOWLEDGE_DOCUMENT_NOT_FOUND";

    /** 知识文档版本冲突（{@code If-Match} 已过期或 CAS 失败）。 */
    public static final String CODE_KNOWLEDGE_DOCUMENT_VERSION_CONFLICT = "KNOWLEDGE_DOCUMENT_VERSION_CONFLICT";

    /** 知识文档当前状态不允许解析（已在解析中、或已经解析完成）。 */
    public static final String CODE_KNOWLEDGE_DOCUMENT_NOT_PARSABLE = "KNOWLEDGE_DOCUMENT_NOT_PARSABLE";

    /** 知识文档当前状态不允许索引（尚未解析、已在索引中、或已经索引完成）。 */
    public static final String CODE_KNOWLEDGE_DOCUMENT_NOT_INDEXABLE = "KNOWLEDGE_DOCUMENT_NOT_INDEXABLE";

    /** 当前环境未启用文档向量化：索引接口不可用，且不会读取或修改任何文档。 */
    public static final String CODE_KNOWLEDGE_EMBEDDING_DISABLED = "KNOWLEDGE_EMBEDDING_DISABLED";

    /** 上游向量服务失败：超时、限流、5xx、连接失败等。 */
    public static final String CODE_EMBEDDING_PROVIDER_ERROR = "EMBEDDING_PROVIDER_ERROR";

    /**
     * 上游重排服务失败（RAG 6/6）：超时、限流、5xx、鉴权失败等。
     *
     * <p>与 {@link #CODE_EMBEDDING_PROVIDER_ERROR} 分开：两者是<b>不同的上游</b>，
     * 合并成一个错误码后「哪一段上游出问题」只能在日志里猜。</p>
     */
    public static final String CODE_RERANK_PROVIDER_ERROR = "RERANK_PROVIDER_ERROR";

    /** 文档无法解析：内容损坏、加密、与声明格式不符，或提取不到文本。 */
    public static final String CODE_DOCUMENT_PARSE_FAILED = "DOCUMENT_PARSE_FAILED";

    /** 文档切片数量超过配置上限。 */
    public static final String CODE_DOCUMENT_TOO_MANY_CHUNKS = "DOCUMENT_TOO_MANY_CHUNKS";

    /**
     * 请求的 {@code Accept} 无法被满足：服务端没有任何可产出的表示能满足它。
     *
     * <p>与 {@link #CODE_UNSUPPORTED_MEDIA_TYPE} 方向相反：415 是「你发来的我读不懂」，
     * 406 是「你要的我给不了」。两者都不代表请求本身有业务问题。</p>
     */
    public static final String CODE_NOT_ACCEPTABLE = "NOT_ACCEPTABLE";

    /** 未预期异常，兜底为服务端错误。 */
    public static final String CODE_INTERNAL_SERVER_ERROR = "INTERNAL_SERVER_ERROR";

    private FlowDeskProblems() {
    }

    /**
     * 由错误码推导问题类型 URI。
     *
     * @param code 稳定错误码，例如 {@code TICKET_NOT_FOUND}
     * @return 形如 {@code urn:flowdesk:problem:ticket-not-found} 的类型 URI
     */
    public static String typeFor(String code) {
        return TYPE_PREFIX + code.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * 构造错误体。
     *
     * @param status   HTTP 状态码
     * @param code     稳定错误码
     * @param title    简短标题
     * @param detail   固定文案，不得包含异常、SQL、堆栈或请求原文
     * @param instance 请求路径，可为 {@code null}
     * @return 错误体
     */
    public static ProblemDetail of(HttpStatus status, String code, String title, String detail, String instance) {
        Objects.requireNonNull(status, "status 不能为 null");
        Objects.requireNonNull(code, "code 不能为 null");

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(typeFor(code)));
        problem.setTitle(title);
        problem.setProperty("code", code);
        if (instance != null && !instance.isEmpty()) {
            problem.setInstance(URI.create(instance));
        }
        return problem;
    }
}
