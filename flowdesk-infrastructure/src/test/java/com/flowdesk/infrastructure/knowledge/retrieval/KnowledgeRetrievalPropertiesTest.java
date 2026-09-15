package com.flowdesk.infrastructure.knowledge.retrieval;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 检索配置校验测试（RAG 4/6 / FD-0011-R1）。
 *
 * <p>公开输入上限是<b>任务契约</b>（query ≤ 2000 个 code point、topK ∈ 1..20），
 * 配置只能收紧、不能扩大：因此 {@code max-query-code-points=2001} 与 {@code max-top-k=21}
 * 都必须在装配期失败，而不是把公开上限悄悄放大。</p>
 */
class KnowledgeRetrievalPropertiesTest {

    @Test
    void theDefaultsAreValid() {
        assertThatCode(() -> new KnowledgeRetrievalProperties().validate()).doesNotThrowAnyException();
    }

    @Test
    void acceptsTheContractBoundaries() {
        assertThatCode(() -> properties(2000, 5, 20, 0.30).validate()).doesNotThrowAnyException();
        assertThatCode(() -> properties(1, 1, 1, 0.0).validate()).doesNotThrowAnyException();
        assertThatCode(() -> properties(2000, 20, 20, 1.0).validate()).doesNotThrowAnyException();
    }

    @Test
    void rejectsAQueryLimitBeyondThePublicContract() {
        assertThatThrownBy(() -> properties(2001, 5, 20, 0.30).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-query-code-points")
                .hasMessageContaining("2000");
    }

    @Test
    void rejectsATopKLimitBeyondThePublicContract() {
        assertThatThrownBy(() -> properties(2000, 5, 21, 0.30).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-top-k")
                .hasMessageContaining("20");
    }

    @Test
    void rejectsNonPositiveLimits() {
        assertThatThrownBy(() -> properties(0, 5, 20, 0.30).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-query-code-points");
        assertThatThrownBy(() -> properties(2000, 5, 0, 0.30).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-top-k");
        assertThatThrownBy(() -> properties(2000, 0, 20, 0.30).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default-top-k");
    }

    @Test
    void rejectsADefaultTopKLargerThanTheLimit() {
        assertThatThrownBy(() -> properties(2000, 11, 10, 0.30).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default-top-k");
    }

    @Test
    void rejectsAnInvalidDefaultMinScore() {
        for (double minScore : new double[] { -0.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY }) {
            assertThatThrownBy(() -> properties(2000, 5, 20, minScore).validate())
                    .as("default-min-score=%s 必须被拒绝", minScore)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("default-min-score");
        }
    }

    /**
     * @param maxQueryCodePoints 单个 query 的 code point 上限
     * @param defaultTopK        topK 默认值
     * @param maxTopK            topK 上限
     * @param defaultMinScore    阈值默认值
     * @return 配置对象
     */
    private static KnowledgeRetrievalProperties properties(int maxQueryCodePoints, int defaultTopK, int maxTopK,
            double defaultMinScore) {

        KnowledgeRetrievalProperties properties = new KnowledgeRetrievalProperties();
        properties.setMaxQueryCodePoints(maxQueryCodePoints);
        properties.setDefaultTopK(defaultTopK);
        properties.setMaxTopK(maxTopK);
        properties.setDefaultMinScore(defaultMinScore);
        return properties;
    }
}
