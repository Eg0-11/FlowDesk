package com.flowdesk.bootstrap.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 请求解析与校验失败的**统一**处理入口。
 *
 * <p>这四类失败与业务无关，任何接口族都适用，因此集中在这里，全局生效：</p>
 * <ul>
 *   <li>{@link MethodArgumentNotValidException} —— Bean Validation 失败；</li>
 *   <li>{@link HttpMessageNotReadableException} —— 请求体不是合法 JSON（含枚举取值非法）；</li>
 *   <li>{@link MethodArgumentTypeMismatchException} —— 路径或参数类型不匹配；</li>
 *   <li>{@link InvalidRequestException} —— HTTP 边界上判定的格式问题（如非法工单标识）。</li>
 * </ul>
 *
 * <p>任何一个异常类型在本应用中都只有这一个处理入口：工单与 AI 的业务 Advice 都不再声明它们，
 * 因此不存在职责重叠，也不会出现「同一种异常两处处理」。</p>
 *
 * <p>detail 一律使用固定文案或字段名 + 校验消息，不回显请求中的原始取值。</p>
 */
@RestControllerAdvice
@Order(0)
public class ApiRequestExceptionHandler {

    /**
     * Bean Validation 失败。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex,
            HttpServletRequest request) {

        String detail = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("请求参数不合法");
        return badRequest(detail, request);
    }

    /**
     * 请求体无法解析：非法 JSON、类型不匹配、枚举取值不在允许范围内。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadableBody(HttpMessageNotReadableException ex,
            HttpServletRequest request) {

        return badRequest("请求体不是合法 JSON", request);
    }

    /**
     * 路径参数类型不匹配。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
            HttpServletRequest request) {

        return badRequest(ex.getName() + " 格式不合法", request);
    }

    /**
     * HTTP 边界上判定的请求格式问题（例如工单标识不是规范 UUID）。
     */
    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ProblemDetail> handleInvalidRequest(InvalidRequestException ex,
            HttpServletRequest request) {

        return badRequest(ex.getMessage(), request);
    }

    private static ResponseEntity<ProblemDetail> badRequest(String detail, HttpServletRequest request) {
        ProblemDetail problem = FlowDeskProblems.of(HttpStatus.BAD_REQUEST, FlowDeskProblems.CODE_INVALID_REQUEST,
                "请求不合法", detail, request.getRequestURI());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
