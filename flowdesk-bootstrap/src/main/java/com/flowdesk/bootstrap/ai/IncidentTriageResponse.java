package com.flowdesk.bootstrap.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.flowdesk.application.ai.IncidentTriageResult;
import java.util.List;

/**
 * 事件研判响应体（FD-0018-B）。
 *
 * <p>把 {@link IncidentTriageResult} 的四层信息原样交给调用方，<b>一层都不合并、一个字段都不重算</b>：</p>
 * <ol>
 *   <li><b>答案</b>：{@code answer} 与它<b>实际引用</b>的编号 {@code usedEvidenceIds}；</li>
 *   <li><b>真实执行轨迹</b>：{@code executionPath}（Graph 节点在执行时自己追加的序列）；</li>
 *   <li><b>三个来源的真实状态</b>：{@code knowledge} / {@code asset} / {@code monitoring}，
 *       各自保留自己的状态与稳定失败分类；</li>
 *   <li><b>是否建立在证据上</b>：{@code grounded}。</li>
 * </ol>
 *
 * <p><b>{@code requestId} 与 {@code grounded} 都取自用例返回的结果，不在这里重新生成或重新推断</b>：
 * 后者与 {@code usedEvidenceIds} 是否为空严格等价（由 {@code IncidentTriageResult} 的构造期不变量强制），
 * HTTP 层再推一次只会多出一个可能与结果不一致的判断点。同理
 * {@code usedEvidenceIds} 与 {@code executionPath} 在这里再做一次防御性复制，
 * 响应对象被持有后不会再受外部改动影响。</p>
 *
 * <p><b>为什么三个来源状态必须一起返回</b>：{@code grounded=true} 只说明「答案用了本次给出的证据」，
 * 不等于三个依赖都成功。部分命中（例如知识 {@code FAILED}、资产与监控命中）时答案依然是
 * grounded 的，而那次研判其实是不完整的 —— 调用方只能通过
 * {@code knowledge.status} / {@code asset.outcome} / {@code monitoring.outcome} 判断。
 * 同理 {@code FAILED}/{@code DISABLED} 永远不会出现在 {@code NOT_FOUND} 的位置上。</p>
 *
 * <p>资产与监控两侧直接复用已验收的映射能力（{@link AssetDiagnosisAssetResponse} /
 * {@link AssetDiagnosisMonitoringResponse}），因此字段契约与资产诊断接口逐字段一致；
 * 知识侧复用 {@link IncidentTriageKnowledgeResponse}。取值为 {@code null} 的字段由
 * {@link JsonInclude.Include#NON_NULL} 省略，而不是输出一堆需要调用方额外解释的空值。</p>
 *
 * <p>刻意<b>不</b>返回：{@code question} 原文、输入命令、向量、Graph 内部状态与调用上下文、
 * 来源查询进度（{@code sourceProgress}）、输入拒绝记录、异常与 cause、SQL、端点与会话标识、
 * 凭证以及 MCP 原始报文。响应由 HTTP 层显式构造，应用层与 agent 内部对象
 * <b>不直接交给 Jackson</b>。</p>
 *
 * @param requestId       服务端请求标识（与错误响应里的 {@code requestId} 同源）
 * @param answer          研判文本（无可用证据时是固定降级文案）
 * @param grounded        答案是否建立在本次证据之上
 * @param usedEvidenceIds 答案实际引用的证据编号，按首次出现顺序去重
 * @param executionPath   Graph 真实执行过的节点名序列
 * @param knowledge       知识分支的真实状态
 * @param asset           本次资产查询的真实状态
 * @param monitoring      本次监控快照查询的真实状态
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IncidentTriageResponse(String requestId,
                                     String answer,
                                     boolean grounded,
                                     List<String> usedEvidenceIds,
                                     List<String> executionPath,
                                     IncidentTriageKnowledgeResponse knowledge,
                                     AssetDiagnosisAssetResponse asset,
                                     AssetDiagnosisMonitoringResponse monitoring) {

    /**
     * @param result 事件研判结果
     * @return 响应体
     */
    public static IncidentTriageResponse from(IncidentTriageResult result) {
        return new IncidentTriageResponse(
                result.requestId(),
                result.answer(),
                result.grounded(),
                List.copyOf(result.usedEvidenceIds()),
                List.copyOf(result.executionPath()),
                IncidentTriageKnowledgeResponse.from(result.knowledge()),
                AssetDiagnosisAssetResponse.from(result.asset()),
                AssetDiagnosisMonitoringResponse.from(result.monitoring()));
    }
}
