package com.flowdesk.domain.knowledge;

import java.util.Arrays;

/**
 * 查询向量：用户问题经 Query Embedding 得到的一条向量（RAG 4/6）。
 *
 * <h2>为什么是独立值对象，而不是复用 {@link KnowledgeDocumentChunkEmbedding}</h2>
 * <p>文档侧的向量必须携带「属于哪个文档、哪个切片、切片摘要是什么」，因为它要写进业务表；
 * 查询向量<b>没有归属</b>，它只在一个请求内有效、不落库、不参与切片一致性证明。
 * 两者共享的只有「描述符 + 1024 维 + 有限数值」这些不变量 —— 把它们塞进同一个类型，
 * 会逼着查询路径伪造 documentId / chunkIndex 这类字段。</p>
 *
 * <h2>不变量（全部在构造期强制）</h2>
 * <ul>
 *   <li>描述符与向量都不得为 {@code null}；</li>
 *   <li>向量长度必须<b>恰好</b>等于描述符声明的维度（本项目固定 1024）——
 *       维度不符时 SQL 里的向量距离计算会直接失败，必须在进入数据库之前拒绝；</li>
 *   <li>向量必须全部是有限值：拒绝 {@code NaN}、{@code Infinity}、{@code -Infinity}
 *       —— 它们会让余弦距离变成无意义的数值，甚至匹配到任意切片；</li>
 *   <li>向量不得为全零：全零向量与任何向量的余弦相似度都没有定义，
 *       是上游把失败响应当成成功解析的典型产物。</li>
 * </ul>
 *
 * <p>向量数组在构造时<b>防御性复制</b>，读取时再复制一份，因此外部拿到的引用无法改写内部状态。</p>
 * <p>{@link #toString()} 刻意不输出向量数值：它既不比维度更有信息量，也不应该出现在日志里。</p>
 */
public final class KnowledgeQueryEmbedding {

    private final EmbeddingDescriptor descriptor;

    private final float[] vector;

    /**
     * @param descriptor 查询向量的描述符（必须与文档侧向量使用同一个 provider/model/dimensions）
     * @param vector     查询向量数值
     * @throws KnowledgeDomainException 任一不变量不成立
     */
    public KnowledgeQueryEmbedding(EmbeddingDescriptor descriptor, float[] vector) {
        if (descriptor == null) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_VECTOR, "查询向量必须携带描述符");
        }
        if (vector == null) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_VECTOR, "查询向量数值不能为空");
        }
        if (vector.length != descriptor.dimensions()) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_VECTOR,
                    "查询向量长度必须等于描述符声明的维度");
        }

        boolean anyNonZero = false;
        for (float value : vector) {
            if (Float.isNaN(value) || Float.isInfinite(value)) {
                throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_VECTOR,
                        "查询向量必须全部是有限数值");
            }
            if (value != 0.0f) {
                anyNonZero = true;
            }
        }
        if (!anyNonZero) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_VECTOR, "查询向量不能全为零");
        }

        this.descriptor = descriptor;
        this.vector = vector.clone();
    }

    /**
     * @return 查询向量描述符
     */
    public EmbeddingDescriptor descriptor() {
        return this.descriptor;
    }

    /**
     * @return 向量维度的便捷访问（等于描述符维度）
     */
    public int dimensions() {
        return this.descriptor.dimensions();
    }

    /**
     * 返回向量数值的<b>副本</b>：调用方改写它不会影响本对象。
     *
     * @return 查询向量数值副本
     */
    public float[] vector() {
        return this.vector.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof KnowledgeQueryEmbedding embedding
                && this.descriptor.equals(embedding.descriptor)
                && Arrays.equals(this.vector, embedding.vector);
    }

    @Override
    public int hashCode() {
        return 31 * this.descriptor.hashCode() + Arrays.hashCode(this.vector);
    }

    /**
     * @return 只含 provider/model/维度的描述（不含任何向量数值）
     */
    @Override
    public String toString() {
        return "KnowledgeQueryEmbedding[" + this.descriptor.provider() + "/" + this.descriptor.model()
                + ", " + this.descriptor.dimensions() + "d]";
    }
}
