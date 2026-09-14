package com.flowdesk.domain.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link EmbeddingDescriptor} 与 {@link KnowledgeDocumentChunkEmbedding} 不变量测试（FD-0010）。
 *
 * <p>这两个值对象是「向量能不能进数据库」的第一道闸门：维度、有限性与非零性都在这里判定，
 * 因此每一项都要有明确的反例。</p>
 */
class EmbeddingValueObjectsTest {

    private static final KnowledgeDocumentId DOCUMENT_ID =
            KnowledgeDocumentId.of(UUID.fromString("11111111-2222-3333-4444-555555555555"));

    private static final Sha256Digest DIGEST = Sha256Digest.of("0123456789abcdef".repeat(4));

    // ---------- 描述符 ----------

    @Test
    void acceptsTheConfiguredDescriptor() {
        EmbeddingDescriptor descriptor = EmbeddingDescriptor.of("dashscope", "text-embedding-v4");

        assertThat(descriptor.provider()).isEqualTo("dashscope");
        assertThat(descriptor.model()).isEqualTo("text-embedding-v4");
        assertThat(descriptor.dimensions()).isEqualTo(1024);
        assertThat(EmbeddingDescriptor.REQUIRED_DIMENSIONS).isEqualTo(1024);
    }

    @Test
    void rejectsWrongDimensions() {
        assertError(KnowledgeErrorCode.INVALID_EMBEDDING_DESCRIPTOR,
                () -> new EmbeddingDescriptor("dashscope", "text-embedding-v4", 1023));
        assertError(KnowledgeErrorCode.INVALID_EMBEDDING_DESCRIPTOR,
                () -> new EmbeddingDescriptor("dashscope", "text-embedding-v4", 1025));
        assertError(KnowledgeErrorCode.INVALID_EMBEDDING_DESCRIPTOR,
                () -> new EmbeddingDescriptor("dashscope", "text-embedding-v4", 0));
        assertError(KnowledgeErrorCode.INVALID_EMBEDDING_DESCRIPTOR,
                () -> new EmbeddingDescriptor("dashscope", "text-embedding-v4", -1));
    }

    @Test
    void rejectsMissingOrUnsafeProviderAndModel() {
        assertError(KnowledgeErrorCode.INVALID_EMBEDDING_DESCRIPTOR,
                () -> new EmbeddingDescriptor(null, "text-embedding-v4", 1024));
        assertError(KnowledgeErrorCode.INVALID_EMBEDDING_DESCRIPTOR,
                () -> new EmbeddingDescriptor("  ", "text-embedding-v4", 1024));
        assertError(KnowledgeErrorCode.INVALID_EMBEDDING_DESCRIPTOR,
                () -> new EmbeddingDescriptor("dashscope", null, 1024));
        assertError(KnowledgeErrorCode.INVALID_EMBEDDING_DESCRIPTOR,
                () -> new EmbeddingDescriptor("dashscope", "", 1024));
        assertError(KnowledgeErrorCode.INVALID_EMBEDDING_DESCRIPTOR,
                () -> new EmbeddingDescriptor("dash scope", "text-embedding-v4", 1024));
        assertError(KnowledgeErrorCode.INVALID_EMBEDDING_DESCRIPTOR,
                () -> new EmbeddingDescriptor("dashscope", "models/embedding", 1024));
        assertError(KnowledgeErrorCode.INVALID_EMBEDDING_DESCRIPTOR,
                () -> new EmbeddingDescriptor("dashscope", "a".repeat(129), 1024));
        assertError(KnowledgeErrorCode.INVALID_EMBEDDING_DESCRIPTOR,
                () -> new EmbeddingDescriptor("a".repeat(33), "text-embedding-v4", 1024));
    }

    // ---------- 向量 ----------

    @Test
    void acceptsAWellFormedVectorAndCopiesItDefensively() {
        float[] source = vector(1.0f);
        KnowledgeDocumentChunkEmbedding embedding = new KnowledgeDocumentChunkEmbedding(DOCUMENT_ID, 3, DIGEST,
                EmbeddingDescriptor.of("dashscope", "text-embedding-v4"), source);

        // 构造后改写源数组不得影响值对象
        source[0] = 999.0f;
        assertThat(embedding.vector()[0]).isEqualTo(1.0f);

        // 读取到的副本改写后也不得影响值对象
        float[] copy = embedding.vector();
        copy[0] = 42.0f;
        assertThat(embedding.vector()[0]).isEqualTo(1.0f);
    }

    @Test
    void exposesTheWholeProvenance() {
        KnowledgeDocumentChunkEmbedding embedding = new KnowledgeDocumentChunkEmbedding(DOCUMENT_ID, 7, DIGEST,
                EmbeddingDescriptor.of("dashscope", "text-embedding-v4"), vector(0.5f));

        assertThat(embedding.documentId()).isEqualTo(DOCUMENT_ID);
        assertThat(embedding.chunkIndex()).isEqualTo(7);
        assertThat(embedding.chunkSha256()).isEqualTo(DIGEST);
        assertThat(embedding.descriptor().provider()).isEqualTo("dashscope");
        assertThat(embedding.descriptor().model()).isEqualTo("text-embedding-v4");
        assertThat(embedding.dimensions()).isEqualTo(1024);
    }

