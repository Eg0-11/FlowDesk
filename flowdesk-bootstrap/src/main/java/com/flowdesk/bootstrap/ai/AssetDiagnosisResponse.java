package com.flowdesk.bootstrap.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.flowdesk.application.ai.AssetDiagnosisResult;
import java.util.List;

/**
 * 资产诊断响应体（FD-0017-B）。
 *
 * <p>把 {@link AssetDiagnosisResult} 的三层信息原样交给调用方，一层都不合并：</p>
 * <ol>
 *   <li><b>答案</b>：{@code answer} 与它<b>实际引用</b>的编号 {@code usedEvidenceIds}；</li>
 *   <li><b>两侧查询的真实状态</b>：{@code asset} 与 {@code monitoring}，各自保留
 *       {@code outcome}，失败时保留稳定 {@code failure}；</li>
 *   <li><b>是否建立在证据上</b>：{@code grounded}。</li>
 * </ol>
 *
 * <p><b>为什么两侧状态必须一起返回</b>：{@code grounded=true} 只说明「答案用了本次给出的证据」，
 * 不等于「两侧依赖都成功」。部分命中（一侧 {@code FOUND}、另一侧 {@code FAILED}）时
 * 答案仍然是 grounded 的，而那次诊断其实是不完整的 —— 调用方只能通过
 * {@code asset.outcome} / {@code monitoring.outcome} 判断，所以这两个字段不能省。
 * 同理，{@code FAILED} 永远不出现在 {@code NOT_FOUND} 的位置上：它们是不同的结论，
 * 需要不同的后续动作（一个可以接受，一个应当重试或升级）。</p>
 *
 * <p>刻意<b>不</b>返回：提示词或模型原始请求、MCP 原始报文、端点与会话标识、
 * 异常类名与消息、堆栈、配置与密钥。{@code usedEvidenceIds} 在这里再做一次防御性复制，
 * 因此响应对象持有后不会被外部改动影响。</p>
 *
 * @param requestId       服务端请求标识（与错误响应里的 {@code requestId} 同源）
 * @param answer          诊断文本（无可用证据时是固定降级文案）
 * @param grounded        答案是否建立在本次命中证据之上
 * @param usedEvidenceIds 答案实际引用的证据编号，按首次出现顺序去重
 * @param asset           本次资产查询的真实结果
 * @param monitoring      本次监控快照查询的真实结果
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssetDiagnosisResponse(String requestId,
                                     String answer,
                                     boolean grounded,
                                     List<String> usedEvidenceIds,
                                     AssetDiagnosisAssetResponse asset,
                                     AssetDiagnosisMonitoringResponse monitoring) {

    /**
     * @param result 资产诊断结果
     * @return 响应体
     */
    public static AssetDiagnosisResponse from(AssetDiagnosisResult result) {
        return new AssetDiagnosisResponse(
                result.requestId(),
                result.answer(),
                result.grounded(),
                List.copyOf(result.usedEvidenceIds()),
                AssetDiagnosisAssetResponse.from(result.asset()),
                AssetDiagnosisMonitoringResponse.from(result.monitoring()));
    }
}
