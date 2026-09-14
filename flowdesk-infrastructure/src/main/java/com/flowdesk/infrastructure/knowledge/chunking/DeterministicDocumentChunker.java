package com.flowdesk.infrastructure.knowledge.chunking;

import com.flowdesk.application.knowledge.parse.DocumentChunkingException;
import com.flowdesk.application.knowledge.port.out.DocumentChunker;
import com.flowdesk.domain.knowledge.KnowledgeParseFailureCode;
import java.util.ArrayList;
import java.util.List;

/**
 * 确定性切片实现（FD-0009）。
 *
 * <h2>为什么按 code point 而不是 {@code char}</h2>
 * <p>{@code String.length()} 数的是 UTF-16 单元：一个 emoji 会被算成 2。
 * 按它切片会在中间切断代理对，产生非法字符串；按 code point 切片则永远不会切开字符。
 * 因此内部把文本转成 {@code int[]}（每个元素是一个 code point），只在最后才拼回字符串。</p>
 *
 * <h2>边界优先级</h2>
 * <ol>
 *   <li>段落边界（换行）—— 语义上最完整；</li>
 *   <li>中英文句末标点（{@code 。！？；.!?;}）；</li>
 *   <li>普通空白；</li>
 *   <li>都找不到时硬切（保证算法一定前进）。</li>
 * </ol>
 * <p>回溯窗口限制为半个 chunk-size：避免「刚好在开头找到一个句号」而切出极短的片，
 * 也保证单片的长度下限是合理的。</p>
 *
 * <h2>确定性</h2>
 * <p>算法只依赖输入与配置，不依赖时间、随机数、集合迭代顺序或平台默认字符集，
 * 因此相同输入与配置必然产生逐字节相同的切片。</p>
 */
public final class DeterministicDocumentChunker implements DocumentChunker {

    private final int chunkSize;

    private final int overlap;

    private final int maxChunks;

    /**
     * @param chunkSize 单片最大 code point 数
     * @param overlap   相邻片保留的重叠 code point 数，必须小于 {@code chunkSize}
     * @param maxChunks 单片文档允许的最大切片数
     */
    public DeterministicDocumentChunker(int chunkSize, int overlap, int maxChunks) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize 必须大于 0");
        }
        if (chunkSize > KnowledgeChunkingProperties.MAX_CHUNK_SIZE_LIMIT) {
            throw new IllegalArgumentException("chunkSize 超过存储允许的上限");
        }
        if (overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException("overlap 必须在 [0, chunkSize) 区间内");
        }
        if (maxChunks <= 0) {
            throw new IllegalArgumentException("maxChunks 必须大于 0");
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
        this.maxChunks = maxChunks;
    }

    @Override
    public List<String> chunk(String extractedText) {
        String text = DocumentTextNormalizer.normalize(extractedText);
        if (text.isEmpty()) {
            return List.of();
        }

        int[] codePoints = text.codePoints().toArray();
        List<String> chunks = new ArrayList<>();
        int start = 0;

        while (start < codePoints.length) {
            int end = Math.min(start + this.chunkSize, codePoints.length);
            if (end < codePoints.length) {
                int boundary = findBoundary(codePoints, start, end);
                if (boundary > start) {
                    end = boundary;
                }
            }

            String content = new String(codePoints, start, end - start).strip();
            if (!content.isEmpty()) {
                if (chunks.size() >= this.maxChunks) {
                    throw new DocumentChunkingException(KnowledgeParseFailureCode.TOO_MANY_CHUNKS,
                            "切片数量超过上限 " + this.maxChunks);
                }
                chunks.add(content);
            }

            if (end >= codePoints.length) {
                break;
            }
            // 下一片从 end 往前退 overlap 个 code point；显式保证严格前进，杜绝死循环
            int nextStart = end - this.overlap;
            start = Math.max(nextStart, start + 1);
        }
        return List.copyOf(chunks);
    }

    /**
     * 在 {@code (start, end]} 内从后往前寻找边界，返回「边界之后的第一个下标」。
     *
     * @return 边界下标；没有找到时返回 {@code end}（即硬切）
     */
    private int findBoundary(int[] codePoints, int start, int end) {
        int lowestCandidate = start + Math.max(1, this.chunkSize / 2);
        int bestBlank = -1;
        int bestSentence = -1;
        int bestWhitespace = -1;

        for (int index = end - 1; index >= lowestCandidate; index--) {
            int codePoint = codePoints[index];
            if (codePoint == '\n') {
                if (bestBlank < 0) {
                    bestBlank = index + 1;
                }
                continue;
            }
            if (bestSentence < 0 && isSentenceEnd(codePoint)) {
                bestSentence = index + 1;
                continue;
            }
            if (bestWhitespace < 0 && Character.isWhitespace(codePoint)) {
                bestWhitespace = index + 1;
            }
        }

        if (bestBlank > 0) {
            return bestBlank;
        }
        if (bestSentence > 0) {
            return bestSentence;
        }
        if (bestWhitespace > 0) {
            return bestWhitespace;
        }
        return end;
    }

    private static boolean isSentenceEnd(int codePoint) {
        return codePoint == '。' || codePoint == '！' || codePoint == '？' || codePoint == '；'
                || codePoint == '.' || codePoint == '!' || codePoint == '?' || codePoint == ';';
    }

    /**
     * @return 单片最大 code point 数
     */
    public int chunkSize() {
        return this.chunkSize;
    }

    /**
     * @return 相邻片重叠的 code point 数
     */
    public int overlap() {
        return this.overlap;
    }

    /**
     * @return 单片文档允许的最大切片数
     */
    public int maxChunks() {
        return this.maxChunks;
    }

    @Override
    public String toString() {
        return "DeterministicDocumentChunker[size=" + this.chunkSize + ", overlap=" + this.overlap
                + ", maxChunks=" + this.maxChunks + "]";
    }
}
