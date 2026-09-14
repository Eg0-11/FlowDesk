package com.flowdesk.infrastructure.knowledge.chunking;

import com.flowdesk.domain.knowledge.KnowledgeDocumentChunk;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 切片与解析规模配置（FD-0009）。
 *
 * <p>默认值：chunk-size 1000、overlap 150、max-chunks 5000、max-extracted-code-points 1,000,000
 * （全部以 Unicode <b>code point</b> 计，不是 UTF-16 单元）。</p>
 *
 * <p>{@link #validate()} 在装配阶段执行：配置不合法就让应用启动失败，
 * 而不是等到解析某个文档时才在运行期暴露。</p>
 */
@ConfigurationProperties(prefix = "flowdesk.knowledge.chunking")
public class KnowledgeChunkingProperties {

    /**
     * 单片最大长度（code points）的安全上限。
     *
     * <p>它由存储列容量反推：切片的 UTF-16 长度最多是 code point 数的 2 倍
     * （代理对占 2 个 char），而列是 {@code VARCHAR(4000)}，因此 2000 是安全的硬上限。
     * 这不是业务偏好，而是「切片必须能存进数据库」的物理约束。</p>
     */
    public static final int MAX_CHUNK_SIZE_LIMIT = 2000;

    private int chunkSize = 1000;

    private int overlap = 150;

    private int maxChunks = 5000;

    private int maxExtractedCodePoints = 1_000_000;

    /**
     * 严格校验配置，任何不合法都抛异常让应用启动失败。
     *
     * <p>所有比较都用 {@code long}：{@code maxChunks * chunkSize} 之类的乘积在 {@code int}
     * 上可能溢出，从而让「上限很大」的配置看起来合法。</p>
     */
    public void validate() {
        if (this.chunkSize <= 0) {
            throw new IllegalStateException("flowdesk.knowledge.chunking.chunk-size 必须大于 0");
        }
        if (this.chunkSize > MAX_CHUNK_SIZE_LIMIT) {
            throw new IllegalStateException("flowdesk.knowledge.chunking.chunk-size 不能超过 "
                    + MAX_CHUNK_SIZE_LIMIT + "（受切片内容列容量限制）");
        }
        if (this.overlap < 0) {
            throw new IllegalStateException("flowdesk.knowledge.chunking.overlap 不能为负数");
        }
        if (this.overlap >= this.chunkSize) {
            throw new IllegalStateException("flowdesk.knowledge.chunking.overlap 必须小于 chunk-size，"
                    + "否则切片算法无法前进");
        }
        if (this.maxChunks <= 0) {
            throw new IllegalStateException("flowdesk.knowledge.chunking.max-chunks 必须大于 0");
        }
        if (this.maxExtractedCodePoints <= 0) {
            throw new IllegalStateException(
                    "flowdesk.knowledge.chunking.max-extracted-code-points 必须大于 0");
        }
        long worstCaseBytes = (long) this.maxChunks * this.chunkSize;
        if (worstCaseBytes <= 0L) {
            throw new IllegalStateException("flowdesk.knowledge.chunking 配置组合导致数值溢出");
        }
        if ((long) this.maxExtractedCodePoints < (long) this.overlap + 1L) {
            throw new IllegalStateException("提取上限必须大于 overlap，否则任何文档都会被判为超限");
        }
    }

    /**
     * @return 单片最大 code point 数
     */
    public int getChunkSize() {
        return this.chunkSize;
    }

    /**
     * @param chunkSize 单片最大 code point 数
     */
    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    /**
     * @return 相邻切片保留的重叠 code point 数
     */
    public int getOverlap() {
        return this.overlap;
    }

    /**
     * @param overlap 相邻切片保留的重叠 code point 数
     */
    public void setOverlap(int overlap) {
        this.overlap = overlap;
    }

    /**
     * @return 单片文档允许的最大切片数
     */
    public int getMaxChunks() {
        return this.maxChunks;
    }

    /**
     * @param maxChunks 单片文档允许的最大切片数
     */
    public void setMaxChunks(int maxChunks) {
        this.maxChunks = maxChunks;
    }

    /**
     * @return 单次解析允许提取的最大 code point 数
     */
    public int getMaxExtractedCodePoints() {
        return this.maxExtractedCodePoints;
    }

    /**
     * @param maxExtractedCodePoints 单次解析允许提取的最大 code point 数
     */
    public void setMaxExtractedCodePoints(int maxExtractedCodePoints) {
        this.maxExtractedCodePoints = maxExtractedCodePoints;
    }

    /**
     * @return 存储列容量允许的单片长度上限（供测试引用）
     */
    public static int maxStorableChunkLength() {
        return KnowledgeDocumentChunk.MAX_CONTENT_LENGTH;
    }
}
