package com.flowdesk.application.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * 监控快照查询结果的三态契约（FD-0016-R1）。
 *
 * <p>与资产侧同一套形状约定，另外钉住「快照自带观测时刻」这件事在结果层没有被丢掉。</p>
 */
class MonitoringSnapshotQueryResultTests {

    private static final MonitoringSnapshotView SNAPSHOT = new MonitoringSnapshotView("AST-900001",
            Instant.parse("2026-01-01T00:00:00Z"), HealthState.DEGRADED, 92, 68, 1, SourceOrigin.DEMO);

    @Test
    void theFoundStateCarriesTheSnapshotIncludingItsObservationTime() {
        MonitoringSnapshotQueryResult result = MonitoringSnapshotQueryResult.found(SNAPSHOT);

        assertThat(result.outcome()).isEqualTo(QueryOutcome.FOUND);
        assertThat(result.isFound()).isTrue();
        assertThat(result.isNotFound()).isFalse();
        assertThat(result.isFailed()).isFalse();
        assertThat(result.requireSnapshot()).isEqualTo(SNAPSHOT);
        assertThat(result.requireSnapshot().observedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(result.requireSnapshot().source()).as("FOUND 的来源在快照里").isEqualTo(SourceOrigin.DEMO);
        assertThat(result.assetId()).as("顶层 assetId 只服务于 NOT_FOUND").isNull();
        assertThat(result.source()).as("顶层 source 只服务于 NOT_FOUND").isNull();
        assertThat(result.failure()).isNull();
    }

    @Test
    void theNotFoundStateCarriesTheRequestedIdAndTheOrigin() {
        MonitoringSnapshotQueryResult result =
                MonitoringSnapshotQueryResult.notFound("AST-900003", SourceOrigin.DEMO);

        assertThat(result.outcome()).isEqualTo(QueryOutcome.NOT_FOUND);
        assertThat(result.isNotFound()).isTrue();
        assertThat(result.isFound()).isFalse();
        assertThat(result.isFailed()).isFalse();
        assertThat(result.assetId()).isEqualTo("AST-900003");
        assertThat(result.source()).isEqualTo(SourceOrigin.DEMO);
        assertThat(result.snapshot()).isNull();
        assertThat(result.failure()).isNull();
    }

    @Test
    void theFailedStateCarriesOnlyTheFailureClass() {
        for (QueryFailure failure : QueryFailure.values()) {
            MonitoringSnapshotQueryResult result = MonitoringSnapshotQueryResult.failed(failure);

            assertThat(result.outcome()).as("failure=%s", failure).isEqualTo(QueryOutcome.FAILED);
            assertThat(result.isFailed()).isTrue();
            assertThat(result.failure()).isEqualTo(failure);
            assertThat(result.snapshot()).isNull();
            assertThat(result.assetId()).isNull();
            assertThat(result.source()).isNull();
        }
    }

    @Test
    void contradictoryCombinationsAreRejectedAtConstruction() {
        assertThatThrownBy(() -> new MonitoringSnapshotQueryResult(QueryOutcome.FOUND, SNAPSHOT, "AST-900001",
                null, null))
                .as("FOUND 不得携带 assetId")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MonitoringSnapshotQueryResult(QueryOutcome.FOUND, SNAPSHOT, null,
                SourceOrigin.DEMO, null))
                .as("FOUND 不得携带顶层 source")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MonitoringSnapshotQueryResult(QueryOutcome.FOUND, SNAPSHOT, null, null,
                QueryFailure.TIMEOUT))
                .as("FOUND 不得携带 failure")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MonitoringSnapshotQueryResult(QueryOutcome.NOT_FOUND, SNAPSHOT, "AST-900001",
                SourceOrigin.DEMO, null))
                .as("NOT_FOUND 不得携带快照")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MonitoringSnapshotQueryResult(QueryOutcome.NOT_FOUND, null, null,
                SourceOrigin.DEMO, null))
                .as("NOT_FOUND 必须有 assetId")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MonitoringSnapshotQueryResult(QueryOutcome.NOT_FOUND, null, "AST-900001",
                null, null))
                .as("NOT_FOUND 必须有 source")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MonitoringSnapshotQueryResult(QueryOutcome.NOT_FOUND, null, "AST-900001",
                SourceOrigin.DEMO, QueryFailure.TIMEOUT))
                .as("NOT_FOUND 不得携带 failure")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MonitoringSnapshotQueryResult(QueryOutcome.FAILED, SNAPSHOT, null, null,
                QueryFailure.TIMEOUT))
                .as("FAILED 不得携带快照")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MonitoringSnapshotQueryResult(QueryOutcome.FAILED, null, "AST-900001", null,
                QueryFailure.TIMEOUT))
                .as("FAILED 不得携带 assetId")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MonitoringSnapshotQueryResult(QueryOutcome.FAILED, null, null,
                SourceOrigin.DEMO, QueryFailure.TIMEOUT))
                .as("FAILED 不得携带 source")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MonitoringSnapshotQueryResult(QueryOutcome.FAILED, null, null, null, null))
                .as("FAILED 必须有 failure")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingRequiredFieldsAreRejected() {
        assertThatThrownBy(() -> new MonitoringSnapshotQueryResult(null, null, null, null, null))
                .as("outcome 不能为空")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MonitoringSnapshotQueryResult.found(null))
                .as("FOUND 必须有快照")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MonitoringSnapshotQueryResult.notFound(null, SourceOrigin.DEMO))
                .as("NOT_FOUND 必须有 assetId")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MonitoringSnapshotQueryResult.notFound("AST-900001", null))
                .as("NOT_FOUND 必须有 source")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MonitoringSnapshotQueryResult.failed(null))
                .as("FAILED 必须有 failure")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requireSnapshotFailsLoudlyInEveryNonFoundState() {
        assertThat(catchThrowable(() -> MonitoringSnapshotQueryResult.notFound("AST-900003", SourceOrigin.DEMO)
                .requireSnapshot()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NOT_FOUND");
        assertThat(catchThrowable(() -> MonitoringSnapshotQueryResult.failed(QueryFailure.DISABLED).requireSnapshot()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FAILED");
    }
}
