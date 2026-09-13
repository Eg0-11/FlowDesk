package com.flowdesk.bootstrap.ticket;

import com.flowdesk.application.ticket.command.AssignTicketCommand;
import com.flowdesk.application.ticket.command.CloseTicketCommand;
import com.flowdesk.application.ticket.command.CreateTicketCommand;
import com.flowdesk.application.ticket.command.ReassignTicketCommand;
import com.flowdesk.application.ticket.command.ResolveTicketCommand;
import com.flowdesk.application.ticket.command.StartTicketCommand;
import com.flowdesk.application.ticket.port.in.TicketCommandUseCase;
import com.flowdesk.application.ticket.port.in.TicketQueryUseCase;
import com.flowdesk.application.ticket.query.GetTicketQuery;
import com.flowdesk.application.ticket.view.TicketView;
import com.flowdesk.domain.ticket.TicketId;
import com.flowdesk.domain.ticket.UserId;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工单 REST 接口。
 *
 * <p>职责严格限定为三件事：HTTP 映射、DTO 转换、调用输入端口。
 * 本类<b>不注入</b> {@code TicketRepository}、{@code JdbcClient}、领域聚合或具体应用服务实现，
 * 因此不可能绕过用例直接读写数据库。</p>
 *
 * <h2>乐观并发协议</h2>
 * <p>版本只来自 {@code If-Match}，请求体中不存在 {@code expectedVersion}：</p>
 * <ul>
 *   <li>缺少 {@code If-Match} → 428 Precondition Required；</li>
 *   <li>格式非法 → 400 {@code INVALID_IF_MATCH}；</li>
 *   <li>版本过期 → 412 Precondition Failed；</li>
 *   <li>创建、查询与每次成功变更都返回 {@code ETag: "&lt;当前版本&gt;"}，
 *       且响应体中的 {@code version} 与该 ETag 一致。</li>
 * </ul>
 */
@RestController
@RequestMapping(TicketController.BASE_PATH)
public class TicketController {

    /** 统一前缀。 */
    static final String BASE_PATH = "/api/v1/tickets";

    private final TicketCommandUseCase ticketCommandUseCase;

    private final TicketQueryUseCase ticketQueryUseCase;

    /**
     * @param ticketCommandUseCase 写入用例输入端口
     * @param ticketQueryUseCase   查询用例输入端口
     */
    public TicketController(TicketCommandUseCase ticketCommandUseCase, TicketQueryUseCase ticketQueryUseCase) {
        this.ticketCommandUseCase = ticketCommandUseCase;
        this.ticketQueryUseCase = ticketQueryUseCase;
    }

    /**
     * 创建工单。
     *
     * @param request 创建请求
     * @return 201 Created，带 Location 与 ETag
     */
    @PostMapping
    public ResponseEntity<TicketResponse> create(@Valid @RequestBody CreateTicketRequest request) {
        TicketView view = this.ticketCommandUseCase.create(new CreateTicketCommand(
                request.title(), request.description(), request.category(), request.priority(),
                UserId.of(request.requesterId())));

        return ResponseEntity.created(URI.create(BASE_PATH + "/" + view.id().value()))
                .eTag(TicketEtag.format(view.version()))
                .body(TicketResponse.from(view));
    }

    /**
     * 查询工单。
     *
     * @param ticketId 工单标识
     * @return 200 OK，带 ETag
     */
    @GetMapping("/{ticketId}")
    public ResponseEntity<TicketResponse> get(@PathVariable UUID ticketId) {
        return ok(this.ticketQueryUseCase.get(new GetTicketQuery(TicketId.of(ticketId))));
    }

    /**
     * 分配处理人。
     *
     * @param ticketId  工单标识
     * @param ifMatch   期望版本
     * @param request   分配请求
     * @return 200 OK，带新版本 ETag
     */
    @PostMapping("/{ticketId}/assign")
    public ResponseEntity<TicketResponse> assign(@PathVariable UUID ticketId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody AssignTicketRequest request) {

        long expectedVersion = TicketEtag.requireVersion(ifMatch);
        return ok(this.ticketCommandUseCase.assign(new AssignTicketCommand(TicketId.of(ticketId),
                UserId.of(request.assigneeId()), expectedVersion)));
    }

    /**
     * 重新分配处理人。
     *
     * @param ticketId 工单标识
     * @param ifMatch  期望版本
     * @param request  重新分配请求
     * @return 200 OK，带新版本 ETag
     */
    @PostMapping("/{ticketId}/reassign")
    public ResponseEntity<TicketResponse> reassign(@PathVariable UUID ticketId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody AssignTicketRequest request) {

        long expectedVersion = TicketEtag.requireVersion(ifMatch);
        return ok(this.ticketCommandUseCase.reassign(new ReassignTicketCommand(TicketId.of(ticketId),
                UserId.of(request.assigneeId()), expectedVersion)));
    }

    /**
     * 开始处理。
     *
     * @param ticketId 工单标识
     * @param ifMatch  期望版本
     * @return 200 OK，带新版本 ETag
     */
    @PostMapping("/{ticketId}/start")
    public ResponseEntity<TicketResponse> start(@PathVariable UUID ticketId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {

        long expectedVersion = TicketEtag.requireVersion(ifMatch);
        return ok(this.ticketCommandUseCase.start(new StartTicketCommand(TicketId.of(ticketId), expectedVersion)));
    }

    /**
     * 提交处理结论。
     *
     * @param ticketId 工单标识
     * @param ifMatch  期望版本
     * @param request  提交结论请求
     * @return 200 OK，带新版本 ETag
     */
    @PostMapping("/{ticketId}/resolve")
    public ResponseEntity<TicketResponse> resolve(@PathVariable UUID ticketId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ResolveTicketRequest request) {

        long expectedVersion = TicketEtag.requireVersion(ifMatch);
        return ok(this.ticketCommandUseCase.resolve(new ResolveTicketCommand(TicketId.of(ticketId),
                request.resolution(), expectedVersion)));
    }

    /**
     * 关闭工单。
     *
     * @param ticketId 工单标识
     * @param ifMatch  期望版本
     * @return 200 OK，带新版本 ETag
     */
    @PostMapping("/{ticketId}/close")
    public ResponseEntity<TicketResponse> close(@PathVariable UUID ticketId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {

        long expectedVersion = TicketEtag.requireVersion(ifMatch);
        return ok(this.ticketCommandUseCase.close(new CloseTicketCommand(TicketId.of(ticketId), expectedVersion)));
    }

    /**
     * 成功响应统一带 ETag，保证响应体 version 与 ETag 永远一致。
     */
    private static ResponseEntity<TicketResponse> ok(TicketView view) {
        return ResponseEntity.ok()
                .eTag(TicketEtag.format(view.version()))
                .body(TicketResponse.from(view));
    }
}
