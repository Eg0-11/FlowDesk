package com.flowdesk.infrastructure.mcp.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowdesk.application.integration.AssetIdentifier;
import com.flowdesk.application.integration.HealthState;
import com.flowdesk.application.integration.MonitoringSnapshotQueryResult;
import com.flowdesk.application.integration.MonitoringSnapshotView;
import com.flowdesk.application.integration.QueryFailure;
import com.flowdesk.application.integration.SourceOrigin;
import java.time.Instant;
import java.util.Set;

/**
 * {@code monitoring_snapshot_get} 载荷解析（FD-0016）。
 *
 * <p>与 {@link AssetPayloadParser} 同一套严格策略，另外多了几项监控特有的校验：</p>
 * <ul>
 *   <li>{@code health} 是<b>封闭枚举</b>（{@code HEALTHY}/{@code DEGRADED}/{@code CRITICAL}/{@code UNKNOWN}），
 *       未知取值按非法响应拒绝；</li>
 *   <li>{@code observedAt} 必须是合法的 ISO-8601 时刻；</li>
 *   <li>两个百分比必须在 {@code 0..100}，{@code activeAlertCount} 必须 {@code >= 0}
 *       （整数字段不接受浮点或字符串）；</li>
 *   <li>未找到的错误码必须是 {@code MONITORING_SNAPSHOT_NOT_FOUND}；远端声明
 *       {@code MONITORING_SOURCE_UNAVAILABLE} 时映射为 {@link QueryFailure#UNAVAILABLE}。</li>
 * </ul>
 */
final class SnapshotPayloadParser {

    /** 命中形状的字段集合（顺序与远端一致，但比较用集合）。 */
    static final Set<String> HIT_FIELDS = Set.of("assetId", "observedAt", "health", "cpuUtilizationPercent",
            "memoryUtilizationPercent", "activeAlertCount", "source");

    /** 未找到形状的字段集合。 */
    static final Set<String> NOT_FOUND_FIELDS = Set.of("assetId", "found", "error", "message", "source");

    /** 远端失败形状的字段集合。 */
    static final Set<String> ERROR_FIELDS = Set.of("error", "message");

    /** 未找到的错误码。 */
    static final String NOT_FOUND_CODE = "MONITORING_SNAPSHOT_NOT_FOUND";

    /** 远端声明「监控数据源不可用」的错误码。 */
    static final String SOURCE_UNAVAILABLE_CODE = "MONITORING_SOURCE_UNAVAILABLE";

    private SnapshotPayloadParser() {
    }

    /**
     * @param requestedAssetId 请求里的资产标识（必须与响应回显一致）
     * @param response         原始工具响应
     * @return 三态结果，永不为 {@code null}
     */
    static MonitoringSnapshotQueryResult parse(String requestedAssetId, McpToolResponse response) {
        if (response.remoteError()) {
            return remoteFailure(response.text());
        }
        JsonNode payload = PayloadFields.readObject(response.text());
        if (payload == null) {
            return MonitoringSnapshotQueryResult.failed(QueryFailure.INVALID_RESPONSE);
        }
        if (PayloadFields.hasExactFields(payload, HIT_FIELDS)) {
            return hit(requestedAssetId, payload);
        }
        if (PayloadFields.hasExactFields(payload, NOT_FOUND_FIELDS)) {
            return notFound(requestedAssetId, payload);
        }
        return MonitoringSnapshotQueryResult.failed(QueryFailure.INVALID_RESPONSE);
    }

    private static MonitoringSnapshotQueryResult hit(String requestedAssetId, JsonNode payload) {
        String assetId = PayloadFields.textOf(payload, "assetId");
        Instant observedAt = PayloadFields.instantOf(payload, "observedAt");
        HealthState health = PayloadFields.enumOf(payload, "health", HealthState.class);
        Integer cpu = PayloadFields.intOf(payload, "cpuUtilizationPercent");
        Integer memory = PayloadFields.intOf(payload, "memoryUtilizationPercent");
        Integer alerts = PayloadFields.intOf(payload, "activeAlertCount");
        SourceOrigin source = PayloadFields.sourceOf(payload);

        if (assetId == null || observedAt == null || health == null
                || cpu == null || memory == null || alerts == null || source == null
                || !assetId.equals(requestedAssetId)) {
            return MonitoringSnapshotQueryResult.failed(QueryFailure.INVALID_RESPONSE);
        }
        try {
            return MonitoringSnapshotQueryResult.found(new MonitoringSnapshotView(assetId, observedAt, health,
                    cpu, memory, alerts, source));
        }
        catch (IllegalArgumentException ex) {
            // 数值越界等应用层不变量
            return MonitoringSnapshotQueryResult.failed(QueryFailure.INVALID_RESPONSE);
        }
    }

    private static MonitoringSnapshotQueryResult notFound(String requestedAssetId, JsonNode payload) {
        String assetId = PayloadFields.textOf(payload, "assetId");
        Boolean found = PayloadFields.booleanOf(payload, "found");
        String error = PayloadFields.textOf(payload, "error");
        String message = PayloadFields.textOf(payload, "message");
        SourceOrigin source = PayloadFields.sourceOf(payload);

        if (assetId == null || found == null || error == null || message == null || source == null) {
            return MonitoringSnapshotQueryResult.failed(QueryFailure.INVALID_RESPONSE);
        }
        if (found.booleanValue() || !NOT_FOUND_CODE.equals(error) || !assetId.equals(requestedAssetId)
                || !AssetIdentifier.isValid(assetId)) {
            return MonitoringSnapshotQueryResult.failed(QueryFailure.INVALID_RESPONSE);
        }
        return MonitoringSnapshotQueryResult.notFound(assetId, source);
    }

    private static MonitoringSnapshotQueryResult remoteFailure(String text) {
        JsonNode payload = PayloadFields.readObject(text);
        if (payload == null || !PayloadFields.hasExactFields(payload, ERROR_FIELDS)) {
            return MonitoringSnapshotQueryResult.failed(QueryFailure.REMOTE_TOOL_ERROR);
        }
        String error = PayloadFields.textOf(payload, "error");
        String message = PayloadFields.textOf(payload, "message");
        if (error == null || message == null) {
            return MonitoringSnapshotQueryResult.failed(QueryFailure.REMOTE_TOOL_ERROR);
        }
        return MonitoringSnapshotQueryResult.failed(SOURCE_UNAVAILABLE_CODE.equals(error)
                ? QueryFailure.UNAVAILABLE
                : QueryFailure.REMOTE_TOOL_ERROR);
    }
}
