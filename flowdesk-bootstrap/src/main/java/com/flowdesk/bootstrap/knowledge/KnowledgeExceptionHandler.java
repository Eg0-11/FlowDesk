package com.flowdesk.bootstrap.knowledge;

import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.bootstrap.web.FlowDeskProblems;
import com.flowdesk.domain.knowledge.KnowledgeDomainException;
import com.flowdesk.domain.knowledge.KnowledgeErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

/**
 * 知识文档异常到 HTTP 状态码与错误码的映射。
 *
 * <table border="1">
 *   <caption>映射矩阵</caption>
 *   <tr><th>场景</th><th>HTTP</th><th>code</th></tr>
 *   <tr><td>标题、文件名等非法输入（含领域字段校验失败）</td><td>400</td><td>{@code INVALID_REQUEST}</td></tr>
 *   <tr><td>空文件</td><td>400</td><td>{@code INVALID_REQUEST}</td></tr>
 *   <tr><td>超过大小限制</td><td>413</td><td>{@code DOCUMENT_TOO_LARGE}</td></tr>
 *   <tr><td>格式不受支持或声明与实际内容不一致</td><td>415</td><td>{@code UNSUPPORTED_DOCUMENT_TYPE}</td></tr>
 *   <tr><td>文档不存在</td><td>404</td><td>{@code KNOWLEDGE_DOCUMENT_NOT_FOUND}</td></tr>
 *   <tr><td>内容存储 / 元数据存储 / 快照损坏</td><td>500</td><td>{@code INTERNAL_SERVER_ERROR}</td></tr>
 * </table>
 *
 * <p>所有响应都是 {@code application/problem+json}，detail 一律是<b>固定安全文案</b>：
 * 不含原始文件名、标题原文、内容键、本地路径、SQL、异常类名或堆栈。</p>
 */
@RestControllerAdvice
@Order(15)
public class KnowledgeExceptionHandler {

    /**
     * 应用层错误：按错误码映射。
     */
    @ExceptionHandler(KnowledgeApplicationException.class)
    public ResponseEntity<ProblemDetail> handleApplicationException(KnowledgeApplicationException ex,
            HttpServletRequest request) {

        return switch (ex.errorCode()) {
            case INVALID_UPLOAD_COMMAND, INVALID_QUERY, EMPTY_DOCUMENT_CONTENT -> problem(
                    HttpStatus.BAD_REQUEST, FlowDeskProblems.CODE_INVALID_REQUEST, "请求不合法",
                    fixedDetail(ex.errorCode()), request);
            case DOCUMENT_TOO_LARGE -> problem(HttpStatus.PAYLOAD_TOO_LARGE,
                    FlowDeskProblems.CODE_DOCUMENT_TOO_LARGE, "文档过大",
                    "上传内容超过允许的最大大小", request);
            case UNSUPPORTED_DOCUMENT_FORMAT -> problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    FlowDeskProblems.CODE_UNSUPPORTED_DOCUMENT_TYPE, "文档类型不受支持",
                    "仅支持 pdf、docx、md、txt，且内容必须与声明的类型一致", request);
            case KNOWLEDGE_DOCUMENT_NOT_FOUND -> problem(HttpStatus.NOT_FOUND,
                    FlowDeskProblems.CODE_KNOWLEDGE_DOCUMENT_NOT_FOUND, "文档不存在",
                    "指定知识文档不存在", request);
            case KNOWLEDGE_DOCUMENT_ALREADY_EXISTS, INVALID_PERSISTED_DOCUMENT, CONTENT_STORAGE_FAILURE,
                    METADATA_STORAGE_FAILURE -> problem(HttpStatus.INTERNAL_SERVER_ERROR,
                    FlowDeskProblems.CODE_INTERNAL_SERVER_ERROR, "服务端错误",
                    "服务暂时不可用，请稍后重试", request);
        };
    }

    /**
     * 领域异常：上传路径上的字段校验失败是调用方输入问题（400）；
     * 恢复快照失败是服务端数据问题（500）。
     */
    @ExceptionHandler(KnowledgeDomainException.class)
    public ResponseEntity<ProblemDetail> handleDomainException(KnowledgeDomainException ex,
            HttpServletRequest request) {

        if (ex.errorCode() == KnowledgeErrorCode.INVALID_RESTORED_STATE) {
            return problem(HttpStatus.INTERNAL_SERVER_ERROR, FlowDeskProblems.CODE_INTERNAL_SERVER_ERROR,
                    "服务端错误", "服务暂时不可用，请稍后重试", request);
        }
        return problem(HttpStatus.BAD_REQUEST, FlowDeskProblems.CODE_INVALID_REQUEST, "请求不合法",
                fixedDetail(ex.errorCode()), request);
    }

    /**
     * Spring 在进入 Controller <b>之前</b>就会拦下超过 multipart 上限的请求，
     * 这时还没有任何 HTTP 处理逻辑参与，必须在这里统一映射为 413 ——
     * 否则它会掉进兜底处理器变成 500，让「文件太大」看起来像服务端故障。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> handleMaxUploadSize(MaxUploadSizeExceededException ex,
            HttpServletRequest request) {

        return problem(HttpStatus.PAYLOAD_TOO_LARGE, FlowDeskProblems.CODE_DOCUMENT_TOO_LARGE, "文档过大",
                "上传内容超过允许的最大大小", request);
    }

    /**
     * 其余 multipart 解析失败（请求体不是合法的 multipart 等）：属于调用方请求问题。
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ProblemDetail> handleMultipart(MultipartException ex, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, FlowDeskProblems.CODE_INVALID_REQUEST, "请求不合法",
                "multipart 请求无法解析", request);
    }

    /**
     * 领域错误码到固定文案的映射：只说明「哪一类字段不合法」，不回显任何原始值。
     */
    private static String fixedDetail(KnowledgeApplicationErrorCode code) {
        return switch (code) {
            case INVALID_UPLOAD_COMMAND, INVALID_QUERY -> "上传请求不合法";
            case EMPTY_DOCUMENT_CONTENT -> "上传内容不能为空";
            default -> "请求不合法";
        };
    }

    private static String fixedDetail(KnowledgeErrorCode code) {
        return switch (code) {
            case INVALID_TITLE -> "标题不合法";
            case INVALID_ORIGINAL_FILENAME -> "原始文件名不合法";
            case INVALID_DOCUMENT_ID -> "文档标识不合法";
            case INVALID_SIZE -> "内容大小不合法";
            case INVALID_DIGEST -> "内容摘要不合法";
            case INVALID_MEDIA_TYPE -> "媒体类型不合法";
            case INVALID_CONTENT_KEY, INVALID_STATUS, INVALID_FORMAT, INVALID_TIMELINE ->
                    "上传请求不合法";
            case INVALID_RESTORED_STATE -> "服务暂时不可用，请稍后重试";
        };
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String title,
            String detail, HttpServletRequest request) {

        ProblemDetail problem = FlowDeskProblems.of(status, code, title, detail, request.getRequestURI());
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }
}
