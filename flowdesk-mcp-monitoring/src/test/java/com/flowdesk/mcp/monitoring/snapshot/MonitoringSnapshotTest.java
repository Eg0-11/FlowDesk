package com.flowdesk.mcp.monitoring.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 快照不变量测试（FD-0015）。
 *
 * <p>纯 Java，不起 Spring、不联网：不变量必须在<b>构造期</b>就成立，
 * 这样「入库一条 120% 的 CPU 使用率」在类型层面就不可能发生。</p>
 */
class MonitoringSnapshotTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void aWellFormedSnapshotIsAcceptedAndRendersIsoInstant() {
        MonitoringSnapshot snapshot = new MonitoringSnapshot("AST-900001", OBSERVED_AT, HealthState.DEGRADED,
                92, 68, 1, SnapshotOrigin.DEMO);

        assertThat(snapshot.assetId()).isEqualTo("AST-900001");
        assertThat(snapshot.observedAtIso()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(snapshot.health()).isEqualTo(HealthState.DEGRADED);
    }

    @ParameterizedTest
    @ValueSource(strings = { "AST-1", "ast-900001", "AST-900001 ", " AST-900001", "AST-9000011", "", "900001" })
    void anInvalidAssetIdIsRejectedAtConstruction(String assetId) {
        assertThatThrownBy(() -> new MonitoringSnapshot(assetId, OBSERVED_AT, HealthState.HEALTHY, 1, 1, 0,
                SnapshotOrigin.DEMO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("assetId");
    }

    @Test
    void aNullObservedAtHealthOrOriginIsRejected() {
        assertThatThrownBy(() -> new MonitoringSnapshot("AST-900001", null, HealthState.HEALTHY, 1, 1, 0,
                SnapshotOrigin.DEMO)).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new MonitoringSnapshot("AST-900001", OBSERVED_AT, null, 1, 1, 0,
                SnapshotOrigin.DEMO)).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new MonitoringSnapshot("AST-900001", OBSERVED_AT, HealthState.HEALTHY, 1, 1, 0,
                null)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = { -1, 101, 1000 })
    void anOutOfRangePercentageIsRejected(int percent) {
        assertThatThrownBy(() -> new MonitoringSnapshot("AST-900001", OBSERVED_AT, HealthState.HEALTHY,
                percent, 50, 0, SnapshotOrigin.DEMO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cpuUtilizationPercent");

        assertThatThrownBy(() -> new MonitoringSnapshot("AST-900001", OBSERVED_AT, HealthState.HEALTHY,
                50, percent, 0, SnapshotOrigin.DEMO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("memoryUtilizationPercent");
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 100 })
    void theBoundaryPercentagesAreAccepted(int percent) {
        assertThat(new MonitoringSnapshot("AST-900001", OBSERVED_AT, HealthState.HEALTHY, percent, percent, 0,
                SnapshotOrigin.DEMO).cpuUtilizationPercent()).isEqualTo(percent);
    }

    @Test
    void aNegativeAlertCountIsRejected() {
        assertThatThrownBy(() -> new MonitoringSnapshot("AST-900001", OBSERVED_AT, HealthState.HEALTHY, 1, 1, -1,
                SnapshotOrigin.DEMO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("activeAlertCount");
    }

    @Test
    void healthStatesAreExactlyTheFourContractedValues() {
        assertThat(HealthState.values()).extracting(Enum::name)
                .containsExactly("HEALTHY", "DEGRADED", "CRITICAL", "UNKNOWN");
    }

    @Test
    void theSnapshotRecordCarriesOnlyTheContractedComponents() {
        assertThat(MonitoringSnapshot.class.getRecordComponents())
                .as("类型上只有这七个组件：IP、主机名、内部地址、凭证与异常信息根本没有字段可放，"
                        + "因此不可能被工具「顺手」带出去")
                .extracting(RecordComponent::getName)
                .containsExactly("assetId", "observedAt", "health", "cpuUtilizationPercent",
                        "memoryUtilizationPercent", "activeAlertCount", "origin");
    }

    @Test
    void equalityIsValueBased() {
        MonitoringSnapshot first = new MonitoringSnapshot("AST-900001", OBSERVED_AT, HealthState.DEGRADED,
                92, 68, 1, SnapshotOrigin.DEMO);
        MonitoringSnapshot second = new MonitoringSnapshot("AST-900001", OBSERVED_AT, HealthState.DEGRADED,
                92, 68, 1, SnapshotOrigin.DEMO);

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
    }
}
