package com.flowdesk.agent.ai;

/**
 * 事件研判编排的稳定失败类别（FD-0018-A）。
 *
 * <p>这些取值是固定词汇：只进日志与内部异常，不含 assetId、问题原文、知识正文、资产/监控字段、
 * 提示词、模型回答、异常消息、端点或堆栈。三个<b>证据来源</b>自身的失败不在这里 ——
 * 它们以各自的三态（{@code KnowledgeFailure}、{@code QueryFailure}）如实出现在结果里。</p>
 */
public enum IncidentTriageFailure {

    /** 输入不合法（assetId 或检索入参）：在任何证据节点与模型调用之前拒绝。 */
    INVALID_INPUT,

    /** 依赖返回 {@code null} 或违反端口契约：能执行的后续证据节点仍执行，但调用模型前统一失败。 */
    PORT_CONTRACT_VIOLATION,

    /** Graph 框架异常、最终状态缺失或状态类型不符（不向调用方泄漏框架类名）。 */
    GRAPH_FAILURE,

    /** 调用模型失败（异常、超时或上游错误）。 */
    MODEL_CALL_FAILED,

    /** 模型返回空答案。 */
    ANSWER_EMPTY,

    /** 有可用证据，但答案一个引用都没有。 */
    ANSWER_WITHOUT_CITATION,

    /** 引用形式畸形（小写、前导零、空格、后缀字符、未闭合、组合编号等）。 */
    INVALID_CITATION_FORMAT,

    /** 引用了本次并不存在的编号。 */
    UNKNOWN_CITATION,

    /** 某个本次有证据的族（知识/资产/监控）一条都没被引用。 */
    EVIDENCE_FAMILY_NOT_CITED
}
