package com.flowdesk.infrastructure.knowledge.persistence.jdbc;

/**
 * pgvector 字面量序列化（写入与查询共用）。
 *
 * <p>pgvector 接受文本形式的字面量（{@code [0.1,0.2,...]}），配合显式 {@code ?::vector} 转换
 * 就不会依赖驱动的类型推断。写入路径（{@code INSERT}）与查询路径（距离计算、阈值过滤、排序）
 * 都必须产生<b>同一种</b>表示，否则同一个数值在不同语句里会被解析成不同的向量。</p>
 *
 * <h2>契约</h2>
 * <ul>
 *   <li><b>Locale 无关</b>：使用 {@link Float#toString(float)}，它按 Java 规范始终输出
 *       点号小数点（不随 {@code Locale} 变化）—— 在土耳其语等区域设置下
 *       {@code String.format("%f")} 之类的写法会产出逗号小数点并直接破坏 SQL 字面量；</li>
 *   <li><b>只输出有限数值</b>：{@code NaN} 与 {@code ±Infinity} 会被 pgvector 拒绝或写入无意义的值，
 *       因此在序列化时就拒绝；</li>
 *   <li><b>不记录、不输出完整向量</b>：本类没有任何日志，异常信息也不含数值；</li>
 *   <li>空向量或 {@code null} 一律拒绝（调用方在此之前已经校验过维度与归属）。</li>
 * </ul>
 *
 * <p>本类只做「数值 → 文本」，不做维度与业务校验：那些属于领域与应用层。</p>
 */
final class PgVectorLiteral {

    private PgVectorLiteral() {
    }

    /**
     * 把向量序列化为 pgvector 字面量。
     *
     * @param vector 向量数值
     * @return 形如 {@code [0.1,0.2]} 的字面量
     * @throws IllegalArgumentException 向量为 {@code null}/空，或含非有限数值
     */
    static String serialize(float[] vector) {
        if (vector == null) {
            throw new IllegalArgumentException("向量不能为 null");
        }
        if (vector.length == 0) {
            throw new IllegalArgumentException("向量不能为空");
        }
        StringBuilder literal = new StringBuilder(vector.length * 12 + 2);
        literal.append('[');
        for (int index = 0; index < vector.length; index++) {
            float value = vector[index];
            if (Float.isNaN(value) || Float.isInfinite(value)) {
                throw new IllegalArgumentException("向量必须全部是有限数值");
            }
            if (index > 0) {
                literal.append(',');
            }
            literal.append(Float.toString(value));
        }
        return literal.append(']').toString();
    }
}
