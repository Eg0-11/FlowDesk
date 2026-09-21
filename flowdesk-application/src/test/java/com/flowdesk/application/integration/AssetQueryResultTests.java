package com.flowdesk.application.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.Test;

/**
 * 资产查询结果的三态契约（FD-0016-R1）。
 *
 * <p>纯 Java：这些记录是调用方唯一能看到的结果形状，因此「哪些组合根本不可能存在」
 * 必须在构造期就拒绝，而不是留给调用方去猜字段什么时候为 null。</p>
 */
class AssetQueryResultTests {

    private static final AssetView VIEW =
            new AssetView("AST-900001", "SERVER", "IN_SERVICE", SourceOrigin.DEMO);

    @Test
    void theFoundStateCarriesTheRecordAndItsOriginInsideTheRecord() {
        AssetQueryResult result = AssetQueryResult.found(VIEW);

        assertThat(result.outcome()).isEqualTo(QueryOutcome.FOUND);
        assertThat(result.isFound()).isTrue();
        assertThat(result.isNotFound()).isFalse();
        assertThat(result.isFailed()).isFalse();
        assertThat(result.requireAsset()).isEqualTo(VIEW);
        assertThat(result.requireAsset().source()).as("FOUND 的来源在记录里").isEqualTo(SourceOrigin.DEMO);
        assertThat(result.assetId()).as("顶层 assetId 只服务于 NOT_FOUND").isNull();
        assertThat(result.source()).as("顶层 source 只服务于 NOT_FOUND").isNull();
        assertThat(result.failure()).isNull();
    }

    @Test
    void theNotFoundStateCarriesTheRequestedIdAndTheOrigin() {
        AssetQueryResult result = AssetQueryResult.notFound("AST-900001", SourceOrigin.REAL);

        assertThat(result.outcome()).isEqualTo(QueryOutcome.NOT_FOUND);
        assertThat(result.isNotFound()).isTrue();
        assertThat(result.isFound()).isFalse();
        assertThat(result.isFailed()).isFalse();
        assertThat(result.assetId()).isEqualTo("AST-900001");
        assertThat(result.source()).isEqualTo(SourceOrigin.REAL);
        assertThat(result.asset()).isNull();
        assertThat(result.failure()).isNull();
    }

    @Test
    void theFailedStateCarriesOnlyTheFailureClass() {
        for (QueryFailure failure : QueryFailure.values()) {
            AssetQueryResult result = AssetQueryResult.failed(failure);

            assertThat(result.outcome()).as("failure=%s", failure).isEqualTo(QueryOutcome.FAILED);
            assertThat(result.isFailed()).isTrue();
            assertThat(result.failure()).isEqualTo(failure);
            assertThat(result.asset()).isNull();
            assertThat(result.assetId()).isNull();
            assertThat(result.source()).isNull();
        }
    }

    @Test
    void contradictoryCombinationsAreRejectedAtConstruction() {
        assertThatThrownBy(() -> new AssetQueryResult(QueryOutcome.FOUND, VIEW, "AST-900001", null, null))
                .as("FOUND 不得携带 assetId")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssetQueryResult(QueryOutcome.FOUND, VIEW, null, SourceOrigin.DEMO, null))
                .as("FOUND 不得携带顶层 source")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssetQueryResult(QueryOutcome.FOUND, VIEW, null, null,
                QueryFailure.UNAVAILABLE))
                .as("FOUND 不得携带 failure")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssetQueryResult(QueryOutcome.NOT_FOUND, VIEW, "AST-900001",
                SourceOrigin.DEMO, null))
                .as("NOT_FOUND 不得携带记录")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssetQueryResult(QueryOutcome.NOT_FOUND, null, null, SourceOrigin.DEMO, null))
                .as("NOT_FOUND 必须有 assetId")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssetQueryResult(QueryOutcome.NOT_FOUND, null, "AST-900001", null, null))
                .as("NOT_FOUND 必须有 source")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssetQueryResult(QueryOutcome.NOT_FOUND, null, "AST-900001", SourceOrigin.DEMO,
                QueryFailure.UNAVAILABLE))
                .as("NOT_FOUND 不得携带 failure")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssetQueryResult(QueryOutcome.FAILED, VIEW, null, null,
                QueryFailure.UNAVAILABLE))
                .as("FAILED 不得携带记录")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssetQueryResult(QueryOutcome.FAILED, null, "AST-900001", null,
                QueryFailure.UNAVAILABLE))
                .as("FAILED 不得携带 assetId")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssetQueryResult(QueryOutcome.FAILED, null, null, SourceOrigin.DEMO,
                QueryFailure.UNAVAILABLE))
                .as("FAILED 不得携带 source")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssetQueryResult(QueryOutcome.FAILED, null, null, null, null))
                .as("FAILED 必须有 failure")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingRequiredFieldsAreRejected() {
        assertThatThrownBy(() -> new AssetQueryResult(null, null, null, null, null))
                .as("outcome 不能为空")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AssetQueryResult.found(null))
                .as("FOUND 必须有记录")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AssetQueryResult.notFound(null, SourceOrigin.DEMO))
                .as("NOT_FOUND 必须有 assetId")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AssetQueryResult.notFound("AST-900001", null))
                .as("NOT_FOUND 必须有 source")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AssetQueryResult.failed(null))
                .as("FAILED 必须有 failure")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requireAssetFailsLoudlyInEveryNonFoundState() {
        assertThat(catchThrowable(() -> AssetQueryResult.notFound("AST-900001", SourceOrigin.DEMO).requireAsset()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NOT_FOUND");
        assertThat(catchThrowable(() -> AssetQueryResult.failed(QueryFailure.TIMEOUT).requireAsset()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FAILED");
    }
}
