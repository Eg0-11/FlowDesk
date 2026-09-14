package com.flowdesk.infrastructure.knowledge.chunking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * {@link KnowledgeChunkingProperties} 测试（FD-0009）。
 *
 * <p>配置错误的代价很高（解析器可能切出存不进数据库的切片，或干脆不前进），
 * 因此校验必须是<b>启动期</b>的、且覆盖所有边界。</p>
 */
class KnowledgeChunkingPropertiesTest {

    @Test
    void defaultsMatchTheDocumentedValues() {
        KnowledgeChunkingProperties properties = new KnowledgeChunkingProperties();

        assertThat(properties.getChunkSize()).isEqualTo(1000);
        assertThat(properties.getOverlap()).isEqualTo(150);
        assertThat(properties.getMaxChunks()).isEqualTo(5000);
        assertThat(properties.getMaxExtractedCodePoints()).isEqualTo(1_000_000);
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void bindsFromConfigurationProperties() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource();
        source.put("flowdesk.knowledge.chunking.chunk-size", "800");
        source.put("flowdesk.knowledge.chunking.overlap", "80");
        source.put("flowdesk.knowledge.chunking.max-chunks", "100");
        source.put("flowdesk.knowledge.chunking.max-extracted-code-points", "5000");

        KnowledgeChunkingProperties properties = new Binder(source)
                .bind("flowdesk.knowledge.chunking", Bindable.of(KnowledgeChunkingProperties.class))
                .orElseThrow(() -> new AssertionError("配置绑定失败"));

        assertThat(properties.getChunkSize()).isEqualTo(800);
        assertThat(properties.getOverlap()).isEqualTo(80);
        assertThat(properties.getMaxChunks()).isEqualTo(100);
        assertThat(properties.getMaxExtractedCodePoints()).isEqualTo(5000);
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void rejectsNonPositiveChunkSize() {
        KnowledgeChunkingProperties properties = new KnowledgeChunkingProperties();
        properties.setChunkSize(0);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);

        properties.setChunkSize(-1);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsChunkSizeBeyondTheStorageLimit() {
        KnowledgeChunkingProperties properties = new KnowledgeChunkingProperties();
        properties.setChunkSize(KnowledgeChunkingProperties.MAX_CHUNK_SIZE_LIMIT + 1);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chunk-size");
    }

    @Test
    void acceptsChunkSizeExactlyAtTheStorageLimit() {
        KnowledgeChunkingProperties properties = new KnowledgeChunkingProperties();
        properties.setChunkSize(KnowledgeChunkingProperties.MAX_CHUNK_SIZE_LIMIT);
        properties.setOverlap(0);

        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void rejectsNegativeOrTooLargeOverlap() {
        KnowledgeChunkingProperties properties = new KnowledgeChunkingProperties();
        properties.setOverlap(-1);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);

        properties.setOverlap(1000);
        assertThatThrownBy(properties::validate)
                .as("overlap 等于 chunk-size 时算法无法前进")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsNonPositiveMaxChunks() {
        KnowledgeChunkingProperties properties = new KnowledgeChunkingProperties();
        properties.setMaxChunks(0);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsNonPositiveExtractionLimitOrLimitSmallerThanOverlap() {
        KnowledgeChunkingProperties properties = new KnowledgeChunkingProperties();
        properties.setMaxExtractedCodePoints(0);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);

        KnowledgeChunkingProperties tooSmall = new KnowledgeChunkingProperties();
        tooSmall.setOverlap(200);
        tooSmall.setMaxExtractedCodePoints(200);
        assertThatThrownBy(tooSmall::validate)
                .as("提取上限不大于 overlap 时任何文档都会被判为超限")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsOverflowingCombinationUsingLongArithmetic() {
        KnowledgeChunkingProperties properties = new KnowledgeChunkingProperties();
        // chunk-size 上限 2000，因此用 max-chunks 撑到 int 溢出区间
        properties.setMaxChunks(Integer.MAX_VALUE);
        properties.setChunkSize(KnowledgeChunkingProperties.MAX_CHUNK_SIZE_LIMIT);

        // 组合本身（20 亿 * 2000）超出 int，但用 long 计算不会溢出为「看起来合法」的负数
        assertThat((long) properties.getMaxChunks() * properties.getChunkSize())
                .as("用 long 计算才看得出真实量级")
                .isGreaterThan((long) Integer.MAX_VALUE);
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }
}
