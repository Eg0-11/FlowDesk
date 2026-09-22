package com.flowdesk.application.ai;

/**
 * 事件研判命令（FD-0018-A）。
 *
 * <p>四个入参都<b>不</b>在这里校验：{@code assetId} 的规则由既有
 * {@link com.flowdesk.application.integration.AssetIdentifier} 判定，
 * {@code question}/{@code topK}/{@code minScore} 的规则由既有检索用例
 * （{@code RetrieveKnowledgeUseCase}）判定 —— 复制第二套规则只会让两处各自漂移。</p>
 *
 * @param assetId  资产标识（{@code AST-} 加六位数字）
 * @param question 事件/问题原文（不可信数据；由检索用例做规范化与合法性判定）
 * @param topK     检索返回条数上限（可为 {@code null}，表示用检索默认值）
 * @param minScore 相似度下限（可为 {@code null}，表示用检索默认值）
 */
public record IncidentTriageCommand(String assetId, String question, Integer topK, Double minScore) {
}
