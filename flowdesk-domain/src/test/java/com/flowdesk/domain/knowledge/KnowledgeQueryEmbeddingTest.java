package com.flowdesk.domain.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * {@link KnowledgeQueryEmbedding} 不变量测试（RAG 4/6）。
 *
 * <p>查询向量是「用户问题的数值形态」，它进不了数据库、也救不回来：
 * 维度不符会让 SQL 里的距离计算直接失败，{@code NaN}/{@code Infinity} 会让相似度变成
 * 无意义的数值，全零向量则与任何切片都无法比较。因此每一项都必须有明确的反例。</p>
 */
class KnowledgeQueryEmbeddingTest {

    private static final EmbeddingDescriptor DESCRIPTOR =
            EmbeddingDescriptor.of("dashscope", "text-embedding-v4");

    @Test
    void acceptsAWellFormedVectorAndCopiesItDefensively() {
        float[] source = vector(0.5f);

        KnowledgeQueryEmbedding embedding = new KnowledgeQueryEmbedding(DESCRIPTOR, source);
        // 改写源数组不得影响值对象
        source[0] = 99.0f;

        assertThat(embedding.descriptor()).isEqualTo(DESCRIPTOR);
        assertThat(embedding.dimensions()).isEqualTo(EmbeddingDescriptor.REQUIRED_DIMENSIONS);
        assertThat(embedding.vector()).hasSize(EmbeddingDescriptor.REQUIRED_DIMENSIONS);
        assertThat(embedding.vector()[0]).isEqualTo(0.5f);
    }

    @Test
    void theReturnedVectorIsACopy() {
        KnowledgeQueryEmbedding embedding = new KnowledgeQueryEmbedding(DESCRIPTOR, vector(0.25f));

        float[] first = embedding.vector();
        first[7] = 42.0f;

        assertThat(embedding.vector()[7]).as("读取到的数组必须与内部状态无关").isEqualTo(0.25f);
    }

    @Test
    void rejectsAMissingDescriptorOrVector() {
        assertError(() -> new KnowledgeQueryEmbedding(null, vector(0.5f)));
        assertError(() -> new KnowledgeQueryEmbedding(DESCRIPTOR, null));
    }

    @Test
    void rejectsVectorsWhoseLengthDoesNotMatchTheDescriptor() {
        assertError(() -> new KnowledgeQueryEmbedding(DESCRIPTOR, new float[1023]));
        assertError(() -> new KnowledgeQueryEmbedding(DESCRIPTOR, new float[1025]));
        assertError(() -> new KnowledgeQueryEmbedding(DESCRIPTOR, new float[0]));
    }

    @Test
    void rejectsNonFiniteValues() {
        float[] withNaN = vector(0.5f);
        withNaN[13] = Float.NaN;
        assertError(() -> new KnowledgeQueryEmbedding(DESCRIPTOR, withNaN));

        float[] withPositiveInfinity = vector(0.5f);
        withPositiveInfinity[13] = Float.POSITIVE_INFINITY;
        assertError(() -> new KnowledgeQueryEmbedding(DESCRIPTOR, withPositiveInfinity));

        float[] withNegativeInfinity = vector(0.5f);
        withNegativeInfinity[13] = Float.NEGATIVE_INFINITY;
        assertError(() -> new KnowledgeQueryEmbedding(DESCRIPTOR, withNegativeInfinity));
    }

    @Test
    void rejectsAnAllZeroVector() {
        assertError(() -> new KnowledgeQueryEmbedding(DESCRIPTOR, new float[1024]));
    }

    @Test
    void comparesByContent() {
        KnowledgeQueryEmbedding first = new KnowledgeQueryEmbedding(DESCRIPTOR, vector(0.75f));
        KnowledgeQueryEmbedding second = new KnowledgeQueryEmbedding(DESCRIPTOR, vector(0.75f));

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);

        float[] different = vector(0.75f);
        different[0] = 0.5f;
        assertThat(first).isNotEqualTo(new KnowledgeQueryEmbedding(DESCRIPTOR, different));
    }

    @Test
    void toStringDoesNotLeakVectorValues() {
        KnowledgeQueryEmbedding embedding = new KnowledgeQueryEmbedding(DESCRIPTOR, vector(0.123456f));

        assertThat(embedding.toString())
                .contains("dashscope")
                .contains("text-embedding-v4")
                .contains("1024d")
                .doesNotContain("0.123456");
    }

    private static float[] vector(float value) {
        float[] vector = new float[EmbeddingDescriptor.REQUIRED_DIMENSIONS];
        Arrays.fill(vector, value);
        return vector;
    }

    private static void assertError(Runnable callable) {
        assertThatThrownBy(callable::run)
                .isInstanceOf(KnowledgeDomainException.class)
                .extracting(thrown -> ((KnowledgeDomainException) thrown).errorCode())
                .isEqualTo(KnowledgeErrorCode.INVALID_VECTOR);
    }
}
