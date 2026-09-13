package com.flowdesk.bootstrap.web;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * FlowDesk 的 HTTP 错误契约。
 *
 * <p>所有错误响应都是 {@code application/problem+json}，并且必然包含：</p>
 * <ul>
 *   <li>{@code type} —— 稳定的问题类型 URI，形如 {@code urn:flowdesk:problem:ticket-not-found}
 *       （由错误码小写化、下划线转连字符得到）；</li>
 *   <li>{@code title}、{@code status}、{@code detail}、{@code instance}；</li>
 *   <li>{@code code} —— 稳定的业务错误码，调用方应当据此分支，而不是解析文案。</li>
 * </ul>
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
