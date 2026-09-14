package com.flowdesk.infrastructure.knowledge.persistence.jdbc;

import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentEmbeddingStore;
import com.flowdesk.application.knowledge.port.out.VersionedKnowledgeDocument;
import com.flowdesk.domain.knowledge.KnowledgeDocument;
import com.flowdesk.domain.knowledge.KnowledgeDocumentChunkEmbedding;
import com.flowdesk.domain.knowledge.KnowledgeDocumentId;
import com.flowdesk.domain.knowledge.KnowledgeDocumentStatus;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 「完成索引」的 PostgreSQL + pgvector 实现（FD-0010）。
 *
 * <h2>为什么五步必须在一个事务里</h2>
 * <p>完成索引要同时做到：校验文档仍是 {@code INDEXING} 且版本匹配、校验待写入向量与库中切片
 * <b>完全一致</b>、删除旧向量、批量写入新向量、把文档更新为 {@code INDEXED} 并加版本。
 * 任何一步单独成功、其余失败都会留下自相矛盾的状态：</p>
 * <ul>
 *   <li>「文档是 INDEXED 但向量只写了一半」→ 后续检索召回不完整，且无法从状态看出；</li>
 *   <li>「向量是新的但文档仍在 INDEXING」→ 文档永远领不走，向量却已经存在。</li>
 * </ul>
 * <p>因此这五步共享同一个事务，任一步抛出异常都整体回滚。</p>
 *
 * <h2>为什么不用自动建表的通用 vector_store</h2>
 * <p>本表有复合外键指向 {@code knowledge_document_chunks(document_id, chunk_index)}：
 * 向量<b>不可能</b>存在没有对应切片的行，删除切片时向量随之级联删除。这是通用
 * {@code vector_store}（只有 id/content/metadata/embedding）无法表达的约束。见 ADR 0007。</p>
 *
 * <h2>不做任何纠错</h2>
 * <p>向量数量不足、{@link KnowledgeDocumentChunkEmbedding#chunkIndex()} 断号/错序、
 * 摘要与库中切片不一致、维度或数值非法 —— 全部<b>拒绝</b>并整体回滚。
 * 「按位置重排」或「跳过不匹配的行」会让库里的向量与切片静默错配，
 * 检索阶段会返回语义完全不相干的片段，而这类错误极难被发现。</p>
 */
public final class JdbcKnowledgeDocumentEmbeddingStore implements KnowledgeDocumentEmbeddingStore {

    private final JdbcClient jdbcClient;

    private final TransactionOperations transactions;

    private final JdbcKnowledgeDocumentRepository documentRepository;

    /**
     * @param jdbcClient         Spring JDBC 客户端
     * @param transactions       写事务模板
     * @param documentRepository 文档仓储（同一事务内复用，用于最终重新读取）
     */
    public JdbcKnowledgeDocumentEmbeddingStore(JdbcClient jdbcClient, TransactionOperations transactions,
            JdbcKnowledgeDocumentRepository documentRepository) {

        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient 不能为 null");
        this.transactions = Objects.requireNonNull(transactions, "transactions 不能为 null");
        this.documentRepository = Objects.requireNonNull(documentRepository, "documentRepository 不能为 null");
    }

    @Override
    public VersionedKnowledgeDocument completeIndexing(KnowledgeDocument indexedDocument, long expectedVersion,
            List<KnowledgeDocumentChunkEmbedding> embeddings) {

        Objects.requireNonNull(indexedDocument, "indexedDocument 不能为 null");
        Objects.requireNonNull(embeddings, "embeddings 不能为 null");

        // 防御性校验：全部发生在开启事务与执行任何 SQL 之前
        requireValidEmbeddings(indexedDocument, embeddings);

        KnowledgeDocumentId documentId = indexedDocument.id();
        try {
            return this.transactions.execute(
                    status -> completeWithinTransaction(indexedDocument, expectedVersion, embeddings, documentId));
        }
        catch (KnowledgeApplicationException ex) {
            throw ex;
        }
        catch (DataAccessException ex) {
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.METADATA_STORAGE_FAILURE,
                    "向量写入失败", ex);
        }
    }

    /**
     * 入参防线：状态必须是 {@code INDEXED}（即已调用 {@code markIndexed}）、向量非空、
     * 归属正确、序号从 0 严格连续递增、描述符一致。
     *
     * @param indexedDocument 已标记完成的文档聚合
     * @param embeddings      待写入向量
     */
    private static void requireValidEmbeddings(KnowledgeDocument indexedDocument,
            List<KnowledgeDocumentChunkEmbedding> embeddings) {

        if (indexedDocument.status() != KnowledgeDocumentStatus.INDEXED) {
            throw internalError("完成索引只接受 INDEXED 状态的文档");
        }
        if (embeddings.isEmpty()) {
            throw internalError("完成索引不接受空向量列表");
        }
        for (int index = 0; index < embeddings.size(); index++) {
            KnowledgeDocumentChunkEmbedding embedding = embeddings.get(index);
            if (embedding == null) {
                throw internalError("向量列表中不能包含 null");
            }
            if (!indexedDocument.id().equals(embedding.documentId())) {
                throw internalError("向量归属的文档与目标文档不一致");
            }
            if (embedding.chunkIndex() != index) {
                throw internalError("向量序号必须从 0 开始严格连续递增");
            }
            if (!embedding.descriptor().equals(indexedDocument.embedding())) {
                throw internalError("向量描述符与文档记录的模型不一致");
            }
        }
    }

    private VersionedKnowledgeDocument completeWithinTransaction(KnowledgeDocument indexedDocument,
            long expectedVersion, List<KnowledgeDocumentChunkEmbedding> embeddings,
            KnowledgeDocumentId documentId) {

        CurrentRow current = lockDocument(documentId);
        if (current.version() != expectedVersion) {
            throw new KnowledgeApplicationException(
                    KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_VERSION_CONFLICT,
                    "文档版本不匹配");
        }
        if (!KnowledgeDocumentStatus.INDEXING.name().equals(current.status())) {
            throw new KnowledgeApplicationException(
                    KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_NOT_INDEXABLE,
                    "文档不在索引中状态");
        }

        // ① 二次证明：库中的切片与本次生成向量时读到的切片完全一致
        requireChunksMatchEmbeddings(documentId, embeddings);

        // ② 删除该文档可能存在的旧向量（重新索引时不留残余）
        this.jdbcClient.sql(KnowledgeEmbeddingSql.DELETE_BY_DOCUMENT)
                .param(1, documentId.value())
                .update();

        // ③ 批量写入全部向量（循环批量，避免单条语句长度随切片数线性增长）。
        //    created_at 用文档的 indexedAt：时间来自应用层的时间端口，适配器自己不看系统时钟，
        //    因此同一事务里「文档完成时间」与「向量创建时间」必然一致且可预测。
        java.time.OffsetDateTime createdAt = KnowledgeDocumentSql.toOffsetDateTime(
                indexedDocument.indexedAt());
        for (KnowledgeDocumentChunkEmbedding embedding : embeddings) {
            this.jdbcClient.sql(KnowledgeEmbeddingSql.INSERT)
                    .param(1, documentId.value())
                    .param(2, embedding.chunkIndex())
                    .param(3, embedding.chunkSha256().value())
                    .param(4, toVectorLiteral(embedding))
                    .param(5, embedding.descriptor().provider())
                    .param(6, embedding.descriptor().model())
                    .param(7, embedding.dimensions())
                    .param(8, createdAt)
                    .update();
        }

        // ④ 更新文档状态（CAS：id + version 同时匹配），与向量写入同属一个事务
        int affected = KnowledgeDocumentSql.bindLifecycleFields(
                this.jdbcClient.sql(KnowledgeDocumentSql.CAS_UPDATE_STATUS), indexedDocument)
                .param(13, documentId.value())
                .param(14, expectedVersion)
                .update();
        if (affected != 1) {
            throw new KnowledgeApplicationException(
                    KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_VERSION_CONFLICT,
                    "并发更新冲突");
        }

        // ⑤ 在同一事务内重新读取，保证返回的版本与库中一致
        return this.documentRepository.findById(documentId)
                .orElseThrow(() -> new KnowledgeApplicationException(
                        KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_NOT_FOUND, "知识文档不存在"));
    }

    /**
     * 向量与库中切片必须一一对应：数量相同、序号相同、摘要相同。
     *
     * @param documentId 文档标识
     * @param embeddings 待写入向量
     */
    private void requireChunksMatchEmbeddings(KnowledgeDocumentId documentId,
            List<KnowledgeDocumentChunkEmbedding> embeddings) {

        Map<Integer, String> chunkDigests = new TreeMap<>();
        for (ChunkProof proof : this.jdbcClient
                .sql(KnowledgeEmbeddingSql.SELECT_CHUNK_PROOFS)
                .param(1, documentId.value())
                .query((resultSet, rowNum) -> new ChunkProof(resultSet.getInt("chunk_index"),
                        resultSet.getString("sha256")))
                .list()) {
            chunkDigests.put(proof.chunkIndex(), proof.sha256().strip());
        }

        if (chunkDigests.size() != embeddings.size()) {
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.KNOWLEDGE_INTERNAL_ERROR,
                    "待写入向量数量与数据库切片数量不一致");
        }
        for (KnowledgeDocumentChunkEmbedding embedding : embeddings) {
            String digest = chunkDigests.get(embedding.chunkIndex());
            if (digest == null || !digest.equals(embedding.chunkSha256().value())) {
                // 切片在向量生成之后被换过：写入它等于把「旧文本的向量」挂到「新切片」上
                throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.KNOWLEDGE_INTERNAL_ERROR,
                        "待写入向量与数据库切片的摘要不一致");
            }
        }
    }

    /**
     * 把向量序列化为 pgvector 字面量（{@code [0.1,0.2,...]}）。
     *
     * <p>序列化前再次校验有限性与维度：这是「数值进入 SQL 之前」的最后一道闸门，
     * {@code NaN}/{@code Infinity} 会让 pgvector 报类型错误或写入无意义的值。</p>
     *
     * @param embedding 向量值对象
     * @return pgvector 字面量
     */
    private static String toVectorLiteral(KnowledgeDocumentChunkEmbedding embedding) {
        float[] vector = embedding.vector();
        if (vector.length != embedding.dimensions()) {
            throw internalError("向量维度与描述符不一致");
        }
        StringBuilder literal = new StringBuilder(vector.length * 12 + 2);
        literal.append('[');
        for (int index = 0; index < vector.length; index++) {
            float value = vector[index];
            if (Float.isNaN(value) || Float.isInfinite(value)) {
                throw internalError("向量必须全部是有限数值");
            }
            if (index > 0) {
                literal.append(',');
            }
            literal.append(Float.toString(value));
        }
        return literal.append(']').toString();
    }

    private CurrentRow lockDocument(KnowledgeDocumentId documentId) {
        CurrentRow current = this.jdbcClient.sql(KnowledgeDocumentSql.SELECT_VERSION_FOR_UPDATE)
                .param(1, documentId.value())
                .query((resultSet, rowNum) -> new CurrentRow(resultSet.getLong("version"),
                        resultSet.getString("status")))
                .optional()
                .orElse(null);
        if (current == null) {
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_NOT_FOUND,
                    "知识文档不存在");
        }
        return current;
    }

    private static KnowledgeApplicationException internalError(String message) {
        return new KnowledgeApplicationException(KnowledgeApplicationErrorCode.KNOWLEDGE_INTERNAL_ERROR, message);
    }

    /**
     * 行锁读到的当前版本与状态。
     *
     * @param version 当前版本
     * @param status  当前状态
     */
    private record CurrentRow(long version, String status) {
    }

    /**
     * 库中切片的「序号 + 摘要」证明。
     *
     * @param chunkIndex 切片序号
     * @param sha256     切片内容摘要
     */
    private record ChunkProof(int chunkIndex, String sha256) {
    }
}
