package com.flowdesk.domain.knowledge;

import java.util.Arrays;

/**
 * 切片向量：不可变、自校验的领域值对象。
 *
 * <h2>为什么不是 record</h2>
 * <p>record 的 {@code equals}/{@code hashCode} 对数组字段使用<b>引用比较</b>，
 * 因此两个内容完全相同的向量会被判为不等 —— 这在「先比较再决定是否重建索引」这类逻辑里
 * 会静默产生错误结论。本类显式用 {@link Arrays#equals(float[], float[])} 实现按内容比较。</p>
 *
 * <h2>不变量（全部在构造期强制）</h2>
 * <ul>
 *   <li>所属文档、切片序号、切片摘要、描述符与向量都不得为 {@code null}；</li>
 *   <li>向量长度必须<b>恰好</b>等于描述符声明的维度（本项目固定 1024）；</li>
 *   <li>向量必须全部是有限值：拒绝 {@code NaN}、{@code Infinity}、{@code -Infinity}
 *       —— 它们会让余弦距离计算出无意义的结果，而且一旦写进向量库就再也无法用 SQL 过滤干净；</li>
 *   <li>向量不得为全零：全零向量在余弦相似度下与任何查询都「等距」，
 *       是上游异常（例如把失败响应当成成功解析）的典型产物。</li>
 * </ul>
 *
 * <p>向量数组在构造时<b>防御性复制</b>，读取时再复制一份，因此外部拿到的引用无法改写内部状态。</p>
 */
public final class KnowledgeDocumentChunkEmbedding {

    private final KnowledgeDocumentId documentId;

    private final int chunkIndex;

    private final Sha256Digest chunkSha256;

    private final EmbeddingDescriptor descriptor;

    private final float[] vector;

    /**
     * @param documentId  所属文档标识
     * @param chunkIndex  切片序号
     * @param chunkSha256 该切片内容的摘要（用于在写入前二次证明切片没有被换掉）
     * @param descriptor  向量描述符（provider / model / dimensions）
     * @param vector      向量数值
     * @throws KnowledgeDomainException 任一不变量不成立
     */
    public KnowledgeDocumentChunkEmbedding(KnowledgeDocumentId documentId,
            int chunkIndex,
            Sha256Digest chunkSha256,
            EmbeddingDescriptor descriptor,
            float[] vector) {

        if (documentId == null) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_VECTOR, "向量必须属于某个文档");
        }
        if (chunkIndex < 0) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_VECTOR, "向量所属切片序号不能为负数");
        }
        if (chunkSha256 == null) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_VECTOR, "向量必须携带切片摘要");
        }
        if (descriptor == null) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_VECTOR, "向量必须携带描述符");
        }
        if (vector == null) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_VECTOR, "向量数值不能为空");
        }
        if (vector.length != descriptor.dimensions()) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_VECTOR,
                    "向量长度必须等于描述符声明的维度");
        }

        boolean anyNonZero = false;
        for (float value : vector) {
            if (Float.isNaN(value) || Float.isInfinite(value)) {
                throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_VECTOR,
                        "向量必须全部是有限数值");
            }
            if (value != 0.0f) {
                anyNonZero = true;
            }
        }
        if (!anyNonZero) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_VECTOR, "向量不能全为零");
        }

        this.documentId = documentId;
        this.chunkIndex = chunkIndex;
        this.chunkSha256 = chunkSha256;
        this.descriptor = descriptor;
        this.vector = vector.clone();
    }

    /**
     * @return 所属文档标识
     */
    public KnowledgeDocumentId documentId() {
        return this.documentId;
    }

    /**
     * @return 切片序号
     */
    public int chunkIndex() {
        return this.chunkIndex;
    }

    /**
     * @return 切片内容摘要
     */
    public Sha256Digest chunkSha256() {
        return this.chunkSha256;
    }

    /**
     * @return 向量描述符
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
     * 返回向量的<b>副本</b>：调用方改写它不会影响本对象。
     *
     * @return 向量数值副本
     */
    public float[] vector() {
        return this.vector.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof KnowledgeDocumentChunkEmbedding embedding
                && this.chunkIndex == embedding.chunkIndex
                && this.documentId.equals(embedding.documentId)
                && this.chunkSha256.equals(embedding.chunkSha256)
                && this.descriptor.equals(embedding.descriptor)
                && Arrays.equals(this.vector, embedding.vector);
    }

    @Override
    public int hashCode() {
        int result = this.documentId.hashCode();
        result = 31 * result + this.chunkIndex;
        result = 31 * result + this.chunkSha256.hashCode();
        result = 31 * result + this.descriptor.hashCode();
        result = 31 * result + Arrays.hashCode(this.vector);
        return result;
    }

    /**
     * 刻意不输出向量数值：1024 个浮点数既没有诊断价值，也不应该出现在日志里。
     *
     * @return 只含标识、序号与维度的描述
     */
    @Override
    public String toString() {
        return "KnowledgeDocumentChunkEmbedding[" + this.documentId + "#" + this.chunkIndex + ", "
                + this.descriptor.provider() + "/" + this.descriptor.model() + ", "
                + this.descriptor.dimensions() + "d]";
    }
}
