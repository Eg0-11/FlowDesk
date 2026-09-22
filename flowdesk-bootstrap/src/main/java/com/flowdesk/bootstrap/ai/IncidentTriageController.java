package com.flowdesk.bootstrap.ai;

import com.flowdesk.application.ai.IncidentTriageCommand;
import com.flowdesk.application.ai.IncidentTriageResult;
import com.flowdesk.application.ai.IncidentTriageUseCase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 事件研判 HTTP 接口（FD-0018-B）。
 *
 * <p>独立于 {@link AiController}、{@link KnowledgeAnswerController} 与
 * {@link AssetDiagnosisController}：研判有自己的请求形状、响应结构与状态语义
 * （三个证据来源、一条真实执行轨迹、以及「没有证据也是 200」），混进通用 AI 控制器
 * 会让几个接口的契约互相牵制。</p>
 *
 * <p>本类只做 DTO 转换：不注入 Graph（{@code CompiledGraph}）、不注入 {@code ChatClient}、
 * 不注入三个证据来源端口、不注入数据库与 MCP 客户端，也不注入任何编排实现类 ——
 * 唯一协作者是应用层用例接口 {@link IncidentTriageUseCase}。因此 HTTP 层无法决定图怎么走、
 * 无法选择工具，也无法自己发起一次查询或一次模型调用。输入校验同理<b>不在</b>这一层做：
 * 编号规则与检索输入规则分别只存在于 application 层的 {@code AssetIdentifier} 与检索用例，
 * 这里<b>不</b>复制、<b>不</b> trim、<b>不</b>补默认值。</p>
 *
 * <p>异常分类也<b>不</b>在这一层做：{@code AiRequestException} → 400、
 * {@code AiProviderException} → 502（携带 {@code requestId}、固定安全文案）由全局
 * {@code AiExceptionHandler} 统一处理，这里的控制器不声明任何异常处理器，
 * 也不把上游异常重新分类。</p>
 *
 * <h2>开关</h2>
 * <p>整个控制器只在 {@code flowdesk.ai.enabled=true} 时注册：默认 profile 下
 * <b>不存在</b>这个端点（404），也就不可能发起任何模型调用。</p>
 *
 * <h2>状态语义</h2>
 * <ul>
 *   <li>可审计的研判结果一律 <b>200</b> —— 完整证据、部分证据、三个来源全部
 *       {@code NOT_FOUND}，以及「没有任何命中且至少一个来源失败」的固定降级结果。
 *       <b>200 不代表三个依赖都成功</b>：调用方必须读 {@code knowledge.status} /
 *       {@code asset.outcome} / {@code monitoring.outcome} 与 {@code failure}；</li>
 *   <li>非法请求（含空 body、{@code {}}、非法 {@code assetId}、非法
 *       {@code question}/{@code topK}/{@code minScore}）→ 400 {@code INVALID_REQUEST}，
 *       文案由用例给出，此时三个来源与模型<b>零调用</b>；</li>
 *   <li>请求体不是合法 JSON → 400 {@code INVALID_REQUEST}「请求体不是合法 JSON」
 *       （全局 JSON 契约，本接口不改动未知字段/类型转换/重复字段的既有规则）；</li>
 *   <li>模型调用失败、引用校验失败、Graph 执行失败或端口契约违约 →
 *       502 {@code AI_PROVIDER_ERROR} + {@code requestId}，detail 固定、不含 cause，
 *       也不回显模型答案；</li>
 *   <li>{@code Content-Type} / {@code Accept} 不受支持 → 415 / 406（全局媒体类型契约）；
 *       AI 未启用 → 端点不注册，404。</li>
 * </ul>
 */
@RestController
@RequestMapping(path = IncidentTriageController.BASE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(name = "flowdesk.ai.enabled", havingValue = "true")
public class IncidentTriageController {

    /** 统一前缀（与其它 AI 接口相同的前缀，不同路径）。 */
    static final String BASE_PATH = "/api/v1/ai";

    private final IncidentTriageUseCase incidentTriageUseCase;

    /**
     * @param incidentTriageUseCase 事件研判用例
     */
    public IncidentTriageController(IncidentTriageUseCase incidentTriageUseCase) {
        this.incidentTriageUseCase = incidentTriageUseCase;
    }

    /**
     * 研判一次事件。
     *
     * <p>空请求体按「四个字段全为 {@code null}」交给用例：于是得到与 {@code {}} 完全相同的
     * 400（非法 {@code assetId} 的固定文案），而不是「缺少请求体」这类由容器决定的通用错误。
     * 用例对一次可解析的请求最多被调用<b>一次</b>。</p>
     *
     * @param request 研判请求体（可为 {@code null}）
     * @return 200 OK + 答案、实际引用、真实执行路径与三个来源的真实状态
     */
    @PostMapping(path = "/incident-triage", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<IncidentTriageResponse> triage(
            @RequestBody(required = false) IncidentTriageRequest request) {

        IncidentTriageCommand command = request == null
                ? new IncidentTriageCommand(null, null, null, null)
                : new IncidentTriageCommand(request.assetId(), request.question(), request.topK(),
                        request.minScore());
        IncidentTriageResult result = this.incidentTriageUseCase.triage(command);
        return ResponseEntity.ok(IncidentTriageResponse.from(result));
    }
}
