package com.flowdesk.application.ai;

/**
 * 资产诊断命令（FD-0017-A）。
 *
 * <p>只有一个入参：资产标识。这里<b>不</b>做输入校验 —— 诊断用例统一调用
 * {@link com.flowdesk.application.integration.AssetIdentifier} 的既有规则，
 * 避免同一套规则在两处各写一份、日后各自漂移。</p>
 *
 * @param assetId 资产标识（{@code AST-} 加六位数字）
 */
public record AssetDiagnosisCommand(String assetId) {
}
