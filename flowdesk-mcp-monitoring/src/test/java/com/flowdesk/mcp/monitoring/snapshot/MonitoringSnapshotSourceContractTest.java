package com.flowdesk.mcp.monitoring.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 监控快照查询端口的契约测试（FD-0015）。
 *
 * <p>两个实现（演示、不可用）必须给出<b>同一套</b>可观察契约：</p>
 * <ul>
 *   <li>端口是<b>只读</b>的：接口上不允许出现任何写方法（用反射把这条钉死）；</li>
 *   <li>「未找到」与「数据源不可用」严格区分：前者是 {@code Optional.empty()}，
 *       后者是异常；</li>
 *   <li>演示数据固定且都带 {@code source=DEMO}。</li>
 * </ul>
 */
class MonitoringSnapshotSourceContractTest {

    /** 固定演示记录的期望值（与 README / ADR 的表格逐项对应）。 */
    private static final List<String> READ_ONLY_METHODS = List.of("findSnapshotById", "origin", "equals", "hashCode",
            "toString", "getClass", "notify", "notifyAll", "wait");

    @Test
    void thePortExposesNoWriteMethod() {
        for (Method method : MonitoringSnapshotSource.class.getDeclaredMethods()) {
            assertThat(READ_ONLY_METHODS)
                    .as("端口只允许查询方法：%s", method.getName())
                    .contains(method.getName());
        }
    }

    @Test
    void theDemoSourceReturnsTheFixedDegradedSnapshot() {
        MonitoringSnapshotSource source = new DemoSnapshotSource();

        MonitoringSnapshot snapshot = source.findSnapshotById("AST-900001").orElseThrow();

        assertThat(snapshot.assetId()).isEqualTo("AST-900001");
        assertThat(snapshot.observedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(snapshot.health()).isEqualTo(HealthState.DEGRADED);
        assertThat(snapshot.cpuUtilizationPercent()).isEqualTo(92);
        assertThat(snapshot.memoryUtilizationPercent()).isEqualTo(68);
        assertThat(snapshot.activeAlertCount()).isEqualTo(1);
        assertThat(snapshot.origin()).isEqualTo(SnapshotOrigin.DEMO);
    }

    @Test
    void theDemoSourceReturnsTheFixedHealthySnapshot() {
        MonitoringSnapshotSource source = new DemoSnapshotSource();

        MonitoringSnapshot snapshot = source.findSnapshotById("AST-900002").orElseThrow();

        assertThat(snapshot.assetId()).isEqualTo("AST-900002");
        assertThat(snapshot.observedAt()).isEqualTo(Instant.parse("2026-01-01T00:05:00Z"));
        assertThat(snapshot.health()).isEqualTo(HealthState.HEALTHY);
        assertThat(snapshot.cpuUtilizationPercent()).isEqualTo(18);
        assertThat(snapshot.memoryUtilizationPercent()).isEqualTo(35);
        assertThat(snapshot.activeAlertCount()).isZero();
        assertThat(snapshot.origin()).isEqualTo(SnapshotOrigin.DEMO);
    }

    @Test
    void theDemoSourceReportsNoSnapshotForTheThirdFixedAsset() {
        MonitoringSnapshotSource source = new DemoSnapshotSource();

        assertThat(source.findSnapshotById("AST-900003"))
                .as("AST-900003 刻意不提供快照：用于验证「合法但未找到」")
                .isEmpty();
    }

    @Test
    void everyDemoSnapshotIsMarkedAsDemo() {
        MonitoringSnapshotSource source = new DemoSnapshotSource();

        for (String assetId : List.of("AST-900001", "AST-900002")) {
            assertThat(source.findSnapshotById(assetId).orElseThrow().origin())
                    .as("assetId=%s", assetId)
                    .isEqualTo(SnapshotOrigin.DEMO);
        }
        assertThat(source.origin()).isEqualTo(SnapshotOrigin.DEMO);
    }

    @Test
    void theDemoSourceIsDeterministic() {
        MonitoringSnapshotSource source = new DemoSnapshotSource();

        assertThat(source.findSnapshotById("AST-900001").orElseThrow())
                .as("固定时间 + 固定数值：两次查询必须完全相同")
                .isEqualTo(source.findSnapshotById("AST-900001").orElseThrow());
    }

    @Test
    void theUnavailableSourceFailsExplicitlyInsteadOfReportingNotFound() {
        MonitoringSnapshotSource source = new UnavailableSnapshotSource();

        Throwable lookup = catchThrowable(() -> source.findSnapshotById("AST-900001"));
        Throwable origin = catchThrowable(source::origin);

        assertThat(lookup).as("「没有数据源」不能伪装成「没有这个快照」")
                .isInstanceOf(SnapshotSourceUnavailableException.class);
        assertThat(origin).isInstanceOf(SnapshotSourceUnavailableException.class);
        assertThat(lookup).hasMessage(SnapshotSourceUnavailableException.MESSAGE);
        assertThat(SnapshotSourceUnavailableException.MESSAGE).isEqualTo("监控数据源当前不可用");
    }

    @Test
    void noImplementationCanSilentlyReturnAnEmptyResultWhenTheSourceIsUnavailable() {
        MonitoringSnapshotSource source = new UnavailableSnapshotSource();

        assertThatThrownBy(() -> source.findSnapshotById("AST-999999"))
                .as("即使查询一个明显不存在的资产，没有数据源也必须失败")
                .isInstanceOf(SnapshotSourceUnavailableException.class);
    }

    @Test
    void theDemoSourceNeverPretendsToBeReal() {
        assertThat(new DemoSnapshotSource().origin()).isEqualTo(SnapshotOrigin.DEMO);
        assertThat(SnapshotOrigin.valueOf("DEMO")).isEqualTo(SnapshotOrigin.DEMO);
        assertThat(Optional.of(SnapshotOrigin.DEMO).orElseThrow().name()).isEqualTo("DEMO");
    }
}
