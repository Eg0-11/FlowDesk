package com.flowdesk.application.ai;

/**
 * 资产诊断用例（FD-0017-A）。
 *
 * <p>把「资产记录」与「监控快照」两份只读证据固定地收集起来，交给模型生成可审计的中文运维诊断。
 * 流程由 Agent 层<b>确定性</b>控制：模型不能选择工具、不能改编号、也不能跳过某次查询 ——
 * 两个查询端口在编排层被各调用一次（顺序固定为资产 → 监控），模型只负责在给定证据上作答。</p>
 *
 * <p>输入不合法时抛 {@link AiRequestException}；模型调用或引用校验失败抛
 * {@link AiProviderException}（携带本次 {@code requestId}）。</p>
 */
public interface AssetDiagnosisUseCase {

    /**
     * 诊断一个资产的健康状况。
     *
     * @param command 诊断命令（可为 {@code null}；为空或其 {@code assetId} 不合法时抛
     *                {@link AiRequestException}，且此时两个查询端口与模型都不会被调用）
     * @return 可审计的诊断结果
     */
    AssetDiagnosisResult diagnose(AssetDiagnosisCommand command);
}
