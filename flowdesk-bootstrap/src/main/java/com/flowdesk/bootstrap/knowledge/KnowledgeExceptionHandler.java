package com.flowdesk.bootstrap.knowledge;

import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.application.knowledge.parse.DocumentChunkingException;
import com.flowdesk.application.knowledge.parse.DocumentParsingException;
import com.flowdesk.bootstrap.web.FlowDeskProblems;
import com.flowdesk.domain.knowledge.KnowledgeDomainException;
import com.flowdesk.domain.knowledge.KnowledgeErrorCode;
import com.flowdesk.domain.knowledge.KnowledgeParseFailureCode;
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
 *   <caption>映射矩阵（上传与查询）</caption>
 *   <tr><th>场景</th><th>HTTP</th><th>code</th></tr>
 *   <tr><td>标题、文件名等非法输入（含领域字段校验失败）</td><td>400</td><td>{@code INVALID_REQUEST}</td></tr>
 *   <tr><td>空文件</td><td>400</td><td>{@code INVALID_REQUEST}</td></tr>
 *   <tr><td>超过大小限制</td><td>413</td><td>{@code DOCUMENT_TOO_LARGE}</td></tr>
 *   <tr><td>格式不受支持或声明与实际内容不一致</td><td>415</td><td>{@code UNSUPPORTED_DOCUMENT_TYPE}</td></tr>
 *   <tr><td>文档不存在</td><td>404</td><td>{@code KNOWLEDGE_DOCUMENT_NOT_FOUND}</td></tr>
 *   <tr><td>内容存储 / 元数据存储 / 快照损坏</td><td>500</td><td>{@code INTERNAL_SERVER_ERROR}</td></tr>
 * </table>
 *
 * <table border="1">
 *   <caption>映射矩阵（解析，FD-0009）</caption>
 *   <tr><th>场景</th><th>HTTP</th><th>code</th></tr>
 *   <tr><td>解析命令不合法（版本为负等）</td><td>400</td><td>{@code INVALID_REQUEST}</td></tr>
 *   <tr><td>{@code If-Match} 已过期 / CAS 失败</td><td>412</td><td>{@code KNOWLEDGE_DOCUMENT_VERSION_CONFLICT}</td></tr>
 *   <tr><td>当前状态不允许解析（已在解析或已解析完成）</td><td>409</td><td>{@code KNOWLEDGE_DOCUMENT_NOT_PARSABLE}</td></tr>
 *   <tr><td>文档损坏 / 加密 / 与声明格式不符 / 无可提取文本</td><td>422</td>
 *       <td>{@code DOCUMENT_PARSE_FAILED}，另带 {@code failureCode}</td></tr>
 *   <tr><td>提取文本超过上限</td><td>413</td><td>{@code DOCUMENT_TOO_LARGE}，另带 {@code failureCode}</td></tr>
 *   <tr><td>切片数量超过上限</td><td>413</td><td>{@code DOCUMENT_TOO_MANY_CHUNKS}，另带 {@code failureCode}</td></tr>
 *   <tr><td>解析器内部失败、原始内容不可读、结果写入失败</td><td>500</td><td>{@code INTERNAL_SERVER_ERROR}</td></tr>
 * </table>
 *
 * <p>所有响应都是 {@code application/problem+json}，detail 一律是<b>固定安全文案</b>：
 * 不含原始文件名、标题原文、内容键、本地路径、SQL、异常类名或堆栈。
 * 解析失败额外返回一个 {@code failureCode}（{@link KnowledgeParseFailureCode} 的枚举名）：
 * 它是<b>稳定枚举</b>而不是自由文本，调用方可以据此分支，且不含任何解析器细节。</p>
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
            case INVALID_UPLOAD_COMMAND, INVALID_QUERY, INVALID_PARSE_COMMAND, EMPTY_DOCUMENT_CONTENT -> problem(
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
            case KNOWLEDGE_DOCUMENT_VERSION_CONFLICT -> problem(HttpStatus.PRECONDITION_FAILED,
                    FlowDeskProblems.CODE_KNOWLEDGE_DOCUMENT_VERSION_CONFLICT, "版本冲突",
                    "文档已被其他请求修改，请重新读取后再试", request);
            case KNOWLEDGE_DOCUMENT_NOT_PARSABLE -> problem(HttpStatus.CONFLICT,
                    FlowDeskProblems.CODE_KNOWLEDGE_DOCUMENT_NOT_PARSABLE, "状态不允许解析",
                    "当前状态不允许解析该文档", request);
            case KNOWLEDGE_DOCUMENT_ALREADY_EXISTS, INVALID_PERSISTED_DOCUMENT, CONTENT_STORAGE_FAILURE,
                    METADATA_STORAGE_FAILURE, DOCUMENT_CONTENT_UNREADABLE, KNOWLEDGE_INTERNAL_ERROR -> problem(
                    HttpStatus.INTERNAL_SERVER_ERROR, FlowDeskProblems.CODE_INTERNAL_SERVER_ERROR, "服务端错误",
                    "服务暂时不可用，请稍后重试", request);
        };
    }

    /**
     * 解析失败：按<b>稳定失败码</b>映射，并把失败码本身作为扩展字段返回。
     *
     * <p>区分依据是「调用方能否通过换一份文档解决」：</p>
     * <ul>
     *   <li>损坏、加密、与声明格式不符、提取不到文本 —— 文档本身的问题，422；</li>
     *   <li>提取文本或切片数量超限 —— 文档太大，413；</li>
     *   <li>解析器内部失败 —— 服务端问题，500（绝不把解析器异常文本暴露出去）。</li>
     * </ul>
     */
    @ExceptionHandler(DocumentParsingException.class)
    public ResponseEntity<ProblemDetail> handleParsingException(DocumentParsingException ex,
            HttpServletRequest request) {

        return parseFailure(ex.failureCode(), request);
    }

    /**
     * 切片失败：目前只有「切片数量超限」一种可预期失败，其余按服务端错误处理。
     */
    @ExceptionHandler(DocumentChunkingException.class)
    public ResponseEntity<ProblemDetail> handleChunkingException(DocumentChunkingException ex,
            HttpServletRequest request) {

        return parseFailure(ex.failureCode(), request);
    }

    /**
     * 领域异常：上传路径上的字段校验失败是调用方输入问题（400）；
     * 恢复快照失败、失败码非法、切片不满足不变量都是服务端数据/编码问题（500）；
     * 状态机冲突（无对应应用层包装时）是 409。
     */
    @ExceptionHandler(KnowledgeDomainException.class)
    public ResponseEntity<ProblemDetail> handleDomainException(KnowledgeDomainException ex,
            HttpServletRequest request) {

        return switch (ex.errorCode()) {
            case INVALID_RESTORED_STATE, INVALID_PARSE_FAILURE_CODE, INVALID_CHUNK -> problem(
                    HttpStatus.INTERNAL_SERVER_ERROR, FlowDeskProblems.CODE_INTERNAL_SERVER_ERROR,
                    "服务端错误", "服务暂时不可用，请稍后重试", request);
            case ILLEGAL_STATUS_TRANSITION -> problem(HttpStatus.CONFLICT,
                    FlowDeskProblems.CODE_ILLEGAL_STATUS_TRANSITION, "状态转换非法",
                    "当前状态不允许该操作", request);
            default -> problem(HttpStatus.BAD_REQUEST, FlowDeskProblems.CODE_INVALID_REQUEST, "请求不合法",
                    fixedDetail(ex.errorCode()), request);
        };
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
     * 稳定失败码到 HTTP 语义的映射。
     *
     * @param failureCode 解析/切片失败码
     * @param request     当前请求
     * @return 错误响应
     */
    private static ResponseEntity<ProblemDetail> parseFailure(KnowledgeParseFailureCode failureCode,
            HttpServletRequest request) {

        return switch (failureCode) {
            case CORRUPTED_DOCUMENT -> parseFailureProblem(HttpStatus.UNPROCESSABLE_ENTITY,
                    FlowDeskProblems.CODE_DOCUMENT_PARSE_FAILED, "文档无法解析",
                    "文档内容已损坏或与声明的格式不符", failureCode, request);
            case ENCRYPTED_DOCUMENT -> parseFailureProblem(HttpStatus.UNPROCESSABLE_ENTITY,
                    FlowDeskProblems.CODE_DOCUMENT_PARSE_FAILED, "文档无法解析",
                    "文档受密码保护，无法解析", failureCode, request);
            case UNSUPPORTED_DOCUMENT_CONTENT -> parseFailureProblem(HttpStatus.UNPROCESSABLE_ENTITY,
                    FlowDeskProblems.CODE_DOCUMENT_PARSE_FAILED, "文档无法解析",
                    "文档内容与声明的类型不一致", failureCode, request);
            case EMPTY_EXTRACTED_TEXT -> parseFailureProblem(HttpStatus.UNPROCESSABLE_ENTITY,
                    FlowDeskProblems.CODE_DOCUMENT_PARSE_FAILED, "文档无法解析",
                    "文档中没有可提取的文本", failureCode, request);
            case EXTRACTED_TEXT_TOO_LARGE -> parseFailureProblem(HttpStatus.PAYLOAD_TOO_LARGE,
                    FlowDeskProblems.CODE_DOCUMENT_TOO_LARGE, "文档过大",
                    "文档提取出的文本超过允许的最大长度", failureCode, request);
            case TOO_MANY_CHUNKS -> parseFailureProblem(HttpStatus.PAYLOAD_TOO_LARGE,
                    FlowDeskProblems.CODE_DOCUMENT_TOO_MANY_CHUNKS, "文档过大",
                    "文档切片数量超过允许的最大值", failureCode, request);
            case PARSER_FAILURE -> parseFailureProblem(HttpStatus.INTERNAL_SERVER_ERROR,
                    FlowDeskProblems.CODE_INTERNAL_SERVER_ERROR, "服务端错误",
                    "服务暂时不可用，请稍后重试", failureCode, request);
        };
    }

    private static ResponseEntity<ProblemDetail> parseFailureProblem(HttpStatus status, String code, String title,
            String detail, KnowledgeParseFailureCode failureCode, HttpServletRequest request) {

        ProblemDetail problem = FlowDeskProblems.of(status, code, title, detail, request.getRequestURI());
        problem.setProperty("failureCode", failureCode.name());
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    /**
     * 领域错误码到固定文案的映射：只说明「哪一类字段不合法」，不回显任何原始值。
     */
    private static String fixedDetail(KnowledgeApplicationErrorCode code) {
        return switch (code) {
            case INVALID_UPLOAD_COMMAND, INVALID_QUERY -> "上传请求不合法";
            case INVALID_PARSE_COMMAND -> "解析请求不合法";
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
            case INVALID_CONTENT_KEY, INVALID_STATUS, INVALID_FORMAT, INVALID_TIMELINE, INVALID_CHUNK,
                    ILLEGAL_STATUS_TRANSITION, INVALID_PARSE_FAILURE_CODE -> "上传请求不合法";
            case INVALID_RESTORED_STATE -> "服务暂时不可用，请稍后重试";
        };
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String title,
            String detail, HttpServletRequest request) {

        ProblemDetail problem = FlowDeskProblems.of(status, code, title, detail, request.getRequestURI());
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }
}
