package com.flowdesk.bootstrap.ai;

/**
 * 资产诊断请求体（FD-0017-B）。
 *
 * <p>只有一个字段，而且这里<b>不做</b>任何校验（不用 Bean Validation、不复制 {@code AssetIdentifier}
 * 的规则）：编号的形状、去空白与长度上限统一由资产诊断用例判定，因此
 * {@code POST /api/v1/ai/asset-diagnosis} 与直接调用用例的输入语义不会分叉。
 * 在 HTTP 层再写一份正则，等于给同一份契约留下第二个会各自漂移的副本。</p>
 *
 * <p>请求体缺失（空 body）时控制器按 {@code assetId=null} 处理，与 {@code {}} 得到同一条
 * 400「{@code assetId 必须形如 AST-000001}」契约。</p>
 *
 * @param assetId 资产标识（{@code AST-} 加六位数字）
 */
public record AssetDiagnosisRequest(String assetId) {
}
