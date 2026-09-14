package com.flowdesk.infrastructure.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

/**
 * 上传上限配置一致性校验测试（FD-0008-R1）。
 *
 * <p>防的是「配置漂移」：应用上限与容器上限是两个独立配置项，
 * 只改其中一项会让另一个成为真正的天花板，而配置表面上看是"改成功了"。</p>
 */
class KnowledgeUploadLimitValidatorTest {

    @Test
    void acceptsEqualLimits() {
        KnowledgeUploadLimitValidator.validate(DataSize.ofMegabytes(20), DataSize.ofMegabytes(20),
                DataSize.ofMegabytes(22));
    }

    @Test
    void acceptsContainerLimitLargerThanApplicationLimit() {
        // 容器更宽松是安全的：应用层仍会按实际读取字节数拦住
        KnowledgeUploadLimitValidator.validate(DataSize.ofMegabytes(1), DataSize.ofMegabytes(20),
                DataSize.ofMegabytes(22));
    }

    @Test
    void rejectsContainerFileLimitSmallerThanApplicationLimit() {
        // 典型漂移：应用改成 25MB，容器仍是 20MB → 21MB 的请求会被容器提前拒绝
        assertThatThrownBy(() -> KnowledgeUploadLimitValidator.validate(DataSize.ofMegabytes(25),
                DataSize.ofMegabytes(20), DataSize.ofMegabytes(22)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-file-size")
                .hasMessageContaining("不可达");
    }

    @Test
    void rejectsRequestLimitThatCannotAccommodateTheFileLimit() {
        // 请求上限等于文件上限：连「恰好达到文件上限」的请求都会因为 multipart 边框被拒
        assertThatThrownBy(() -> KnowledgeUploadLimitValidator.validate(DataSize.ofKilobytes(512),
                DataSize.ofKilobytes(512), DataSize.ofKilobytes(512)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-request-size");

        // 只多出 512 字节（不足 1KB 余量）同样拒绝
        assertThatThrownBy(() -> KnowledgeUploadLimitValidator.validate(DataSize.ofKilobytes(512),
                DataSize.ofKilobytes(512), DataSize.ofBytes(512L * 1024L + 512L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-request-size");
    }

    @Test
    void acceptsRequestLimitWithEnoughOverhead() {
        // 文件上限 + 1KB 恰好满足最低要求
        KnowledgeUploadLimitValidator.validate(DataSize.ofKilobytes(512), DataSize.ofKilobytes(512),
                DataSize.ofBytes(512L * 1024L + 1024L));

        // 更宽松当然也可以
        KnowledgeUploadLimitValidator.validate(DataSize.ofKilobytes(512), DataSize.ofKilobytes(512),
                DataSize.ofMegabytes(2));
    }

    @Test
    void rejectsNonPositiveApplicationLimit() {
        assertThatThrownBy(() -> KnowledgeUploadLimitValidator.validate(DataSize.ofBytes(0),
                DataSize.ofMegabytes(20), DataSize.ofMegabytes(22)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("必须为正");

        assertThatThrownBy(() -> KnowledgeUploadLimitValidator.validate(DataSize.ofMegabytes(1),
                DataSize.ofBytes(0), DataSize.ofMegabytes(22)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("必须为正");
    }

    @Test
    void rejectsNullArguments() {
        assertThatThrownBy(() -> KnowledgeUploadLimitValidator.validate(null, DataSize.ofMegabytes(1),
                DataSize.ofMegabytes(2))).isInstanceOf(NullPointerException.class);
    }

    // ---------- 溢出边界（FD-0008-R2） ----------

    @Test
    void rejectsNonPositiveRequestLimit() {
        assertThatThrownBy(() -> KnowledgeUploadLimitValidator.validate(DataSize.ofMegabytes(1),
                DataSize.ofMegabytes(20), DataSize.ofBytes(0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-request-size")
                .hasMessageContaining("必须为正");
    }

    @Test
    void rejectsRequestLimitWhenRequiredOverheadWouldOverflow() {
        // 旧实现用 fileLimit + overhead 判断：Long.MAX_VALUE + 1KB 会回绕成负数，
        // 于是「请求上限等于文件上限」这种明显冲突反而被接受
        assertThatThrownBy(() -> KnowledgeUploadLimitValidator.validate(DataSize.ofMegabytes(1),
                DataSize.ofBytes(Long.MAX_VALUE), DataSize.ofBytes(Long.MAX_VALUE)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-request-size");

        // 只差 1 个字节的余量同样冲突，且不得因为加法回绕而通过
        assertThatThrownBy(() -> KnowledgeUploadLimitValidator.validate(DataSize.ofMegabytes(1),
                DataSize.ofBytes(Long.MAX_VALUE - 1L), DataSize.ofBytes(Long.MAX_VALUE)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-request-size");
    }

    @Test
    void rejectsLongMaxFileAndRequestLimitsBecauseNoOverheadFits() {
        assertThatThrownBy(() -> KnowledgeUploadLimitValidator.validate(DataSize.ofBytes(Long.MAX_VALUE),
                DataSize.ofBytes(Long.MAX_VALUE), DataSize.ofBytes(Long.MAX_VALUE)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-request-size");

        // 应用上限同样是 Long.MAX_VALUE 时，first check（file >= app）通过，仍然必须在余量检查处拒绝
        assertThatThrownBy(() -> KnowledgeUploadLimitValidator.validate(DataSize.ofMegabytes(20),
                DataSize.ofBytes(Long.MAX_VALUE), DataSize.ofBytes(Long.MAX_VALUE)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void acceptsHugeButConsistentLimits() {
        // 余量足够时，接近 Long.MAX_VALUE 的配置也必须被接受（无回绕误判）
        KnowledgeUploadLimitValidator.validate(DataSize.ofMegabytes(1),
                DataSize.ofBytes(Long.MAX_VALUE - 2048L), DataSize.ofBytes(Long.MAX_VALUE));
    }

    @Test
    void defaultConfigurationSatisfiesTheValidator() {
        // 与 application.yml 中的默认值保持一致：20MB / 20MB / 22MB
        KnowledgeUploadLimitValidator.validate(DataSize.ofMegabytes(20), DataSize.ofMegabytes(20),
                DataSize.ofMegabytes(22));

        assertThat(KnowledgeUploadLimitValidator.MULTIPART_OVERHEAD).isEqualTo(DataSize.ofKilobytes(1));
    }
}
