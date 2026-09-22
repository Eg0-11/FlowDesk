package com.flowdesk.application.ai;

/**
 * 事件研判用例（FD-0018-A）。
 *
 * <p>把三个只读证据来源（企业知识检索、资产查询、最新监控快照）放进一条<b>真正由
 * Spring AI Alibaba StateGraph 执行</b>的编排里，最后由 DeepSeek 生成一次可审计的中文研判。</p>
 *
 * <p>编排权在代码里而不在模型手里：模型不能选择工具、不能改编号、不能跳过任何一个证据节点、
 * 也不会在请求里收到任何工具定义。输入不合法抛 {@link AiRequestException}（下游零调用）；
 * 模型调用、引用校验或图执行失败抛 {@link AiProviderException}（携带本次 {@code requestId}）。</p>
 */
public interface IncidentTriageUseCase {

    /**
     * 研判一次事件。
     *
     * @param command 研判命令（可为 {@code null}；为空或其字段不合法时抛
     *                {@link AiRequestException}，且此时三个证据来源与模型都不会被调用）
     * @return 可审计的研判结果
     */
    IncidentTriageResult triage(IncidentTriageCommand command);
}
