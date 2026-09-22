package com.flowdesk.application.ai;

/**
 * 知识检索分支的稳定失败分类（FD-0018-A）。
 *
 * <p>检索是<b>可能整体不可用</b>的证据来源（向量化关闭、Embedding 上游故障、重排上游故障、内部失败），
 * 这些情形与「检索成功但没有命中」是完全不同的结论，因此必须在结果里保留稳定分类，
 * 而不是塌缩成一句「没有知识证据」。</p>
 */
public enum KnowledgeFailure {

    /** 知识向量化未启用（检索用例报 {@code KNOWLEDGE_EMBEDDING_DISABLED}）。 */
    DISABLED,

    /** Embedding 上游不可用（检索用例报 {@code EMBEDDING_PROVIDER_ERROR}）。 */
    EMBEDDING_PROVIDER_UNAVAILABLE,

    /** 重排上游不可用（检索用例报 {@code RERANK_PROVIDER_ERROR}）。 */
    RERANK_PROVIDER_UNAVAILABLE,

    /** 其它内部检索失败（{@code KNOWLEDGE_RETRIEVAL_FAILURE} 或未预期的运行期异常）。 */
    RETRIEVAL_FAILURE
}
