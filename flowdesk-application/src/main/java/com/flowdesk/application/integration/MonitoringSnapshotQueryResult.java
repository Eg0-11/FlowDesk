package com.flowdesk.application.integration;

/**
 * 监控快照查询结果（FD-0016）。
 *
 * <p>三态与资产查询一致：{@link QueryOutcome#FOUND}（带 {@link MonitoringSnapshotView}）、
 * {@link QueryOutcome#NOT_FOUND}（带被查询的编号与来源）、
 * {@link QueryOutcome#FAILED}（带稳定的失败分类）。</p>
 *
 * @param outcome  结果三态
 * @param snapshot 命中的快照；仅 {@code FOUND} 时非 null（{@code FOUND} 的来源从它的 {@code source} 读）
 * @param assetId  被查询的资产标识；仅 {@code NOT_FOUND} 时非 null
 * @param source   数据来源；<b>仅 {@code NOT_FOUND} 时非 null</b> —— {@code FOUND} 的来源在
 *                 {@code snapshot} 里而不在结果顶层，{@code FAILED} 既不携带快照也不携带来源
 * @param failure  失败分类；仅 {@code FAILED} 时非 null
 */
public record MonitoringSnapshotQueryResult(QueryOutcome outcome, MonitoringSnapshotView snapshot, String assetId,
        SourceOrigin source, QueryFailure failure) {

    /**
     * @throws IllegalArgumentException 结果自相矛盾
     */
    public MonitoringSnapshotQueryResult {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome 不能为空");
        }
        switch (outcome) {
            case FOUND -> {
                if (snapshot == null || failure != null || assetId != null || source != null) {
                    throw new IllegalArgumentException("FOUND 只能携带 snapshot");
                }
            }
            case NOT_FOUND -> {
                if (snapshot != null || failure != null || assetId == null || source == null) {
                    throw new IllegalArgumentException("NOT_FOUND 必须携带 assetId 与 source");
                }
            }
            case FAILED -> {
                if (snapshot != null || assetId != null || source != null || failure == null) {
                    throw new IllegalArgumentException("FAILED 只能携带 failure");
                }
            }
        }
    }

    /**
     * @param snapshot 命中的快照
     * @return 命中结果
     */
    public static MonitoringSnapshotQueryResult found(MonitoringSnapshotView snapshot) {
        return new MonitoringSnapshotQueryResult(QueryOutcome.FOUND, snapshot, null, null, null);
    }

    /**
     * @param assetId 被查询的资产标识
     * @param source  远端给出的来源
     * @return 未找到结果（来源非 null）
     */
    public static MonitoringSnapshotQueryResult notFound(String assetId, SourceOrigin source) {
        return new MonitoringSnapshotQueryResult(QueryOutcome.NOT_FOUND, null, assetId, source, null);
    }

    /**
     * @param failure 稳定失败分类
     * @return 失败结果
     */
    public static MonitoringSnapshotQueryResult failed(QueryFailure failure) {
        return new MonitoringSnapshotQueryResult(QueryOutcome.FAILED, null, null, null, failure);
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
     * 命中的快照。
     *
     * @return 快照
     * @throws IllegalStateException 结果不是 {@code FOUND}
     */
    public MonitoringSnapshotView requireSnapshot() {
        if (this.snapshot == null) {
            throw new IllegalStateException("结果不是 FOUND：" + this.outcome);
        }
        return this.snapshot;
    }
}
