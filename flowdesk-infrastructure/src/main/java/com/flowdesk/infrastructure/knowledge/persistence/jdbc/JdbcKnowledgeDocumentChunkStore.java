package com.flowdesk.infrastructure.knowledge.persistence.jdbc;

import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentChunkStore;
import com.flowdesk.application.knowledge.port.out.VersionedKnowledgeDocument;
import com.flowdesk.domain.knowledge.KnowledgeDocument;
import com.flowdesk.domain.knowledge.KnowledgeDocumentChunk;
import com.flowdesk.domain.knowledge.KnowledgeDocumentId;
import com.flowdesk.domain.knowledge.KnowledgeDocumentStatus;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 切片与「完成解析」的 JDBC 实现（FD-0009）。
 *
 * <h2>为什么四步必须在一个事务里</h2>
 * <p>完成解析要同时做到：校验文档仍是 {@code PARSING} 且版本匹配、删除旧切片、批量插入新切片、
 * 把文档更新为 {@code PARSED} 并加版本。任何一步单独成功、其余失败，都会留下自相矛盾的状态
 * （例如「文档已 PARSED 但切片只有一半」）。因此这四步共享同一个事务，
 * 任一步抛出异常都会整体回滚 —— 端口契约要求的原子性由此落实。</p>
 *
 * <h2>为什么批量插入不用单条多值 INSERT</h2>
 * <p>单条多值 INSERT 的语句长度会随切片数线性增长（切片上限数千），
 * 既可能撞上数据库的参数上限，也让 SQL 拼装变复杂。这里用批处理风格的循环插入，
 * 事务保证整体性；切片数上限由配置约束，不会无限增长。</p>
 *
 * <h2>入参防线（FD-0009-R1）</h2>
 * <p>端口在开启事务之前就会拒绝：非 {@code PARSED} 的文档、空切片列表、列表中的 {@code null}、
 * 归属错误（{@code chunk.documentId} 与文档不一致）以及不连续/乱序的 {@code chunkIndex}。
 * 尤其是「归属错误」：插入时使用的是文档自己的标识，若不做校验，一份属于别的文档的切片
 * 会被<b>静默改写</b>成当前文档的切片 —— 数据看起来正常，来源却已经错了。</p>
 */
public final class JdbcKnowledgeDocumentChunkStore implements KnowledgeDocumentChunkStore {

    private final JdbcClient jdbcClient;

    private final TransactionOperations transactions;

    private final JdbcKnowledgeDocumentRepository documentRepository;

    /**
     * @param jdbcClient         Spring JDBC 客户端
     * @param transactions        写事务模板
     * @param documentRepository  文档仓储（同一事务内复用，用于最终重新读取）
     */
    public JdbcKnowledgeDocumentChunkStore(JdbcClient jdbcClient, TransactionOperations transactions,
            JdbcKnowledgeDocumentRepository documentRepository) {

        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient 不能为 null");
        this.transactions = Objects.requireNonNull(transactions, "transactions 不能为 null");
        this.documentRepository = Objects.requireNonNull(documentRepository, "documentRepository 不能为 null");
    }

