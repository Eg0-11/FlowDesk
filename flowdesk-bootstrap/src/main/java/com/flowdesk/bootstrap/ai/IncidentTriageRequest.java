package com.flowdesk.bootstrap.ai;

/**
 * 事件研判请求体（FD-0018-B）。
 *
 * <p>四个字段在这里<b>不做</b>任何校验、不做 trim、不做规范化、也不补默认值：
 * {@code assetId} 的规则只存在于 application 层的 {@code AssetIdentifier}（由 {@code validate_asset}
 * 节点调用），{@code question}/{@code topK}/{@code minScore} 的规则只存在于既有检索用例
 * （{@code RetrieveKnowledgeUseCase}）。在 HTTP 层再写一份校验，等于给同一份输入契约留下
 * 第二个会各自漂移的副本。</p>
 *
 * <p>请求体缺失（空 body）时控制器按「四个字段全为 {@code null}」的命令处理，
 * 与 {@code {}} 得到完全相同的判定结果。</p>
 *
 * @param assetId  资产标识（{@code AST-} 加六位数字）
 * @param question 事件/问题原文（不可信数据）
 * @param topK     检索返回条数上限；省略或 {@code null} 表示用检索默认值
 * @param minScore 相似度下限；省略或 {@code null} 表示用检索默认值
 */
public record IncidentTriageRequest(String assetId, String question, Integer topK, Double minScore) {
}
