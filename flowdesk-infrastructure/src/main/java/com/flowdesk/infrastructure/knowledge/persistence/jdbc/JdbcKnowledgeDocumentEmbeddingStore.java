package com.flowdesk.infrastructure.knowledge.persistence.jdbc;

import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.application.knowledge.index.DocumentIndexingException;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentEmbeddingStore;
import com.flowdesk.application.knowledge.port.out.VersionedKnowledgeDocument;
import com.flowdesk.domain.knowledge.KnowledgeDocument;
import com.flowdesk.domain.knowledge.KnowledgeDocumentChunkEmbedding;
import com.flowdesk.domain.knowledge.KnowledgeDocumentId;
import com.flowdesk.domain.knowledge.KnowledgeDocumentStatus;
import com.flowdesk.domain.knowledge.KnowledgeIndexFailureCode;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 「完成索引」的 PostgreSQL + pgvector 实现（FD-0010 / FD-0010-R1）。
 *
 * <h2>为什么五步必须在一个事务里</h2>
 * <p>完成索引要同时做到：校验文档仍是 {@code INDEXING} 且版本匹配、校验待写入向量与库中切片
 * <b>完全一致</b>、删除旧向量、批量写入新向量、把文档更新为 {@code INDEXED} 并加版本。
 * 任何一步单独成功、其余失败都会留下自相矛盾的状态：</p>
 * <ul>
 *   <li>「文档是 INDEXED 但向量只写了一半」→ 后续检索召回不完整，且无法从状态看出；</li>
 *   <li>「向量是新的但文档仍在 INDEXING」→ 文档永远领不走，向量却已经存在。</li>
 * </ul>
 * <p>因此这五步共享同一个事务，任一步（含任意一个写批次）抛出异常都整体回滚。</p>
 *
 * <h2>真正的 JDBC 批处理（FD-0010-R1）</h2>
 * <p>FD-0010 的写入是 {@code for (… ) jdbcClient.sql(INSERT).update();} —— 那是 N 次独立的
 * {@code executeUpdate()}，每次都要走一遍「解析 SQL → 绑定 → 执行 → 取结果」，
 * 既不是批处理，也没有「写批次」这个可观测的边界。</p>
 * <p>现在用 {@link JdbcTemplate#batchUpdate(String, java.util.Collection, int,
 * org.springframework.jdbc.core.ParameterizedPreparedStatementSetter)}：
 * 一条准备好的语句 + {@code addBatch()} 累积 + 每 {@code writeBatchSize} 条一次
 * {@code executeBatch()}。批次有界（默认 {@value #DEFAULT_WRITE_BATCH_SIZE} 条），
 * <b>不</b>拼接超长 SQL，所有值仍然参数绑定，向量在绑定前仍逐个做维度与有限性校验。</p>
 * <p>批与批之间<b>没有</b>独立提交：它们和删除旧向量、文档状态 CAS 同属一个事务，
 * 因此「前一批已执行、后一批失败」时，前一批的写入也会被回滚（由集成测试实证）。</p>
 *
 * <h2>失败码契约（FD-0010-R1）</h2>
 * <ul>
 *   <li>写入 / 批次 / 事务提交失败（{@link DataAccessException}、{@link TransactionException}）→
 *       {@link KnowledgeIndexFailureCode#VECTOR_STORAGE_FAILURE}；</li>
 *   <li>切片摘要或数量与库中不一致 → {@link KnowledgeIndexFailureCode#CHUNK_DATA_INVALID}
 *       （此时<b>不写入任何向量</b>）；</li>
 *   <li>版本冲突 / 状态不允许索引 → 保持 {@link KnowledgeApplicationException}，
 *       由用例层映射为 412 / 409，<b>不</b>降级成写入失败。</li>
 * </ul>
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

    /** 默认数据库写批次大小：一条 prepared statement 最多累积这么多行。 */
    public static final int DEFAULT_WRITE_BATCH_SIZE = 100;

    /** 写批次上限：批次越大，单次 {@code executeBatch()} 的失败重试代价与内存占用越高。 */
    public static final int MAX_WRITE_BATCH_SIZE = 1000;

    private final JdbcClient jdbcClient;

    private final JdbcTemplate jdbcTemplate;

    private final TransactionOperations transactions;

    private final JdbcKnowledgeDocumentRepository documentRepository;

    private final int writeBatchSize;

    /**
     * @param jdbcClient         Spring JDBC 客户端
     * @param dataSource         数据源（用于构造真正的批处理模板）
     * @param transactions       写事务模板
     * @param documentRepository 文档仓储（同一事务内复用，用于最终重新读取）
     */
    public JdbcKnowledgeDocumentEmbeddingStore(JdbcClient jdbcClient, DataSource dataSource,
            TransactionOperations transactions, JdbcKnowledgeDocumentRepository documentRepository) {

        this(jdbcClient, dataSource, transactions, documentRepository, DEFAULT_WRITE_BATCH_SIZE);
    }

    /**
     * @param jdbcClient         Spring JDBC 客户端
     * @param dataSource         数据源（用于构造真正的批处理模板）
     * @param transactions       写事务模板
     * @param documentRepository 文档仓储（同一事务内复用，用于最终重新读取）
     * @param writeBatchSize     单批最大行数，必须在 {@code 1..}{@value #MAX_WRITE_BATCH_SIZE} 之间
     */
    public JdbcKnowledgeDocumentEmbeddingStore(JdbcClient jdbcClient, DataSource dataSource,
            TransactionOperations transactions, JdbcKnowledgeDocumentRepository documentRepository,
            int writeBatchSize) {

        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient 不能为 null");
        this.jdbcTemplate = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource 不能为 null"));
        this.transactions = Objects.requireNonNull(transactions, "transactions 不能为 null");
        this.documentRepository = Objects.requireNonNull(documentRepository, "documentRepository 不能为 null");
        if (writeBatchSize < 1 || writeBatchSize > MAX_WRITE_BATCH_SIZE) {
            throw new IllegalArgumentException("writeBatchSize 必须在 1.." + MAX_WRITE_BATCH_SIZE + " 之间");
        }
        this.writeBatchSize = writeBatchSize;
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
            // 版本冲突 / 状态不允许索引：语义保持原样，绝不降级成「写入失败」
            throw ex;
        }
        catch (DataAccessException | TransactionException ex) {
            // 写入、批处理与事务提交阶段的一切数据库失败：带稳定失败码抛出，
            // 让 HTTP 层能返回 failureCode=VECTOR_STORAGE_FAILURE（而不是一个没有失败码的 500）。
            // 细节（SQL、连接串、驱动信息）只留在 cause，绝不进入消息或响应。
            throw new DocumentIndexingException(KnowledgeIndexFailureCode.VECTOR_STORAGE_FAILURE,
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

        // ③ 真正的 JDBC 批处理写入：每批 writeBatchSize 条一次 executeBatch()
        writeEmbeddingsInBatches(indexedDocument, embeddings, documentId);

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
     * 分批写入全部向量。
     *
     * <p>{@code created_at} 用文档的 {@code indexedAt}：时间来自应用层的时间端口，
     * 适配器自己不看系统时钟，因此同一事务里「文档完成时间」与「向量创建时间」必然一致且可预测。</p>
     *
     * @param indexedDocument 已标记完成的文档聚合
     * @param embeddings      待写入向量（序号从 0 连续递增）
     * @param documentId      文档标识
     */
    private void writeEmbeddingsInBatches(KnowledgeDocument indexedDocument,
            List<KnowledgeDocumentChunkEmbedding> embeddings, KnowledgeDocumentId documentId) {

        java.time.OffsetDateTime createdAt = KnowledgeDocumentSql.toOffsetDateTime(indexedDocument.indexedAt());
        // Spring 6.2 起 batchUpdate 的「集合 + 批大小」重载：一条 prepared statement、
        // 逐行 addBatch()、每 writeBatchSize 行一次 executeBatch()，不会拼接超长 SQL。
        this.jdbcTemplate.batchUpdate(KnowledgeEmbeddingSql.INSERT, embeddings, this.writeBatchSize,
                (PreparedStatement statement, KnowledgeDocumentChunkEmbedding embedding) -> {
                    statement.setObject(1, documentId.value());
                    statement.setInt(2, embedding.chunkIndex());
                    statement.setString(3, embedding.chunkSha256().value());
                    // 向量的最后一道闸门：维度与有限性校验发生在「数值进入 SQL 之前」
                    statement.setString(4, toVectorLiteral(embedding));
                    statement.setString(5, embedding.descriptor().provider());
                    statement.setString(6, embedding.descriptor().model());
                    statement.setInt(7, embedding.dimensions());
                    statement.setObject(8, createdAt);
                });
    }

    /**
     * 向量与库中切片必须一一对应：数量相同、序号相同、摘要相同。
     *
     * <p>摘要不一致意味着片段内容在「生成向量」与「写入向量」之间被换过：写入它等于把
     * 「旧文本的向量」挂到「新切片」上。这类数据不自洽按 {@code CHUNK_DATA_INVALID} 上报，
     * 与「向量服务返回了畸形数据」区分开。</p>
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
            throw chunkDataInvalid("待写入向量数量与数据库切片数量不一致");
        }
        for (KnowledgeDocumentChunkEmbedding embedding : embeddings) {
            String digest = chunkDigests.get(embedding.chunkIndex());
            if (digest == null || !digest.equals(embedding.chunkSha256().value())) {
                throw chunkDataInvalid("待写入向量与数据库切片的摘要不一致");
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

    private static DocumentIndexingException chunkDataInvalid(String message) {
        return new DocumentIndexingException(KnowledgeIndexFailureCode.CHUNK_DATA_INVALID, message);
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
