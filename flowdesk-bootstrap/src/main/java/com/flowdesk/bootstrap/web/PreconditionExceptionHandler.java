package com.flowdesk.bootstrap.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 版本前置条件（{@code If-Match} / ETag）异常到 HTTP 的映射。
 *
 * <p>工单与知识文档使用<b>同一套</b>乐观并发协议，因此「缺少 {@code If-Match}」与
 * 「{@code If-Match} 格式非法」只能有一份映射实现：两类资源各写一份，
 * 迟早会在状态码或错误码上分叉。解析规则见 {@link EntityTag}。</p>
 *
 * <table border="1">
 *   <caption>映射矩阵</caption>
 *   <tr><th>场景</th><th>HTTP</th><th>code</th></tr>
 *   <tr><td>缺少 {@code If-Match}</td><td>428</td><td>{@code PRECONDITION_REQUIRED}</td></tr>
 *   <tr><td>{@code If-Match} 格式非法</td><td>400</td><td>{@code INVALID_IF_MATCH}</td></tr>
 * </table>
 *
 * <p>顺序在工单（10）与知识（15）之间：它处理的是 HTTP 协议层问题，
 * 与资源自己的业务异常互不重叠。</p>
 */
@RestControllerAdvice
@Order(12)
public class PreconditionExceptionHandler {

    /**
     * 缺少 {@code If-Match}：状态变更接口要求版本前置条件。
     *
     * <p>返回 428 而不是 400：请求本身是合法的，只是缺少必要的前置条件头，
     * 调用方补上 {@code If-Match} 后即可成功。</p>
     *
     * @param ex      缺少前置条件
     * @param request 当前请求
     * @return 428 错误体
     */
    @ExceptionHandler(MissingIfMatchException.class)
    public ResponseEntity<ProblemDetail> handleMissingIfMatch(MissingIfMatchException ex,
            HttpServletRequest request) {

        return problem(HttpStatus.PRECONDITION_REQUIRED, FlowDeskProblems.CODE_PRECONDITION_REQUIRED,
                "缺少版本前置条件", "状态变更请求必须携带 If-Match 头", request);
    }

    /**
     * {@code If-Match} 格式非法。
     *
     * @param ex      非法前置条件
     * @param request 当前请求
     * @return 400 错误体
     */
    @ExceptionHandler(InvalidIfMatchException.class)
    public ResponseEntity<ProblemDetail> handleInvalidIfMatch(InvalidIfMatchException ex,
            HttpServletRequest request) {

        return problem(HttpStatus.BAD_REQUEST, FlowDeskProblems.CODE_INVALID_IF_MATCH, "版本前置条件非法",
                "If-Match 必须是单个强类型十进制 ETag，例如 \"0\"", request);
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String title, String detail,
            HttpServletRequest request) {

        ProblemDetail problem = FlowDeskProblems.of(status, code, title, detail, request.getRequestURI());
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }
}
