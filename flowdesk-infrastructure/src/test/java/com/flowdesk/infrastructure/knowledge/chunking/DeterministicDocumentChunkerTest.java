package com.flowdesk.infrastructure.knowledge.chunking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowdesk.application.knowledge.parse.DocumentChunkingException;
import com.flowdesk.domain.knowledge.KnowledgeDocumentChunk;
import com.flowdesk.domain.knowledge.KnowledgeParseFailureCode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link DeterministicDocumentChunker} 与 {@link DocumentTextNormalizer} 测试（FD-0009）。
 *
 * <p>切片是后续向量化的输入，它的<b>确定性</b>是硬要求：同一份文档每次解析必须得到
 * 逐字节相同的切片，否则「重新解析」会静默改变检索结果。这里对长度、重叠、
 * 边界优先级、code point 安全与配置边界逐条断言。</p>
 */
class DeterministicDocumentChunkerTest {

    // ---------- 配置边界 ----------

    @Test
    void rejectsIllegalConfigurations() {
        assertThatThrownBy(() -> new DeterministicDocumentChunker(0, 0, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeterministicDocumentChunker(-5, 0, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeterministicDocumentChunker(100, -1, 10))
                .isInstanceOf(IllegalArgumentException.class);
        // overlap 必须严格小于 chunkSize，否则算法无法前进
        assertThatThrownBy(() -> new DeterministicDocumentChunker(100, 100, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeterministicDocumentChunker(100, 101, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeterministicDocumentChunker(100, 10, 0))
                .isInstanceOf(IllegalArgumentException.class);
        // 超过存储列容量反推的硬上限
        assertThatThrownBy(() -> new DeterministicDocumentChunker(
                KnowledgeChunkingProperties.MAX_CHUNK_SIZE_LIMIT + 1, 0, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsTheDocumentedDefaultConfiguration() {
        DeterministicDocumentChunker chunker = new DeterministicDocumentChunker(1000, 150, 5000);

        assertThat(chunker.chunkSize()).isEqualTo(1000);
        assertThat(chunker.overlap()).isEqualTo(150);
        assertThat(chunker.maxChunks()).isEqualTo(5000);
    }

    @Test
    void maxChunkSizeLimitMatchesTheStorableColumnCapacity() {
        // 上限的来由：切片 UTF-16 长度最多是 code point 数的 2 倍
        assertThat(KnowledgeChunkingProperties.MAX_CHUNK_SIZE_LIMIT * 2)
                .isLessThanOrEqualTo(KnowledgeDocumentChunk.MAX_CONTENT_LENGTH);
    }

    // ---------- 基本行为 ----------

    @Test
    void blankOrEmptyInputProducesNoChunks() {
        DeterministicDocumentChunker chunker = chunker(10, 0);

        assertThat(chunker.chunk("")).isEmpty();
        assertThat(chunker.chunk("   ")).isEmpty();
        assertThat(chunker.chunk("\n\n\t \n")).isEmpty();
        assertThat(chunker.chunk(null)).as("null 视为空文本，不抛 NPE").isEmpty();
    }

    @Test
    void shortTextBecomesExactlyOneTrimmedChunk() {
        DeterministicDocumentChunker chunker = chunker(100, 10);

        assertThat(chunker.chunk("  季度运维报告  ")).containsExactly("季度运维报告");
    }

    @Test
    void everyChunkIsAtMostChunkSizeCodePoints() {
        DeterministicDocumentChunker chunker = chunker(50, 10);
        String text = "报告".repeat(200);

        List<String> chunks = chunker.chunk(text);

        assertThat(chunks).isNotEmpty();
        for (String chunk : chunks) {
            assertThat(chunk.codePointCount(0, chunk.length())).isLessThanOrEqualTo(50);
            assertThat(chunk).isNotBlank();
            assertThat(text).as("切片必须是原文的连续子串（规范化之后）").contains(chunk);
        }
    }

    @Test
    void chunksStrictlyProgressSoTheLoopAlwaysTerminates() {
        // overlap = chunkSize - 1 是极端配置：必须仍然严格前进，不得死循环
        DeterministicDocumentChunker chunker = chunker(20, 19);
        String text = "abcdefghij".repeat(20);

        List<String> chunks = chunker.chunk(text);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.codePointCount(0, chunk.length())).isLessThanOrEqualTo(20));
    }

    @Test
    void overlapZeroProducesNonOverlappingChunks() {
        DeterministicDocumentChunker chunker = chunker(10, 0);
        String text = "0123456789".repeat(3);

        List<String> chunks = chunker.chunk(text);

        assertThat(chunks).containsExactly("0123456789", "0123456789", "0123456789");
    }

    // ---------- 边界优先级 ----------

    @Test
    void prefersParagraphBoundaries() {
        DeterministicDocumentChunker chunker = chunker(12, 0);
        String text = "第一段内容。\n第二段内容。\n第三段内容。";

        List<String> chunks = chunker.chunk(text);

        assertThat(chunks.get(0)).as("换行优先于句末标点").isEqualTo("第一段内容。");
        assertThat(chunks.get(1)).isEqualTo("第二段内容。");
        assertThat(chunks.get(2)).isEqualTo("第三段内容。");
    }

    @Test
    void breaksAtChineseSentenceEndWhenNoParagraphBoundaryExists() {
        DeterministicDocumentChunker chunker = chunker(12, 0);
        String text = "第一句结束。第二句结束。第三句结束。";

        List<String> chunks = chunker.chunk(text);

        // 回溯窗口限制在「后半段」内（chunkSize/2 起），因此第一片取窗口内最后一个句末标点
        assertThat(chunks.get(0)).isEqualTo("第一句结束。第二句结束。");
        assertThat(chunks.get(1)).isEqualTo("第三句结束。");
    }

    @Test
    void breaksAtEnglishSentenceEnd() {
        DeterministicDocumentChunker chunker = chunker(14, 0);
        String text = "First one. Second two. Third three.";

        List<String> chunks = chunker.chunk(text);

        assertThat(chunks.get(0)).as("在英文句点后切开，且不切开单词").isEqualTo("First one.");
    }

    @Test
    void breaksAtWhitespaceWhenThereIsNoSentenceEnd() {
        DeterministicDocumentChunker chunker = chunker(11, 0);
        String text = "alpha beta gamma delta epsilon";

        List<String> chunks = chunker.chunk(text);

        assertThat(chunks.get(0)).as("没有句末标点时退化为空白边界").isEqualTo("alpha beta");
    }

    @Test
    void hardCutsWhenThereIsNoBoundaryAtAll() {
        DeterministicDocumentChunker chunker = chunker(10, 0);
        String text = "一".repeat(25);

        List<String> chunks = chunker.chunk(text);

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0)).hasSize(10);
        assertThat(chunks.get(1)).hasSize(10);
        assertThat(chunks.get(2)).as("最后一片是余下的 5 个字符").hasSize(5);
    }

    @Test
    void neverProducesAnExtremelyShortChunkJustBecauseOfALateBoundary() {
        // 边界只在前半段之后才被接受：避免切出「一个字符 + 一大段」这种退化结果
        DeterministicDocumentChunker chunker = chunker(20, 0);
        String text = "开始。" + "内容".repeat(20);

        List<String> chunks = chunker.chunk(text);

        assertThat(chunks.get(0).codePointCount(0, chunks.get(0).length()))
                .isGreaterThanOrEqualTo(10);
    }

    // ---------- code point 安全 ----------

    @Test
    void neverSplitsSurrogatePairs() {
        DeterministicDocumentChunker chunker = chunker(5, 0);
        String text = "📄🚀📊🔥📈🧾📌".repeat(4);

        List<String> chunks = chunker.chunk(text);

        for (String chunk : chunks) {
            assertThat(Character.isHighSurrogate(chunk.charAt(chunk.length() - 1)))
                    .as("切片末尾不得留下孤立的高代理").isFalse();
            assertThat(Character.isLowSurrogate(chunk.charAt(0)))
                    .as("切片开头不得出现孤立的低代理").isFalse();
            assertThat(chunk.codePointCount(0, chunk.length())).isLessThanOrEqualTo(5);
        }
    }

    @Test
    void countsChineseAndEmojiByCodePointNotByUtf16Unit() {
        DeterministicDocumentChunker chunker = chunker(4, 0);
        String text = "中文📄测试";

        List<String> chunks = chunker.chunk(text);

        assertThat(chunks).as("5 个 code point、6 个 UTF-16 单元：按 code point 切成 4 + 1")
                .containsExactly("中文📄测", "试");
    }

    // ---------- 确定性 ----------

    @Test
    void theSameInputAlwaysProducesTheSameChunks() {
        DeterministicDocumentChunker chunker = chunker(30, 5);
        String text = "第一段内容很长很长。\n\n第二段内容也很长很长。\n\n第三段内容。";

        List<String> first = chunker.chunk(text);
        List<String> second = chunker.chunk(text);

        assertThat(second).isEqualTo(first);
    }

    @Test
    void independentlyConstructedChunkersProduceIdenticalResults() {
        String text = "季度运维报告。".repeat(50);

        assertThat(chunker(40, 8).chunk(text)).isEqualTo(chunker(40, 8).chunk(text));
    }

    // ---------- 上限 ----------

    @Test
    void exceedingMaxChunksFailsInsteadOfTruncatingSilently() {
        DeterministicDocumentChunker chunker = new DeterministicDocumentChunker(10, 0, 3);

        assertThatThrownBy(() -> chunker.chunk("一".repeat(100)))
                .isInstanceOf(DocumentChunkingException.class)
                .extracting(thrown -> ((DocumentChunkingException) thrown).failureCode())
                .isEqualTo(KnowledgeParseFailureCode.TOO_MANY_CHUNKS);
    }

    @Test
    void exactlyMaxChunksIsStillAccepted() {
        DeterministicDocumentChunker chunker = new DeterministicDocumentChunker(10, 0, 3);

        assertThat(chunker.chunk("一".repeat(30))).hasSize(3);
    }

    // ---------- 规范化 ----------

    @Test
    void normalizerUnifiesLineEndings() {
        assertThat(DocumentTextNormalizer.normalize("a\r\nb\rc")).isEqualTo("a\nb\nc");
    }

    @Test
    void normalizerCollapsesRunsOfBlankLines() {
        assertThat(DocumentTextNormalizer.normalize("a\n\n\n\n\nb")).isEqualTo("a\n\nb");
        assertThat(DocumentTextNormalizer.normalize("a\n \n \n b")).isEqualTo("a\n\n b");
    }

    @Test
    void normalizerAppliesUnicodeNfc() {
        String decomposed = "e\u0301";       // e + 组合重音
        String composed = "\u00e9";          // é

        assertThat(DocumentTextNormalizer.normalize(decomposed)).isEqualTo(composed);
        assertThat(DocumentTextNormalizer.normalize(decomposed)).isNotEqualTo(decomposed);
    }

    @Test
    void normalizerTrimsOuterWhitespaceOnly() {
        assertThat(DocumentTextNormalizer.normalize("  a  b  ")).isEqualTo("a  b");
        assertThat(DocumentTextNormalizer.normalize("\n\n")).isEmpty();
    }

    @Test
    void normalizationIsIdempotent() {
        String once = DocumentTextNormalizer.normalize("a\r\n\r\n\r\nb  ");
        assertThat(DocumentTextNormalizer.normalize(once)).isEqualTo(once);
    }

    @Test
    void chunkingIsStableAcrossLineEndingVariants() {
        DeterministicDocumentChunker chunker = chunker(10, 0);

        assertThat(chunker.chunk("第一段。\r\n第二段。")).isEqualTo(chunker.chunk("第一段。\n第二段。"));
    }

    private static DeterministicDocumentChunker chunker(int chunkSize, int overlap) {
        return new DeterministicDocumentChunker(chunkSize, overlap, 10_000);
    }
}
