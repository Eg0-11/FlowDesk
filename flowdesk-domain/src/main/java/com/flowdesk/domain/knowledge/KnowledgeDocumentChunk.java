package com.flowdesk.domain.knowledge;

import java.time.Instant;

/**
 * 知识文档切片（chunk）：解析后按确定性规则切出的文本片段。
 *
 * <p>切片是<b>解析产物</b>而非用户输入：它只由「解析 + 切片」链路生成，
 * 因此本类没有客户端可控的字段，也不接受空内容。</p>
 *
 * <h2>不变量</h2>
 * <ul>
 *   <li>{@code documentId} 非空；</li>
 *   <li>{@code chunkIndex >= 0}（同一文档内从 0 连续递增，连续性由切片器与应用层保证）；</li>
 *   <li>{@code content} 非空且非空白；</li>
 *   <li>{@code codePointCount} 必须<b>等于</b> {@code content} 的真实 Unicode code point 数
 *       —— 这是切片器最容易被写错的点（用 {@code String.length()} 会按 UTF-16 单元计数，
 *       把 emoji 与代理对算成 2），因此领域层自己再算一遍；</li>
 *   <li>{@code sha256} 为 64 位小写十六进制；</li>
 *   <li>{@code createdAt} 非空。</li>
 * </ul>
 *
 * <p>切片聚合<b>只承载纯文本</b>，刻意<b>不含</b> embedding 或向量字段：
 * 向量由独立的领域对象 {@link KnowledgeDocumentChunkEmbedding}（以及独立的向量表）承载，
 * 从而把「模型血缘（provider/model/dimensions）」与「向量数值」挡在切片之外 ——
 * 切片表达的是「文档被切成了什么」，与「用哪个模型把它向量化过」是两件事，
 * 混在一起会让更换模型、重新索引、以及逐切片比对摘要都变得困难。</p>
 *
 * @param documentId     所属文档标识
 * @param chunkIndex     文档内序号，从 0 开始
 * @param content        切片文本
 * @param codePointCount 切片的 Unicode code point 数
 * @param sha256         切片内容的 SHA-256
 * @param createdAt      切片生成时间
 */
public record KnowledgeDocumentChunk(KnowledgeDocumentId documentId,
                                     int chunkIndex,
                                     String content,
                                     int codePointCount,
                                     Sha256Digest sha256,
                                     Instant createdAt) {

    /** 切片内容的最大长度（与数据库列容量一致）。 */
    public static final int MAX_CONTENT_LENGTH = 4000;

    public KnowledgeDocumentChunk {
        if (documentId == null) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_CHUNK, "切片必须属于某个文档");
        }
        if (chunkIndex < 0) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_CHUNK, "切片序号不能为负数");
        }
        if (content == null || content.isBlank()) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_CHUNK, "切片内容不能为空白");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_CHUNK,
                    "切片内容长度不能超过 " + MAX_CONTENT_LENGTH);
        }
        int actualCodePoints = content.codePointCount(0, content.length());
        if (codePointCount != actualCodePoints) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_CHUNK,
                    "切片 code point 计数与实际内容不一致");
        }
        if (sha256 == null) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_CHUNK, "切片摘要不能为空");
        }
        if (createdAt == null) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_CHUNK, "切片时间不能为空");
        }
    }

    @Override
    public String toString() {
        return "KnowledgeDocumentChunk[" + this.documentId + "#" + this.chunkIndex + ", "
                + this.codePointCount + " code points]";
    }
}
