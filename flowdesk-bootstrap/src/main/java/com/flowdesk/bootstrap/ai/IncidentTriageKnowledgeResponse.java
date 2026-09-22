package com.flowdesk.bootstrap.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.flowdesk.application.ai.KnowledgeEvidence;
import com.flowdesk.bootstrap.knowledge.KnowledgeSearchResponse;

/**
 * 知识分支的 HTTP 形状（FD-0018-B）。
 *
 * <p>与资产/监控两侧同一条规则：<b>字段集合由状态决定</b>，出问题的分支不会被伪装成
 * 「检索成功但没有命中」。</p>
 *
 * <table border="1">
 *   <caption>按 status 决定的字段</caption>
 *   <tr><th>status</th><th>输出字段</th></tr>
 *   <tr><td>{@code FOUND}</td><td>{@code status}、{@code retrieval}（{@code citations} 非空）</td></tr>
 *   <tr><td>{@code NOT_FOUND}</td><td>{@code status}、{@code retrieval}（{@code citations} 为空列表）</td></tr>
 *   <tr><td>{@code FAILED}</td>
 *       <td>{@code status}、{@code failure}（<b>不</b>输出 {@code retrieval}，也<b>不</b>伪造一次空的检索成功）</td></tr>
 * </table>
 *
 * <p>{@code retrieval} 直接复用检索接口自己的响应体 {@link KnowledgeSearchResponse}：
 * 同一份「本次生效的检索参数 + 排序模式 + 引用」的形状只维护一处，
 * {@code rerankModel} 在未使用重排时同样被 {@link JsonInclude.Include#NON_NULL} 省略，
 * 因此这里与检索接口的字段契约逐字段一致，不需要（也不应该）另写一套映射。</p>
 *
 * <p>刻意<b>不</b>返回：{@code question} 原文、向量、SQL、异常类名与消息、堆栈、端点或密钥。
 * {@code citations} 是回给调用方的审计证据，比送给模型的证据字段<b>更完整</b>
 * （模型只拿到 {@code citationId}/{@code documentTitle}/{@code chunkIndex}/{@code content}）；
 * 这是两条不同的输出通道，核心层「发给模型的字段更少」的设计不变。</p>
 *
 * @param status    三态：{@code FOUND} / {@code NOT_FOUND} / {@code FAILED}
 * @param retrieval 检索视图；仅 {@code FOUND} 与 {@code NOT_FOUND}
 * @param failure   稳定失败分类；仅 {@code FAILED}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IncidentTriageKnowledgeResponse(String status,
                                              KnowledgeSearchResponse retrieval,
                                              String failure) {

    /**
     * 把应用层的知识分支映射成 HTTP 形状。
     *
     * @param evidence 知识证据分支（非 {@code null}）
     * @return 响应片段
     */
    public static IncidentTriageKnowledgeResponse from(KnowledgeEvidence evidence) {
        return switch (evidence.status()) {
            case FOUND, NOT_FOUND -> new IncidentTriageKnowledgeResponse(evidence.status().name(),
                    KnowledgeSearchResponse.from(evidence.retrieval()), null);
            case FAILED -> new IncidentTriageKnowledgeResponse(evidence.status().name(), null,
                    evidence.failure().name());
        };
    }
}
