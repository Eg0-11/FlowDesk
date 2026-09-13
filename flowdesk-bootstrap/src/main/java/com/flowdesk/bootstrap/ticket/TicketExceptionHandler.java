package com.flowdesk.bootstrap.ticket;

import com.flowdesk.application.ticket.TicketApplicationErrorCode;
import com.flowdesk.application.ticket.TicketApplicationException;
import com.flowdesk.bootstrap.web.FlowDeskProblems;
import com.flowdesk.domain.ticket.TicketDomainException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 工单业务异常到 HTTP 状态码与错误码的映射。
 *
 * <p>只处理工单自己的异常类型，因此与 AI 的异常处理<b>互不误伤</b>：
 * 请求解析与 Bean Validation 由 {@code ApiRequestExceptionHandler} 统一处理，
 * AI 的业务异常由 {@code AiExceptionHandler} 处理，本类不再声明它们。</p>
 *
 * <table border="1">
 *   <caption>映射矩阵</caption>
 *   <tr><th>场景</th><th>HTTP</th><th>code</th></tr>
 *   <tr><td>应用层命令不合法</td><td>400</td><td>{@code INVALID_COMMAND}</td></tr>
 *   <tr><td>列表查询条件不合法</td><td>400</td><td>{@code INVALID_REQUEST}</td></tr>
 *   <tr><td>工单不存在</td><td>404</td><td>{@code TICKET_NOT_FOUND}</td></tr>
 *   <tr><td>工单标识已存在</td><td>409</td><td>{@code TICKET_ALREADY_EXISTS}</td></tr>
 *   <tr><td>版本冲突</td><td>412</td><td>{@code TICKET_VERSION_CONFLICT}</td></tr>
 *   <tr><td>非法状态转换</td><td>409</td><td>{@code ILLEGAL_STATUS_TRANSITION}</td></tr>
 *   <tr><td>相同处理人</td><td>409</td><td>{@code SAME_ASSIGNEE}</td></tr>
 *   <tr><td>字段级领域校验失败</td><td>422</td><td>保留对应领域错误码</td></tr>
 *   <tr><td>持久化快照不自洽</td><td>500</td><td>{@code INVALID_PERSISTED_TICKET}</td></tr>
 * </table>
 *
 * <p>响应体一律是 {@code application/problem+json}，detail 只使用固定文案或领域层
 * 已确保不含原始输入的消息，不包含异常类名、堆栈、SQL 或请求原文。</p>
 */
@RestControllerAdvice
@Order(10)
public class TicketExceptionHandler {

    /**
     * 缺少 {@code If-Match}：状态变更接口要求版本前置条件。
     */
    @ExceptionHandler(MissingIfMatchException.class)
    public ResponseEntity<ProblemDetail> handleMissingIfMatch(MissingIfMatchException ex,
            HttpServletRequest request) {

        return problem(HttpStatus.PRECONDITION_REQUIRED, FlowDeskProblems.CODE_PRECONDITION_REQUIRED,
                "缺少版本前置条件", "状态变更请求必须携带 If-Match 头", request);
    }

    /**
     * {@code If-Match} 格式非法。
     */
    @ExceptionHandler(InvalidIfMatchException.class)
    public ResponseEntity<ProblemDetail> handleInvalidIfMatch(InvalidIfMatchException ex,
            HttpServletRequest request) {

        return problem(HttpStatus.BAD_REQUEST, FlowDeskProblems.CODE_INVALID_IF_MATCH, "版本前置条件非法",
                "If-Match 必须是单个强类型十进制 ETag，例如 \"0\"", request);
    }

    /**
     * 应用层异常。
     */
    @ExceptionHandler(TicketApplicationException.class)
    public ResponseEntity<ProblemDetail> handleApplicationException(TicketApplicationException ex,
            HttpServletRequest request) {

        return switch (ex.errorCode()) {
            case INVALID_COMMAND -> problem(HttpStatus.BAD_REQUEST, FlowDeskProblems.CODE_INVALID_COMMAND,
                    "请求不合法", "命令不合法", request);
            case INVALID_QUERY -> problem(HttpStatus.BAD_REQUEST, FlowDeskProblems.CODE_INVALID_REQUEST,
                    "查询条件不合法", "列表查询参数不合法", request);
            case TICKET_NOT_FOUND -> problem(HttpStatus.NOT_FOUND, FlowDeskProblems.CODE_TICKET_NOT_FOUND,
                    "工单不存在", "指定工单不存在", request);
            case TICKET_ALREADY_EXISTS -> problem(HttpStatus.CONFLICT,
                    FlowDeskProblems.CODE_TICKET_ALREADY_EXISTS, "工单已存在", "该工单标识已存在", request);
            case TICKET_VERSION_CONFLICT -> problem(HttpStatus.PRECONDITION_FAILED,
                    FlowDeskProblems.CODE_TICKET_VERSION_CONFLICT, "版本冲突",
                    "工单已被其他请求修改，请重新读取后再试", request);
        };
    }

    /**
     * 领域异常：状态机与处理人规则是业务冲突，字段级校验是语义错误，
     * 持久化快照不自洽则是服务端数据问题。
     */
    @ExceptionHandler(TicketDomainException.class)
    public ResponseEntity<ProblemDetail> handleDomainException(TicketDomainException ex,
            HttpServletRequest request) {

        return switch (ex.errorCode()) {
            case ILLEGAL_STATUS_TRANSITION -> problem(HttpStatus.CONFLICT,
                    FlowDeskProblems.CODE_ILLEGAL_STATUS_TRANSITION, "状态转换非法", "当前状态不允许该操作", request);
            case SAME_ASSIGNEE -> problem(HttpStatus.CONFLICT, FlowDeskProblems.CODE_SAME_ASSIGNEE,
                    "处理人未变化", "新处理人与当前处理人相同", request);
            case INVALID_RESTORED_STATE -> problem(HttpStatus.INTERNAL_SERVER_ERROR,
                    FlowDeskProblems.CODE_INVALID_PERSISTED_TICKET, "工单数据不合法",
                    "持久化的工单快照不自洽", request);
            default -> problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.errorCode().name(), "字段校验失败",
                    ex.getMessage(), request);
        };
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String title, String detail,
            HttpServletRequest request) {

        ProblemDetail problem = FlowDeskProblems.of(status, code, title, detail, request.getRequestURI());
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }
}