    @Test
    void rejectsMissingParts() {
        EmbeddingDescriptor descriptor = EmbeddingDescriptor.of("dashscope", "text-embedding-v4");

        assertError(KnowledgeErrorCode.INVALID_VECTOR,
                () -> new KnowledgeDocumentChunkEmbedding(null, 0, DIGEST, descriptor, vector(1.0f)));
        assertError(KnowledgeErrorCode.INVALID_VECTOR,
                () -> new KnowledgeDocumentChunkEmbedding(DOCUMENT_ID, 0, null, descriptor, vector(1.0f)));
        assertError(KnowledgeErrorCode.INVALID_VECTOR,
                () -> new KnowledgeDocumentChunkEmbedding(DOCUMENT_ID, 0, DIGEST, null, vector(1.0f)));
        assertError(KnowledgeErrorCode.INVALID_VECTOR,
                () -> new KnowledgeDocumentChunkEmbedding(DOCUMENT_ID, 0, DIGEST, descriptor, null));
        assertError(KnowledgeErrorCode.INVALID_VECTOR,
                () -> new KnowledgeDocumentChunkEmbedding(DOCUMENT_ID, -1, DIGEST, descriptor, vector(1.0f)));
    }

    @Test
    void rejectsWrongVectorLength() {
        EmbeddingDescriptor descriptor = EmbeddingDescriptor.of("dashscope", "text-embedding-v4");

        assertError(KnowledgeErrorCode.INVALID_VECTOR,
                () -> new KnowledgeDocumentChunkEmbedding(DOCUMENT_ID, 0, DIGEST, descriptor, new float[1023]));
        assertError(KnowledgeErrorCode.INVALID_VECTOR,
                () -> new KnowledgeDocumentChunkEmbedding(DOCUMENT_ID, 0, DIGEST, descriptor, new float[1025]));
        assertError(KnowledgeErrorCode.INVALID_VECTOR,
                () -> new KnowledgeDocumentChunkEmbedding(DOCUMENT_ID, 0, DIGEST, descriptor, new float[0]));
    }

    @Test
    void rejectsNonFiniteValuesAndAllZeroVectors() {
        EmbeddingDescriptor descriptor = EmbeddingDescriptor.of("dashscope", "text-embedding-v4");

        float[] withNaN = vector(1.0f);
        withNaN[100] = Float.NaN;
        assertError(KnowledgeErrorCode.INVALID_VECTOR,
                () -> new KnowledgeDocumentChunkEmbedding(DOCUMENT_ID, 0, DIGEST, descriptor, withNaN));

        float[] withPositiveInfinity = vector(1.0f);
        withPositiveInfinity[0] = Float.POSITIVE_INFINITY;
        assertError(KnowledgeErrorCode.INVALID_VECTOR,
                () -> new KnowledgeDocumentChunkEmbedding(DOCUMENT_ID, 0, DIGEST, descriptor,
                        withPositiveInfinity));

        float[] withNegativeInfinity = vector(1.0f);
        withNegativeInfinity[1023] = Float.NEGATIVE_INFINITY;
        assertError(KnowledgeErrorCode.INVALID_VECTOR,
                () -> new KnowledgeDocumentChunkEmbedding(DOCUMENT_ID, 0, DIGEST, descriptor,
                        withNegativeInfinity));

        assertError(KnowledgeErrorCode.INVALID_VECTOR,
                () -> new KnowledgeDocumentChunkEmbedding(DOCUMENT_ID, 0, DIGEST, descriptor, new float[1024]));
    }

    @Test
    void equalityComparesVectorContentsRatherThanReferences() {
        EmbeddingDescriptor descriptor = EmbeddingDescriptor.of("dashscope", "text-embedding-v4");
        KnowledgeDocumentChunkEmbedding first = new KnowledgeDocumentChunkEmbedding(DOCUMENT_ID, 0, DIGEST,
                descriptor, vector(1.0f));
        KnowledgeDocumentChunkEmbedding second = new KnowledgeDocumentChunkEmbedding(DOCUMENT_ID, 0, DIGEST,
                descriptor, vector(1.0f));

        // record 的默认实现会对数组做引用比较，这里必须是按内容比较
        assertThat(first).isEqualTo(second);
        assertThat(first).hasSameHashCodeAs(second);

        float[] different = vector(1.0f);
        different[5] = 2.0f;
        assertThat(first).isNotEqualTo(new KnowledgeDocumentChunkEmbedding(DOCUMENT_ID, 0, DIGEST,
                descriptor, different));
    }

    @Test
    void toStringDoesNotLeakVectorValues() {
        KnowledgeDocumentChunkEmbedding embedding = new KnowledgeDocumentChunkEmbedding(DOCUMENT_ID, 1, DIGEST,
                EmbeddingDescriptor.of("dashscope", "text-embedding-v4"), vector(0.123456f));

        assertThat(embedding.toString())
                .contains("dashscope")
                .contains("text-embedding-v4")
                .contains("1024d")
                .doesNotContain("0.123456");
    }

    private static float[] vector(float value) {
        float[] vector = new float[1024];
        java.util.Arrays.fill(vector, value);
        return vector;
    }

    private static void assertError(KnowledgeErrorCode expected, Runnable callable) {
        assertThatThrownBy(callable::run)
                .isInstanceOf(KnowledgeDomainException.class)
                .extracting(thrown -> ((KnowledgeDomainException) thrown).errorCode())
                .isEqualTo(expected);
    }
}
