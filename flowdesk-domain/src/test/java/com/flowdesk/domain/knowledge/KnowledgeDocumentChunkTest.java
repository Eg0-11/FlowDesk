package com.flowdesk.domain.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link KnowledgeDocumentChunk} 不变量测试（FD-0009）。
 *
 * <p>重点是 code point 计数：切片器与应用层都必须按 Unicode code point 计数，
 * 一旦有人用 {@code String.length()}，emoji 与中文混排的文档就会出现
 * 「计数与实际内容不一致」的脏数据。这里由领域层兜底。</p>
 */
class KnowledgeDocumentChunkTest {

    private static final KnowledgeDocumentId DOCUMENT_ID =
            KnowledgeDocumentId.of(UUID.fromString("11111111-2222-3333-4444-555555555555"));

    private static final Instant NOW = Instant.parse("2026-05-01T10:00:00Z");

    private static final Sha256Digest DIGEST = Sha256Digest.of("0123456789abcdef".repeat(4));

    @Test
    void acceptsAWellFormedChunk() {
        KnowledgeDocumentChunk chunk = chunk(0, "第一季度运维报告", 8);

        assertThat(chunk.documentId()).isEqualTo(DOCUMENT_ID);
        assertThat(chunk.chunkIndex()).isZero();
        assertThat(chunk.content()).isEqualTo("第一季度运维报告");
        assertThat(chunk.codePointCount()).isEqualTo(8);
        assertThat(chunk.sha256()).isEqualTo(DIGEST);
        assertThat(chunk.createdAt()).isEqualTo(NOW);
    }

    @Test
    void countsSupplementaryCharactersAsOneCodePoint() {
        String emoji = "日志📄🚀已处理";
        int codePoints = emoji.codePointCount(0, emoji.length());
        assertThat(emoji.length()).as("UTF-16 长度确实大于 code point 数，这个用例才有意义")
                .isGreaterThan(codePoints);

        assertThat(chunk(3, emoji, codePoints).codePointCount()).isEqualTo(codePoints);
    }

    @Test
    void rejectsCodePointCountThatDisagreesWithTheContent() {
        // 📄 的 String.length() 是 2、code point 数是 1：用 UTF-16 长度计数会被拒绝
        assertError(() -> chunk(0, "📄", "📄".length()));
        assertThat(chunk(0, "📄", 1).codePointCount()).as("正确的计数是 1").isEqualTo(1);
        assertError(() -> chunk(0, "abc", 2));
        assertError(() -> chunk(0, "abc", 4));
    }

    @Test
    void rejectsMissingDocumentOrBlankContent() {
        assertError(() -> new KnowledgeDocumentChunk(null, 0, "内容", 2, DIGEST, NOW));
        assertError(() -> chunk(0, null, 0));
        assertError(() -> chunk(0, "", 0));
        assertError(() -> chunk(0, "   ", 3));
        assertError(() -> chunk(0, "\n\t", 2));
    }

    @Test
    void rejectsNegativeIndex() {
        assertError(() -> chunk(-1, "内容", 2));
    }

    @Test
    void rejectsContentLongerThanTheColumnCapacity() {
        String tooLong = "a".repeat(KnowledgeDocumentChunk.MAX_CONTENT_LENGTH + 1);

        assertError(() -> chunk(0, tooLong, tooLong.length()));
        // 边界值本身合法
        assertThat(chunk(0, "a".repeat(KnowledgeDocumentChunk.MAX_CONTENT_LENGTH),
                KnowledgeDocumentChunk.MAX_CONTENT_LENGTH).content())
                .hasSize(KnowledgeDocumentChunk.MAX_CONTENT_LENGTH);
    }

    @Test
    void rejectsMissingDigestOrTime() {
        assertError(() -> new KnowledgeDocumentChunk(DOCUMENT_ID, 0, "内容", 2, null, NOW));
        assertError(() -> new KnowledgeDocumentChunk(DOCUMENT_ID, 0, "内容", 2, DIGEST, null));
    }

    @Test
    void toStringDoesNotLeakChunkContent() {
        KnowledgeDocumentChunk chunk = chunk(7, "公司内部机密内容", 8);

        assertThat(chunk.toString())
                .contains("7")
                .contains("8 code points")
                .doesNotContain("公司内部机密内容");
    }

    private static KnowledgeDocumentChunk chunk(int index, String content, int codePointCount) {
        return new KnowledgeDocumentChunk(DOCUMENT_ID, index, content, codePointCount, DIGEST, NOW);
    }

    private static void assertError(Runnable callable) {
        assertThatThrownBy(callable::run)
                .isInstanceOf(KnowledgeDomainException.class)
                .extracting(thrown -> ((KnowledgeDomainException) thrown).errorCode())
                .isEqualTo(KnowledgeErrorCode.INVALID_CHUNK);
    }
}
