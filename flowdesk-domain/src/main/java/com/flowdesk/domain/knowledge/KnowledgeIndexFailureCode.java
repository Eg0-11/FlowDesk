package com.flowdesk.domain.knowledge;

/**
 * 索引（向量化）失败码：稳定、可持久化、可对外返回。
 *
 * <p>与 {@link KnowledgeParseFailureCode} 同样的约束：只保存<b>枚举</b>，
 * 绝不把第三方异常文案、HTTP 响应体、SQL、路径或密钥写进聚合 ——
 * 因此这些细节不可能被持久化，也不可能出现在响应里。</p>
 */
public enum KnowledgeIndexFailureCode {

    /** 上游 Embedding 服务失败：超时、限流、5xx、连接失败等。 */
    EMBEDDING_PROVIDER_FAILURE,

    /** 模型返回了不合法结果：数量不符、维度不符、含 null/NaN/Infinity、全零向量等。 */
    INVALID_EMBEDDING_RESPONSE,

    /** 向量存储失败：写入、状态更新或事务提交失败。 */
    VECTOR_STORAGE_FAILURE,

    /** 切片数据不满足向量化前提：错序、断号、归属错误、摘要变化或内容为空。 */
    CHUNK_DATA_INVALID
}
