package com.flowdesk.agent.ai;

/**
 * 答案引用校验的稳定失败类别（RAG 5/6）。
 *
 * <p>用途只有一个：<b>日志与排障</b>。它不进入响应体（对外统一是 502 {@code AI_PROVIDER_ERROR}），
 * 也不携带任何模型原文。类别固定，因此告警可以按类别聚合。</p>
 */
public enum GroundedAnswerFailure {

    /** 模型返回空答案（strip 之后为空）。 */
    ANSWER_EMPTY,

    /** 有证据但答案里一个规范引用都没有。 */
    ANSWER_WITHOUT_CITATION,

    /** 出现了非规范引用形式：{@code [K0]}、{@code [K01]}、{@code [K]} 等。 */
    INVALID_CITATION_FORMAT,

    /** 引用了本次检索没有给出的编号（例如 {@code [K999]}）。 */
    UNKNOWN_CITATION,

    /** 模型调用本身失败（上游错误、超时、空响应）。 */
    MODEL_CALL_FAILED
}