    @Override
    public VersionedKnowledgeDocument completeParsing(KnowledgeDocument parsedDocument, long expectedVersion,
            List<KnowledgeDocumentChunk> chunks) {

        Objects.requireNonNull(parsedDocument, "parsedDocument 不能为 null");
        Objects.requireNonNull(chunks, "chunks 不能为 null");

        // 防御性校验：全部发生在开启事务与执行任何 SQL 之前（FD-0009-R1）。
        // 端口是应用层与数据库之间唯一的入口，因此「不该出现在这里的东西」必须在这里被挡住，
        // 而不是靠调用方自觉 —— 静默纠正（忽略错误归属的切片、按位置重排序号）会让数据
        // 看起来正确、实际上已经错了。
        requireParsedDocumentAndChunks(parsedDocument, chunks);

        KnowledgeDocumentId documentId = parsedDocument.id();

        try {
            return this.transactions.execute(status -> completeWithinTransaction(parsedDocument, expectedVersion,
                    chunks, documentId));
        }
        catch (KnowledgeApplicationException ex) {
            // 业务判定（不存在 / 版本冲突 / 不在解析中）：原样抛出，事务由模板回滚
            throw ex;
        }
        catch (DataAccessException ex) {
            // 任一写入失败都整体回滚，并且不把 Spring/JDBC 异常泄漏到应用层
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.METADATA_STORAGE_FAILURE,
                    "解析结果写入失败", ex);
        }
    }

    /**
     * 完成解析前的防御性校验。
     *
     * <p>拒绝的情形：文档不是 {@code PARSED}、切片列表为空、包含 {@code null}、
     * 切片归属的文档与目标文档不一致、序号不是从 0 开始严格连续递增。
     * 非法输入以既有的稳定内部错误码 {@code KNOWLEDGE_INTERNAL_ERROR} 拒绝，
     * 不静默纠正，也不产生任何数据库写入。</p>
     *
     * @param parsedDocument 待落库的文档聚合
     * @param chunks         待写入的切片（按序号升序）
     */
    private static void requireParsedDocumentAndChunks(KnowledgeDocument parsedDocument,
            List<KnowledgeDocumentChunk> chunks) {

        if (parsedDocument.status() != KnowledgeDocumentStatus.PARSED) {
            throw internalError("完成解析只接受 PARSED 状态的文档");
        }
        if (chunks.isEmpty()) {
            throw internalError("完成解析不接受空切片列表");
        }
        for (int index = 0; index < chunks.size(); index++) {
            KnowledgeDocumentChunk chunk = chunks.get(index);
            if (chunk == null) {
                throw internalError("切片列表中不能包含 null");
            }
            if (!parsedDocument.id().equals(chunk.documentId())) {
                throw internalError("切片归属的文档与目标文档不一致");
            }
            if (chunk.chunkIndex() != index) {
                throw internalError("切片序号必须从 0 开始严格连续递增");
            }
        }
    }

    private static KnowledgeApplicationException internalError(String message) {
        return new KnowledgeApplicationException(KnowledgeApplicationErrorCode.KNOWLEDGE_INTERNAL_ERROR, message);
    }

    private VersionedKnowledgeDocument completeWithinTransaction(KnowledgeDocument parsedDocument,
            long expectedVersion, List<KnowledgeDocumentChunk> chunks, KnowledgeDocumentId documentId) {

        CurrentRow current = lockDocument(documentId);
        if (current.version() != expectedVersion) {
            throw new KnowledgeApplicationException(
                    KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_VERSION_CONFLICT,
                    "文档版本不匹配");
        }
        if (!KnowledgeDocumentStatus.PARSING.name().equals(current.status())) {
            throw new KnowledgeApplicationException(
                    KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_NOT_PARSABLE,
                    "文档不在解析中状态");
        }

        // ① 删除该文档可能存在的旧切片（重复解析同一文档时不留残余）
        this.jdbcClient.sql(KnowledgeChunkSql.DELETE_BY_DOCUMENT)
                .param(1, documentId.value())
                .update();

        // ② 批量插入全部新切片
        for (KnowledgeDocumentChunk chunk : chunks) {
            this.jdbcClient.sql(KnowledgeChunkSql.INSERT)
                    .param(1, documentId.value())
                    .param(2, chunk.chunkIndex())
                    .param(3, chunk.content())
                    .param(4, chunk.codePointCount())
                    .param(5, chunk.sha256().value())
                    .param(6, toOffsetDateTime(chunk.createdAt()))
                    .update();
        }

        // ③ 更新文档状态（CAS：id + version 同时匹配）
        int affected = this.jdbcClient.sql(KnowledgeDocumentSql.CAS_UPDATE_STATUS)
                .param(1, parsedDocument.status().name())
                .param(2, toOffsetDateTime(parsedDocument.updatedAt()))
                .param(3, toOffsetDateTime(parsedDocument.parsedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param(4, toOffsetDateTime(parsedDocument.parseFailedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param(5, parsedDocument.parseFailureCode() == null
                        ? null : parsedDocument.parseFailureCode().name(), Types.VARCHAR)
                .param(6, documentId.value())
                .param(7, expectedVersion)
                .update();
        if (affected != 1) {
            throw new KnowledgeApplicationException(
                    KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_VERSION_CONFLICT,
                    "并发更新冲突");
        }

        // ④ 在同一事务内重新读取，保证返回的版本与库中一致
        return this.documentRepository.findById(documentId)
                .orElseThrow(() -> new KnowledgeApplicationException(
                        KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_NOT_FOUND, "知识文档不存在"));
    }

    @Override
    public long countChunks(KnowledgeDocumentId documentId) {
        Objects.requireNonNull(documentId, "documentId 不能为 null");
        try {
            Long count = this.jdbcClient.sql(KnowledgeChunkSql.COUNT_BY_DOCUMENT)
                    .param(1, documentId.value())
                    .query(Long.class)
                    .single();
            return count == null ? 0L : count;
        }
        catch (DataAccessException ex) {
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.METADATA_STORAGE_FAILURE,
                    "切片数量读取失败", ex);
        }
    }

    @Override
    public List<KnowledgeDocumentChunk> findChunks(KnowledgeDocumentId documentId, int offset, int limit) {
        Objects.requireNonNull(documentId, "documentId 不能为 null");
        if (offset < 0 || limit <= 0) {
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.INVALID_QUERY,
                    "分页参数不合法");
        }
        try {
            return this.jdbcClient.sql(KnowledgeChunkSql.SELECT_PAGE)
                    .param(1, documentId.value())
                    .param(2, limit)
                    .param(3, (long) offset)
                    .query(KnowledgeChunkRowMapper::mapRow)
                    .list();
        }
        catch (DataAccessException ex) {
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.METADATA_STORAGE_FAILURE,
                    "切片读取失败", ex);
        }
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

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * 行锁读到的当前版本与状态。
     *
     * @param version 当前版本
     * @param status  当前状态
     */
    private record CurrentRow(long version, String status) {
    }
}
