package com.flowdesk.agent.ai;

/**
 * 资产诊断的稳定失败类别（FD-0017-A）。
 *
 * <p>这些取值是<b>固定词汇</b>：只进日志与内部异常，不含远端原文、提示词或模型回答。
 * 「查询没查成」不属于这里 —— 那是两个查询端口的 {@code QueryFailure}，
 * 会如实出现在诊断结果里，而不是被当成诊断链路的失败。</p>
 */
public enum AssetDiagnosisFailure {

    /** 输入不合法（{@code null} 命令、空白或不满足 {@code AST-[0-9]{6}} 的编号）：在任何端口与模型调用之前拒绝。 */
    INVALID_INPUT,

    /** 模型返回空答案（或只有空白）。 */
    ANSWER_EMPTY,

    /** 有命中证据，但答案里一个引用都没有。 */
    ANSWER_WITHOUT_CITATION,

    /** 引用形式畸形：小写、前导零、空格、后缀字符、未闭合、或出现 A1/M1 之外的编号形态。 */
    INVALID_CITATION_FORMAT,

    /** 引用形式规范，但本次并没有给出这个证据（例如资产未命中却引用 [A1]）。 */
    UNKNOWN_CITATION,

    /** 本次命中的证据没有被全部引用：结论无法逐条回溯到证据。 */
    EVIDENCE_NOT_CITED,

    /** 调用模型失败（异常、超时或上游错误）。 */
    MODEL_CALL_FAILED,

    /** 查询端口违约抛异常（按契约它们只返回三态结果，不抛异常）。 */
    PORT_CONTRACT_VIOLATION
}
