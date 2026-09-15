package com.flowdesk.infrastructure.knowledge.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * pgvector 字面量序列化测试（RAG 4/6）。
 *
 * <p>写入与查询两条路径必须产生<b>同一种</b>表示：{@code ?::vector} 的字面量一旦格式不同，
 * 同一个数值在不同语句里会被解析成不同的向量。这里锁定格式、Locale 无关性与
 * 「只输出有限数值」三条契约。</p>
 */
class PgVectorLiteralTest {

    @Test
    void serializesAsAPgVectorLiteral() {
        assertThat(PgVectorLiteral.serialize(new float[] { 1.0f, 0.5f, -0.25f }))
                .isEqualTo("[1.0,0.5,-0.25]");
        assertThat(PgVectorLiteral.serialize(new float[] { 0.0f })).isEqualTo("[0.0]");
        assertThat(PgVectorLiteral.serialize(new float[] { 1.0E-5f })).isEqualTo("[1.0E-5]");
    }

    @Test
    void producesExactlyTheDocumentedShapeForA1024DimensionalVector() {
        float[] vector = new float[1024];
        Arrays.fill(vector, 0.5f);

        String literal = PgVectorLiteral.serialize(vector);

        assertThat(literal).startsWith("[0.5,0.5,").endsWith(",0.5]");
        assertThat(literal.chars().filter(character -> character == ',').count())
                .as("1024 个数值之间有 1023 个分隔符").isEqualTo(1023);
        assertThat(literal).doesNotContain("0,5").doesNotContain(" ");
    }

    @Test
    void isLocaleIndependent() {
        Locale original = Locale.getDefault();
        try {
            // 土耳其语/德语区域设置下，某些格式化写法会把小数点写成逗号，直接破坏 SQL 字面量
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(PgVectorLiteral.serialize(new float[] { 0.5f, 1.5f })).isEqualTo("[0.5,1.5]");

            Locale.setDefault(Locale.GERMANY);
            assertThat(PgVectorLiteral.serialize(new float[] { 0.5f, 1.5f })).isEqualTo("[0.5,1.5]");
        }
        finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void rejectsNonFiniteValues() {
        assertThatThrownBy(() -> PgVectorLiteral.serialize(new float[] { 0.5f, Float.NaN }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("有限");
        assertThatThrownBy(() -> PgVectorLiteral.serialize(new float[] { Float.POSITIVE_INFINITY }))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PgVectorLiteral.serialize(new float[] { Float.NEGATIVE_INFINITY }))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullAndEmptyVectors() {
        assertThatThrownBy(() -> PgVectorLiteral.serialize(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PgVectorLiteral.serialize(new float[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theFailureMessageNeverContainsVectorValues() {
        float[] vector = new float[1024];
        Arrays.fill(vector, 0.123456f);
        vector[17] = Float.NaN;

        assertThatThrownBy(() -> PgVectorLiteral.serialize(vector))
                .hasMessageNotContaining("0.123456")
                .hasMessageNotContaining("[");
    }
}
