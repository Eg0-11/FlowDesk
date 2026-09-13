package com.flowdesk.bootstrap.ticket;

import com.flowdesk.application.ticket.TicketApplicationErrorCode;
import com.flowdesk.application.ticket.TicketApplicationException;
import com.flowdesk.application.ticket.command.AssignTicketCommand;
import com.flowdesk.application.ticket.command.CloseTicketCommand;
import com.flowdesk.application.ticket.command.CreateTicketCommand;
import com.flowdesk.application.ticket.command.ReassignTicketCommand;
import com.flowdesk.application.ticket.command.ResolveTicketCommand;
import com.flowdesk.application.ticket.command.StartTicketCommand;
import com.flowdesk.application.ticket.port.in.TicketCommandUseCase;
import com.flowdesk.application.ticket.port.in.TicketQueryUseCase;
import com.flowdesk.application.ticket.query.GetTicketQuery;
import com.flowdesk.application.ticket.query.SearchTicketsQuery;
import com.flowdesk.application.ticket.view.TicketPageView;
import com.flowdesk.application.ticket.view.TicketView;
import com.flowdesk.bootstrap.web.InvalidRequestException;
import com.flowdesk.domain.ticket.TicketDomainException;
import com.flowdesk.domain.ticket.TicketId;
import com.flowdesk.domain.ticket.UserId;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
 *
 * <h2>内容协商</h2>
 * <p>本接口<b>只</b>产出 {@code application/json}，该约束声明在类级 {@code produces} 上，
 * 因此由 {@code RequestMappingHandlerMapping} 在进入任何 Controller 方法<b>之前</b>求值：
 * 无法接受的 {@code Accept} 会直接抛出 {@link HttpMediaTypeNotAcceptableException}，
 * 既不会执行用例，也不会残留 {@code ETag}、{@code Location} 等成功响应头。
 * 该异常由 {@code FrameworkExceptionHandler} 统一映射为 406 {@code NOT_ACCEPTABLE}。</p>
 */
