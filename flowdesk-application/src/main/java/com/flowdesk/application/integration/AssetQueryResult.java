package com.flowdesk.application.integration;

/**
 * 资产查询结果（FD-0016）。
 *
 * <p>三态：{@link QueryOutcome#FOUND}（带 {@link AssetView}）、{@link QueryOutcome#NOT_FOUND}
 * （带被查询的编号与远端给出的来源）、{@link QueryOutcome#FAILED}（带稳定的失败分类）。
 * 「未找到」必须带来源：演示数据说「没有」和真实资产系统说「没有」是两件不同的事。</p>
 *
 * <p>不可变，且不允许出现自相矛盾的组合 —— 构造期就会拒绝。</p>
 *
 * @param outcome 结果三态
 * @param asset   命中的记录；仅 {@code FOUND} 时非 null
 * @param assetId 被查询的资产标识；仅 {@code NOT_FOUND} 时非 null（{@code FOUND} 时从记录读）
 * @param source  数据来源；{@code FOUND} 与 {@code NOT_FOUND} 时非 null
 * @param failure 失败分类；仅 {@code FAILED} 时非 null
 */
public record AssetQueryResult(QueryOutcome outcome, AssetView asset, String assetId, SourceOrigin source,
        QueryFailure failure) {

    /**
     * @throws IllegalArgumentException 结果自相矛盾
     */
    public AssetQueryResult {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome 不能为空");
        }
        switch (outcome) {
            case FOUND -> {
                if (asset == null || failure != null || assetId != null || source != null) {
                    throw new IllegalArgumentException("FOUND 只能携带 asset");
                }
            }
            case NOT_FOUND -> {
                if (asset != null || failure != null || assetId == null || source == null) {
                    throw new IllegalArgumentException("NOT_FOUND 必须携带 assetId 与 source");
                }
            }
            case FAILED -> {
                if (asset != null || assetId != null || source != null || failure == null) {
                    throw new IllegalArgumentException("FAILED 只能携带 failure");
                }
            }
        }
    }

    /**
     * @param asset 命中的记录
     * @return 命中结果
     */
    public static AssetQueryResult found(AssetView asset) {
        return new AssetQueryResult(QueryOutcome.FOUND, asset, null, null, null);
    }

    /**
     * @param assetId 被查询的资产标识
     * @param source  远端给出的来源
     * @return 未找到结果（来源非 null）
     */
    public static AssetQueryResult notFound(String assetId, SourceOrigin source) {
        return new AssetQueryResult(QueryOutcome.NOT_FOUND, null, assetId, source, null);
    }

    /**
     * @param failure 稳定失败分类
     * @return 失败结果
     */
    public static AssetQueryResult failed(QueryFailure failure) {
        return new AssetQueryResult(QueryOutcome.FAILED, null, null, null, failure);
    }

    /** @return 是否命中 */
    public boolean isFound() {
        return this.outcome == QueryOutcome.FOUND;
    }

    /** @return 是否「查过了但没有」 */
    public boolean isNotFound() {
        return this.outcome == QueryOutcome.NOT_FOUND;
    }

    /** @return 是否失败 */
    public boolean isFailed() {
        return this.outcome == QueryOutcome.FAILED;
    }

    /**
     * 命中的记录。
     *
     * @return 记录
     * @throws IllegalStateException 结果不是 {@code FOUND}
     */
    public AssetView requireAsset() {
        if (this.asset == null) {
            throw new IllegalStateException("结果不是 FOUND：" + this.outcome);
        }
        return this.asset;
    }
}
