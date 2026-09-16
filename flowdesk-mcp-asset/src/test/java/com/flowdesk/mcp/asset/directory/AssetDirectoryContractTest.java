package com.flowdesk.mcp.asset.directory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 资产目录端口与资产标识契约的单元测试（FD-0014）。
 *
 * <p>这些是快速契约测试，<b>不能</b>代替 MCP 协议验收（协议由 {@code AssetMcpProtocolTests}
 * 用真实 SDK 客户端覆盖）；它们负责锁定「输入格式」「来源标识」「未找到 vs 不可用」这三件事。</p>
 */
class AssetDirectoryContractTest {

    private final DemoAssetDirectory demo = new DemoAssetDirectory();

    private final UnavailableAssetDirectory unavailable = new UnavailableAssetDirectory();

    @ParameterizedTest
    @ValueSource(strings = { "AST-000000", "AST-900001", "AST-123456", "AST-999999" })
    void acceptsExactlySixDigitsAfterThePrefix(String assetId) {
        assertThat(AssetId.isValid(assetId)).as("assetId=%s", assetId).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = { "", " ", "AST-1", "AST-12345", "AST-1234567", "ast-123456", "Ast-123456",
            "AST-12345a", "AST-12345 ", " AST-123456", "AST--12345", "AST_123456", "123456",
            "AST-1234567890" })
    void rejectsAnythingThatIsNotCanonical(String assetId) {
        assertThat(AssetId.isValid(assetId)).as("assetId=[%s]", assetId).isFalse();
    }

    @Test
    void rejectsNullAndOverlongInput() {
        assertThat(AssetId.isValid(null)).isFalse();
        assertThat(AssetId.isValid("A".repeat(AssetId.MAX_LENGTH + 1))).isFalse();
        assertThat(AssetId.isValid("AST-" + "9".repeat(6))).isTrue();
    }

    @Test
    void theDemoDirectoryOnlyServesFixedFictionalAssetsMarkedAsDemo() {
        assertThat(demo.findById("AST-900001")).hasValueSatisfying(record -> {
            assertThat(record.assetId()).isEqualTo("AST-900001");
            assertThat(record.assetType()).isEqualTo("SERVER");
            assertThat(record.status()).isEqualTo("IN_SERVICE");
            assertThat(record.source()).as("演示数据必须带 DEMO 血缘").isEqualTo(AssetSource.DEMO);
        });
        assertThat(demo.findById("AST-100001")).as("演示目录只认识自己的固定数据").isEmpty();
    }

    @Test
    void theUnavailableDirectoryFailsWithAFixedSafeMessage() {
        assertThatThrownBy(() -> unavailable.findById("AST-900001"))
                .isInstanceOf(AssetSourceUnavailableException.class)
                .hasMessage("资产数据源不可用")
                .hasNoCause();
        assertThatThrownBy(unavailable::source)
                .as("没有数据源就连「来源是什么」都答不出来")
                .isInstanceOf(AssetSourceUnavailableException.class);
    }

    @Test
    void theDemoDirectoryReportsItsOwnSource() {
        assertThat(demo.source()).isEqualTo(AssetSource.DEMO);
    }

    @Test
    void theAssetRecordCarriesNoSensitiveFields() {
        assertThat(AssetRecord.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .as("只返回回答「这是什么、什么状态、从哪来」所需的字段")
                .containsExactly("assetId", "assetType", "status", "source");
    }

    @Test
    void theDirectoryPortOnlyExposesReadOperations() {
        assertThat(AssetDirectory.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .as("端口只有「查一条」与「问来源」两个只读方法")
                .containsExactlyInAnyOrder("findById", "source");
        assertThat(AssetDirectory.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .as("没有任何写能力")
                .noneMatch(name -> name.matches(".*(add|create|update|set|put|patch|delete|remove|write).*"));
    }
}