@RestController
@RequestMapping(path = TicketController.BASE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
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
     * 分页查询 / 条件搜索工单。
     *
     * <p>全部查询参数都是可选的，缺省即「不过滤」；默认按 {@code updatedAt} 倒序、每页 20 条。
     * 所有筛选条件以 <b>AND</b> 组合。越界页返回 200 与空 {@code items}，不是 404。</p>
     *
     * <p><b>错误约定</b>：非整数格式（如 {@code page=abc}）属于线格式问题，在本层直接拒绝；
     * 取值范围、枚举取值、字符串规范化规则全部由<b>应用用例</b>裁决 ——
     * 本层只把用例抛出的 {@code INVALID_QUERY} 翻译成 HTTP 契约要求的
     * 400 {@code INVALID_REQUEST}，<b>不重复执行</b>应用层校验
     * （见 {@link #searchOrTranslate}）。两类失败在响应上完全一致：
     * 400 + {@code code=INVALID_REQUEST} + {@code application/problem+json}，
     * 且文案固定、不回显客户端原始输入。</p>
     *
     * <p>本方法<b>不</b>返回 ETag：集合没有单一版本号（见 {@link TicketPageResponse}）。</p>
     *
     * @param page       页码，从 0 开始；缺省 0
     * @param size       每页条数，1～100；缺省 20
     * @param status     状态精确匹配
     * @param category   分类精确匹配
     * @param priority   优先级精确匹配
     * @param requesterId 请求人精确匹配（strip 后）
     * @param assigneeId  处理人精确匹配（strip 后）
     * @param keyword     标题 / 描述包含搜索（strip 后，大小写不敏感）
     * @param sortBy      排序字段：{@code createdAt}、{@code updatedAt}、{@code priority}、{@code status}
     * @param direction   排序方向：{@code asc}、{@code desc}
     * @return 200 OK，分页结果
     */
    @GetMapping
    public ResponseEntity<TicketPageResponse> list(
            @RequestParam(name = "page", required = false) String page,
            @RequestParam(name = "size", required = false) String size,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "priority", required = false) String priority,
            @RequestParam(name = "requesterId", required = false) String requesterId,
            @RequestParam(name = "assigneeId", required = false) String assigneeId,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "sortBy", required = false) String sortBy,
            @RequestParam(name = "direction", required = false) String direction) {

        SearchTicketsQuery query = new SearchTicketsQuery(
                parseOptionalInt(page, "page"),
                parseOptionalInt(size, "size"),
                status,
                category,
                priority,
                requesterId,
                assigneeId,
                keyword,
                sortBy,
                direction);

        return ResponseEntity.ok(TicketPageResponse.from(searchOrTranslate(query)));
    }

    /**
     * 调用查询输入端口，并把「查询条件不合法」精确翻译成 HTTP 契约。
     *
     * <p><b>校验只做一次</b>：规范化与校验是应用用例的职责，HTTP 层不再自己跑一遍，
     * 否则规则会出现两份实现、也容易出现「接口拒绝但用例接受」这类分歧；
     * 这里只做<b>异常语义的翻译</b>（应用层的 {@code INVALID_QUERY} →
     * 列表接口对外承诺的 {@code INVALID_REQUEST}），并且只翻译这一个错误码，
     * 其余应用层错误原样向上抛，交给 {@code TicketExceptionHandler} 的既有映射。</p>
     *
     * @param query 原始查询条件
     * @return 分页视图
     * @throws InvalidRequestException 查询条件不合法（400 {@code INVALID_REQUEST}）
     */
    private TicketPageView searchOrTranslate(SearchTicketsQuery query) {
        try {
            return this.ticketQueryUseCase.search(query);
        } catch (TicketApplicationException ex) {
            if (ex.errorCode() == TicketApplicationErrorCode.INVALID_QUERY) {
                throw new InvalidRequestException(ex.getMessage());
            }
            throw ex;
        }
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
    public ResponseEntity<TicketResponse> get(@PathVariable String ticketId) {
        return ok(this.ticketQueryUseCase.get(new GetTicketQuery(parseTicketId(ticketId))));
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
    public ResponseEntity<TicketResponse> assign(@PathVariable String ticketId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody AssignTicketRequest request) {

        long expectedVersion = TicketEtag.requireVersion(ifMatch);
        return ok(this.ticketCommandUseCase.assign(new AssignTicketCommand(parseTicketId(ticketId),
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
    public ResponseEntity<TicketResponse> reassign(@PathVariable String ticketId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody AssignTicketRequest request) {

        long expectedVersion = TicketEtag.requireVersion(ifMatch);
        return ok(this.ticketCommandUseCase.reassign(new ReassignTicketCommand(parseTicketId(ticketId),
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
    public ResponseEntity<TicketResponse> start(@PathVariable String ticketId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {

        long expectedVersion = TicketEtag.requireVersion(ifMatch);
        return ok(this.ticketCommandUseCase.start(new StartTicketCommand(parseTicketId(ticketId), expectedVersion)));
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
    public ResponseEntity<TicketResponse> resolve(@PathVariable String ticketId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ResolveTicketRequest request) {

        long expectedVersion = TicketEtag.requireVersion(ifMatch);
        return ok(this.ticketCommandUseCase.resolve(new ResolveTicketCommand(parseTicketId(ticketId),
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
    public ResponseEntity<TicketResponse> close(@PathVariable String ticketId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {

        long expectedVersion = TicketEtag.requireVersion(ifMatch);
        return ok(this.ticketCommandUseCase.close(new CloseTicketCommand(parseTicketId(ticketId), expectedVersion)));
    }

    /**
     * 解析可选的整数查询参数。
     *
     * <p>线索格式（是不是整数）在这里判定，取值<b>范围</b>留给应用层校验器 ——
     * 这样「什么算合法查询」只有一份规则。解析失败抛出的是固定文案，
     * 不回显客户端传入的内容。</p>
     */
    private static Integer parseOptionalInt(String rawValue, String parameterName) {
        if (rawValue == null) {
            return null;
        }
        try {
            return Integer.valueOf(rawValue.strip());
        } catch (NumberFormatException ex) {
            throw new InvalidRequestException(parameterName + " 必须是整数");
        }
    }

    /**
     * 严格解析路径中的工单标识。
     *
     * <p>刻意绑定为 {@code String} 而不是 {@code UUID}：Spring 默认的 UUID 转换是宽松的，
     * 会把 {@code 1-1-1-1-1} 这类缩写形式解析成一个看起来正常的 UUID。这里按规范形式
     * （36 位连字符，忽略大小写）严格校验，失败统一按 400 {@code INVALID_REQUEST} 处理。</p>
     */
    private static TicketId parseTicketId(String rawTicketId) {
        try {
            return TicketId.parse(rawTicketId);
        } catch (TicketDomainException ex) {
            throw new InvalidRequestException("ticketId 必须是规范的 36 位 UUID");
        }
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
