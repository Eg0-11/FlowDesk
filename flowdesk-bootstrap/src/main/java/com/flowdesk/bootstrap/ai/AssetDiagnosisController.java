package com.flowdesk.bootstrap.ai;

import com.flowdesk.application.ai.AssetDiagnosisCommand;
import com.flowdesk.application.ai.AssetDiagnosisResult;
import com.flowdesk.application.ai.AssetDiagnosisUseCase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 资产诊断 HTTP 接口（FD-0017-B）。
 *
 * <p>独立于 {@link AiController} 与 {@link KnowledgeAnswerController}：诊断有自己的请求形状、
 * 响应结构与状态语义（例如 {@code FAILED} 也可能是 200），混进通用 AI 控制器会让三个接口的契约
 * 互相牵制。</p>
 *
 * <p>本类只做 DTO 转换：不注入 {@code ChatClient}、不注入资产/监控查询端口、不注入 MCP 客户端，
 * 也不注入任何编排实现类 —— 唯一协作者是应用层用例接口。因此 HTTP 层无法决定编排顺序、
 * 无法选择工具，也无法自己发起一次查询。输入校验同理<b>不在</b>这一层做：
 * 编号规则只存在 application 层的 {@code AssetIdentifier}，这里再写一份正则就等于复制契约。</p>
 *
 * <h2>开关</h2>
 * <p>整个控制器只在 {@code flowdesk.ai.enabled=true} 时注册：默认 profile 下
 * <b>不存在</b>这个端点（404），也就不可能发起任何模型调用。</p>
 *
 * <h2>状态语义</h2>
 * <ul>
 *   <li>可审计的诊断结果一律 <b>200</b> —— 包括部分命中、两侧 {@code NOT_FOUND}，
 *       甚至两个查询都 {@code DISABLED}（编排正常返回固定降级结果：{@code grounded=false}，
 *       两侧各自明确显示 {@code FAILED} + {@code DISABLED}）。<b>200 不代表两个依赖都成功</b>，
 *       调用方必须看 {@code asset.outcome} / {@code monitoring.outcome} 与 {@code failure}；</li>
 *   <li>非法请求（含空 body、{@code {}}、非法 {@code assetId}）→ 400 {@code INVALID_REQUEST}
 *       （由用例判定，固定文案在用例侧）；</li>
 *   <li>请求体不是合法 JSON → 400 {@code INVALID_REQUEST}「请求体不是合法 JSON」
 *       （全局 JSON 契约）；</li>
 *   <li>模型调用失败、引用校验失败、查询端口契约违约 → 502 {@code AI_PROVIDER_ERROR}，
 *       并带上 {@code requestId}；</li>
 *   <li>AI 未启用 → 端点不存在，404。</li>
 * </ul>
 */
@RestController
@RequestMapping(path = AssetDiagnosisController.BASE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(name = "flowdesk.ai.enabled", havingValue = "true")
public class AssetDiagnosisController {

    /** 统一前缀（与 {@link AiController} 相同的前缀，不同路径）。 */
    static final String BASE_PATH = "/api/v1/ai";

    private final AssetDiagnosisUseCase assetDiagnosisUseCase;

    /**
     * @param assetDiagnosisUseCase 资产诊断用例
     */
    public AssetDiagnosisController(AssetDiagnosisUseCase assetDiagnosisUseCase) {
        this.assetDiagnosisUseCase = assetDiagnosisUseCase;
    }

    /**
     * 诊断一个资产的健康状况。
     *
     * <p>空请求体按 {@code assetId=null} 交给用例：于是得到与 {@code {}} 完全相同的
     * 400「{@code assetId 必须形如 AST-000001}」，而不是「缺少请求体」这类由容器决定的通用错误。</p>
     *
     * @param request 诊断请求体（可为 {@code null}）
     * @return 200 OK + 答案、实际引用编号与两侧查询的真实状态
     */
    @PostMapping(path = "/asset-diagnosis", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AssetDiagnosisResponse> diagnose(
            @RequestBody(required = false) AssetDiagnosisRequest request) {

        AssetDiagnosisCommand command = new AssetDiagnosisCommand(request == null ? null : request.assetId());
        AssetDiagnosisResult result = this.assetDiagnosisUseCase.diagnose(command);
        return ResponseEntity.ok(AssetDiagnosisResponse.from(result));
    }
}
