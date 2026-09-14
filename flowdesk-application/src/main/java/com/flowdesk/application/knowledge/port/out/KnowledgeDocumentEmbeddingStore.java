package com.flowdesk.application.knowledge.port.out;

import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.domain.knowledge.KnowledgeDocument;
import com.flowdesk.domain.knowledge.KnowledgeDocumentChunkEmbedding;
import java.util.List;

/**
 * 切片向量的原子写入端口。
 *
 * <h2>为什么「完成索引」是一个原子端口方法</h2>
 * <p>完成阶段要做五件事：校验文档仍是 {@code INDEXING}、校验版本、校验待写入向量与库中切片
 * <b>完全一致</b>、删除旧向量并写入新向量、把文档更新为 {@code INDEXED}（版本加 1）。
 * 这五步必须<b>整体成功或整体回滚</b> —— 否则会出现「文档是 INDEXED 但向量只写了一半」
 * 或「向量是新的但文档仍停在 INDEXING」这类自相矛盾的状态，而这两种状态都会让后续检索
 * 拿到不完整或错配的向量。</p>
 *
 * <p>把它定义成<b>一个端口方法</b>，事务边界就属于适配器（基础设施）而不是应用层：
 * 应用层继续保持「不认识事务」的纯粹性，而原子性由端口契约强制。</p>
 *
 * <p>向量生成<b>不</b>在这个事务里：模型调用耗时且与数据库无关（见 ADR 0007）。</p>
 */
public interface KnowledgeDocumentEmbeddingStore {

    /**
     * 原子完成索引：替换向量并把文档标记为 {@code INDEXED}（单个数据库事务）。
     *
     * @param indexedDocument 已调用 {@code markIndexed} 的文档聚合
     * @param expectedVersion <b>领取索引后的版本</b>（即 {@code repository.update} 返回的版本）；
     *                        实现必须在事务内校验「行仍是 {@code INDEXING} 且版本等于该值」
     * @param embeddings      本次生成的<b>全部</b>向量，按 {@code chunkIndex} 升序，数量必须与库中切片一致
     * @return 更新后的文档及其新版本（{@code expectedVersion + 1}）
     * @throws KnowledgeApplicationException 版本不匹配、文档不在 {@code INDEXING} 状态、
     *                                       向量与切片不一致，或任一写入失败（整体回滚）
     */
    VersionedKnowledgeDocument completeIndexing(KnowledgeDocument indexedDocument, long expectedVersion,
            List<KnowledgeDocumentChunkEmbedding> embeddings);
}
