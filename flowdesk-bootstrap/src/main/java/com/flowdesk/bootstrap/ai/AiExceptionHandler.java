package com.flowdesk.bootstrap.ai;

import com.flowdesk.application.ai.AiProviderException;
import com.flowdesk.application.ai.AiRequestException;
import com.flowdesk.bootstrap.web.FlowDeskProblems;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * AI 接口业务异常到 HTTP 状态码的映射。
 *
 * <p>契约：</p>
 * <ul>
 *   <li>应用层判定的请求不合法 → 400，错误码 {@code INVALID_REQUEST}</li>
 *   <li>上游模型调用失败 → 502，错误码 {@code AI_PROVIDER_ERROR}</li>
 * </ul>
 *
 * <p>只处理 AI 自己的异常类型。「JSON 无法解析」「Bean Validation 失败」「路径参数类型不匹配」
 * 这三类与业务无关的失败由 {@code ApiRequestExceptionHandler} 统一处理，
 * 本类不再声明它们 —— 同一个异常类型在全应用只有一个处理入口。</p>
 *
 * <p>响应体为 {@link ProblemDetail}，只包含 FlowDesk 自己的文案与错误码，
 * 绝不包含供应商原始报文、堆栈或配置；上游异常的 cause 仅保留在服务端。</p>
 */
@RestControllerAdvice
public class AiExceptionHandler {

    /**
     * 应用层判定的请求不合法。
     */
    @ExceptionHandler(AiRequestException.class)
    public ProblemDetail handleRequestException(AiRequestException ex, HttpServletRequest request) {
        return FlowDeskProblems.of(HttpStatus.BAD_REQUEST, FlowDeskProblems.CODE_INVALID_REQUEST,
                "请求不合法", ex.getMessage(), request.getRequestURI());
    }

    /**
     * 上游模型调用失败。detail 使用固定文案，不回显 {@code ex.getMessage()}。
     */
    @ExceptionHandler(AiProviderException.class)
    public ProblemDetail handleProviderException(AiProviderException ex, HttpServletRequest request) {
        ProblemDetail problem = FlowDeskProblems.of(HttpStatus.BAD_GATEWAY,
                FlowDeskProblems.CODE_AI_PROVIDER_ERROR, "AI 服务错误",
                "上游 AI 服务暂时不可用，请稍后重试", request.getRequestURI());
        if (ex.requestId() != null) {
            problem.setProperty("requestId", ex.requestId());
        }
        return problem;
    }
}
