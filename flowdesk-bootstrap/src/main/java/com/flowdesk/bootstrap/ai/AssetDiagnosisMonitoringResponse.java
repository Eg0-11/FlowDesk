package com.flowdesk.bootstrap.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.flowdesk.application.integration.MonitoringSnapshotQueryResult;
import com.flowdesk.application.integration.MonitoringSnapshotView;

/**
 * 监控侧查询结果的 HTTP 形状（FD-0017-B）。
 *
 * <p>与 {@link AssetDiagnosisAssetResponse} 同一条规则：字段集合由 {@code outcome} 决定，
 * 未命中的一侧<b>不会</b>出现一个「字段都在、值都是 null」的对象。</p>
 *
 * <table border="1">
 *   <caption>按 outcome 决定的字段</caption>
 *   <tr><th>outcome</th><th>输出字段</th></tr>
 *   <tr><td>{@code FOUND}</td>
 *       <td>{@code outcome}、{@code assetId}、{@code observedAt}、{@code health}、
 *           {@code cpuUtilizationPercent}、{@code memoryUtilizationPercent}、{@code activeAlertCount}、
 *           {@code source}</td></tr>
 *   <tr><td>{@code NOT_FOUND}</td>
 *       <td>{@code outcome}、{@code assetId}、{@code source}（<b>不</b>伪造监控数值）</td></tr>
 *   <tr><td>{@code FAILED}</td>
 *       <td>{@code outcome}、{@code failure}（<b>不</b>输出编号、来源或伪造数据）</td></tr>
 * </table>
 *
 * <p>三点刻意的选择：</p>
 * <ul>
 *   <li><b>数值用包装类型</b>：{@code Integer} 而不是 {@code int}，否则未命中时它们会被序列化成
 *       {@code 0}，那看起来就像「CPU 使用率 0%、告警 0 条」—— 一个健康得不真实的快照；</li>
 *   <li><b>{@code observedAt} 是字符串</b>：由 {@code Instant.toString()} 显式产出 ISO-8601 UTC，
 *       不依赖 Jackson 的日期配置，因此契约不会随序列化设置漂移；</li>
 *   <li><b>{@code health} 与 {@code source} 是稳定枚举名</b>，直接来自应用层枚举，不在这里做映射表。</li>
 * </ul>
 *
 * @param outcome                 结果三态：{@code FOUND} / {@code NOT_FOUND} / {@code FAILED}
 * @param assetId                 被查询的资产标识；仅 {@code FOUND} 与 {@code NOT_FOUND}
 * @param observedAt              观测时刻（ISO-8601 UTC）；仅 {@code FOUND}
 * @param health                  健康状态；仅 {@code FOUND}
 * @param cpuUtilizationPercent   CPU 使用率（0..100）；仅 {@code FOUND}
 * @param memoryUtilizationPercent 内存使用率（0..100）；仅 {@code FOUND}
 * @param activeAlertCount        活跃告警数（&gt;= 0）；仅 {@code FOUND}
 * @param source                  数据来源（{@code DEMO}/{@code REAL}）；{@code FOUND} 与 {@code NOT_FOUND} 都有
 * @param failure                 稳定失败分类；仅 {@code FAILED}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssetDiagnosisMonitoringResponse(String outcome,
                                               String assetId,
                                               String observedAt,
                                               String health,
                                               Integer cpuUtilizationPercent,
                                               Integer memoryUtilizationPercent,
                                               Integer activeAlertCount,
                                               String source,
                                               String failure) {

    /**
     * 把应用层的监控快照查询结果映射成 HTTP 形状。
     *
     * @param result 监控快照查询结果（非 {@code null}；编排层已把违约挡在结果之外）
     * @return 响应片段
     */
    public static AssetDiagnosisMonitoringResponse from(MonitoringSnapshotQueryResult result) {
        return switch (result.outcome()) {
            case FOUND -> {
                MonitoringSnapshotView snapshot = result.requireSnapshot();
                yield new AssetDiagnosisMonitoringResponse(result.outcome().name(), snapshot.assetId(),
                        snapshot.observedAt().toString(), snapshot.health().name(),
                        snapshot.cpuUtilizationPercent(), snapshot.memoryUtilizationPercent(),
                        snapshot.activeAlertCount(), snapshot.source().name(), null);
            }
            case NOT_FOUND -> new AssetDiagnosisMonitoringResponse(result.outcome().name(), result.assetId(), null,
                    null, null, null, null, result.source().name(), null);
            case FAILED -> new AssetDiagnosisMonitoringResponse(result.outcome().name(), null, null, null, null, null,
                    null, null, result.failure().name());
        };
    }
}
