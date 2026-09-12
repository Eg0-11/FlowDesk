package com.flowdesk.bootstrap.ai;

import com.flowdesk.application.ai.AiProviderException;
import com.flowdesk.application.ai.AiRequestException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * AI 接口异常到 HTTP 状态码的映射。
 *
 * <p>契约：</p>
 * <ul>
 *   <li>参数非法 → 400，错误码 {@code INVALID_REQUEST}</li>
 *   <li>上游模型调用失败 → 502，错误码 {@code AI_PROVIDER_ERROR}</li>
 * </ul>
 *
 * <p>响应体为 Spring {@link ProblemDetail}，只包含 FlowDesk 自己的文案与错误码，
 * 绝不包含供应商原始报文、堆栈或配置；上游异常的 cause 仅保留在服务端。</p>
 */
@RestControllerAdvice
public class AiExceptionHandler {

    /** 业务错误码：请求不合法。 */
    public static final String CODE_INVALID_REQUEST = "INVALID_REQUEST";

    /** 业务错误码：上游 AI 服务错误。 */
    public static final String CODE_AI_PROVIDER_ERROR = "AI_PROVIDER_ERROR";

    /**
     * 应用层判定的请求不合法。
     */
    @ExceptionHandler(AiRequestException.class)
    public ProblemDetail handleRequestException(AiRequestException ex) {
        return problem(HttpStatus.BAD_REQUEST, CODE_INVALID_REQUEST, "请求不合法", ex.getMessage(), null);
    }

    /**
     * Bean Validation 失败（如 message 为空或超长）。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationException(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("请求参数不合法");
        return problem(HttpStatus.BAD_REQUEST, CODE_INVALID_REQUEST, "请求不合法", detail, null);
    }

    /**
     * 请求体无法解析（非法 JSON、类型不匹配）。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException ex) {
        return problem(HttpStatus.BAD_REQUEST, CODE_INVALID_REQUEST, "请求不合法", "请求体不是合法 JSON", null);
    }

    /**
     * 上游模型调用失败。detail 使用固定文案，不回显 {@code ex.getMessage()}。
     */
    @ExceptionHandler(AiProviderException.class)
    public ProblemDetail handleProviderException(AiProviderException ex) {
        return problem(HttpStatus.BAD_GATEWAY, CODE_AI_PROVIDER_ERROR, "AI 服务错误",
                "上游 AI 服务暂时不可用，请稍后重试", ex.requestId());
    }

    private ProblemDetail problem(HttpStatus status, String code, String title, String detail, String requestId) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setTitle(title);
        problemDetail.setProperty("code", code);
        if (requestId != null) {
            problemDetail.setProperty("requestId", requestId);
        }
        return problemDetail;
    }
}
