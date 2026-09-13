package com.flowdesk.bootstrap.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 五类框架错误的统一处理，外加一个<b>优先级最低</b>的兜底。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>未匹配的路径 → 404 {@code ENDPOINT_NOT_FOUND}；</li>
 *   <li>路径存在但方法不对 → 405 {@code METHOD_NOT_ALLOWED}，并保留 {@code Allow} 响应头；</li>
 *   <li>{@code Accept} 无法被满足 → 406 {@code NOT_ACCEPTABLE}；</li>
 *   <li>不支持的 {@code Content-Type} → 415 {@code UNSUPPORTED_MEDIA_TYPE}；</li>
 *   <li>未预期异常 → 500 {@code INTERNAL_SERVER_ERROR}（detail 为固定文案，不泄漏任何内部信息，
 *       异常与堆栈只写入服务端 ERROR 日志）。</li>
 * </ul>
 *
 * <p><b>406 的特殊性</b>：{@link HttpMediaTypeNotAcceptableException} 由
 * {@code RequestMappingHandlerMapping} 在内容协商阶段抛出，早于任何 Controller 方法执行，
 * 因此 406 响应天然不可能带有 {@code ETag}、{@code Location} 等成功响应头，
 * 也不会产生任何副作用（不写库、不改版本）。它在这里被<b>显式</b>处理，
 * 因而既不会落入 500 兜底，也不会被记录为 ERROR（本处理器只记 DEBUG）。</p>
 *
 * <p>注意 415 与 406 的区别：前者是请求体的 {@code Content-Type} 服务端读不懂，
 * 后者是客户端要求的响应 {@code Accept} 服务端给不了。</p>
 *
 * <p><b>优先级</b>：本类标注 {@link Ordered#LOWEST_PRECEDENCE}，是最后被咨询的 Advice；
 * 而兜底的 {@code Exception.class} 处理器在类内也是最后匹配的（Spring 按异常类型的具体程度选择）。
 * 因此它<b>不会</b>截获工单异常、AI 异常、请求解析异常与 Bean Validation 异常 ——
 * 那些都由各自更具体的处理器负责，有回归测试锁定。</p>
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class FrameworkExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(FrameworkExceptionHandler.class);

    /**
     * 未匹配到任何处理器：视为端点不存在。
     */
    @ExceptionHandler({ NoHandlerFoundException.class, NoResourceFoundException.class })
    public ResponseEntity<ProblemDetail> handleNoEndpoint(Exception ex, HttpServletRequest request) {
        log.debug("端点不存在：{} {}", request.getMethod(), request.getRequestURI());
        return problem(HttpStatus.NOT_FOUND, FlowDeskProblems.CODE_ENDPOINT_NOT_FOUND, "端点不存在",
                "请求的路径没有对应端点", request, null);
    }

    /**
     * 路径存在但 HTTP 方法不被支持。{@code Allow} 头必须保留，客户端据此得知可用方法。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex,
            HttpServletRequest request) {

        String allow = ex.getSupportedHttpMethods() == null
                ? null
                : String.join(", ", ex.getSupportedHttpMethods().stream().map(Object::toString).toList());
        log.debug("方法不被支持：{} {}（Allow: {}）", request.getMethod(), request.getRequestURI(), allow);
        return problem(HttpStatus.METHOD_NOT_ALLOWED, FlowDeskProblems.CODE_METHOD_NOT_ALLOWED, "方法不被支持",
                "该端点不支持此 HTTP 方法", request, allow);
    }

    /**
     * 不支持的 {@code Content-Type}。
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex,
            HttpServletRequest request) {

        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, FlowDeskProblems.CODE_UNSUPPORTED_MEDIA_TYPE,
                "媒体类型不支持", "请求的 Content-Type 不受支持", request, null);
    }

    /**
     * {@code Accept} 无法被满足：端点存在、方法也对，但没有任何可产出的媒体类型能匹配。
     *
     * <p>该异常在内容协商阶段（进入 Controller 之前）抛出，因此这里<b>只需</b>构造错误体：
     * 既没有用法需要回滚，也没有成功响应头需要清理。detail 是固定文案，
     * 不回显客户端的 {@code Accept}，也不含异常信息。</p>
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ProblemDetail> handleNotAcceptable(HttpMediaTypeNotAcceptableException ex,
            HttpServletRequest request) {

        log.debug("Accept 无法被满足：{} {}（Accept: {}）", request.getMethod(), request.getRequestURI(),
                request.getHeader(HttpHeaders.ACCEPT));
        return problem(HttpStatus.NOT_ACCEPTABLE, FlowDeskProblems.CODE_NOT_ACCEPTABLE,
                "响应媒体类型不可接受", "该接口只返回 application/json，请求的 Accept 无法被满足", request, null);
    }

    /**
     * 兜底：任何未被更具体处理器认领的异常。
     *
     * <p>detail 是固定文案；异常信息、堆栈、SQL 与请求原文一律不外泄 ——
     * 它们只通过这里的日志留在服务端，供排障使用。</p>
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("未预期异常，已按 500 兜底：{} {}", request.getMethod(), request.getRequestURI(), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, FlowDeskProblems.CODE_INTERNAL_SERVER_ERROR,
                "服务端错误", "服务暂时不可用，请稍后重试", request, null);
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String title, String detail,
            HttpServletRequest request, String allowHeader) {

        ProblemDetail problem = FlowDeskProblems.of(status, code, title, detail, request.getRequestURI());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (allowHeader != null && !allowHeader.isEmpty()) {
            builder.header(HttpHeaders.ALLOW, allowHeader);
        }
        return builder.body(problem);
    }
}
