package com.flowdesk.infrastructure.knowledge.persistence.jdbc;

import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentRepository;
import com.flowdesk.application.knowledge.port.out.VersionedKnowledgeDocument;
import com.flowdesk.domain.knowledge.KnowledgeDocument;
import com.flowdesk.domain.knowledge.KnowledgeDocumentId;
import com.flowdesk.domain.knowledge.KnowledgeDocumentStatus;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 基于 Spring JDBC {@link JdbcClient} 的知识文档元数据适配器。
 *
 * <h2>插入</h2>
 * <p>版本固定写 0，单条语句即可完成（上传流程只在文件已经落盘之后做这一次短写入）。</p>
 *
 * <h2>更新（compare-and-set，FD-0009 / FD-0009-R1）</h2>
 * <p>解析状态机依赖 CAS，因此更新不是「先查后改」而是：</p>
 * <ol>
 *   <li>{@code SELECT version, status ... FOR UPDATE} 锁定目标行 —— 不存在则 {@code NOT_FOUND}；</li>
 *   <li>当前版本必须等于 {@code expectedVersion}，否则 {@code VERSION_CONFLICT}（不产生任何写入）；</li>
 *   <li><b>锁定后读到的状态必须允许这一步转换</b>（见下表），否则
 *       {@code KNOWLEDGE_DOCUMENT_NOT_PARSABLE}（同样不产生任何写入）；</li>
 *   <li>{@code UPDATE ... WHERE id = ? AND version = ?} 影响行数必须严格等于 1，并把版本加 1；</li>
 *   <li>在同一事务内重新读取并返回独立聚合。</li>
 * </ol>
 *
 * <h3>允许通过本方法落库的单步转换（FD-0009-R1 收紧）</h3>
 * <table border="1">
 *   <caption>通用 update 的状态矩阵</caption>
 *   <tr><th>锁定后读到的状态</th><th>允许写入的目标状态</th></tr>
 *   <tr><td>{@code UPLOADED}</td><td>{@code PARSING}（领取解析）</td></tr>
 *   <tr><td>{@code PARSE_FAILED}</td><td>{@code PARSING}（修复后重试）</td></tr>
 *   <tr><td>{@code PARSING}</td><td>{@code PARSE_FAILED}（失败补偿）</td></tr>
 *   <tr><td>{@code PARSED}</td><td><b>无</b> —— 只能由
 *       {@code KnowledgeDocumentChunkStore.completeParsing} 的原子端口落库</td></tr>
 * </table>
 * <p>把转换矩阵放在<b>适配器</b>里，是为了让「跳过中间状态」「把两次版本递增合并成一次」
 * 这类绕过状态机的写入在数据库边界上就不可能发生 —— 即使将来有别的调用方拿到这个端口。</p>
 *
 * <p>本类不记录日志，因此不会把标题、文件名或本地路径写进日志。</p>
 */
public final class JdbcKnowledgeDocumentRepository implements KnowledgeDocumentRepository {

    private final JdbcClient jdbcClient;

    private final TransactionOperations transactions;

    /**
     * @param jdbcClient   Spring JDBC 客户端
     * @param transactions 写事务模板；CAS 的多个步骤必须处于同一事务
     */
    public JdbcKnowledgeDocumentRepository(JdbcClient jdbcClient, TransactionOperations transactions) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient 不能为 null");
        this.transactions = Objects.requireNonNull(transactions, "transactions 不能为 null");
    }

    @Override
    public VersionedKnowledgeDocument insert(KnowledgeDocument document) {
        Objects.requireNonNull(document, "document 不能为 null");

        try {
            this.jdbcClient.sql(KnowledgeDocumentSql.INSERT)
                    .param(1, document.id().value())
                    .param(2, document.title().value())
                    .param(3, document.originalFilename().value())
                    .param(4, document.format().name())
                    .param(5, document.mediaType())
                    .param(6, document.sizeBytes())
                    .param(7, document.sha256().value())
                    .param(8, document.contentKey())
                    .param(9, document.status().name())
                    .param(10, toOffsetDateTime(document.createdAt()))
                    .param(11, toOffsetDateTime(document.updatedAt()))
                    .update();
        }
        catch (DuplicateKeyException ex) {
            throw new KnowledgeApplicationException(
                    KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_ALREADY_EXISTS,
                    "文档标识或内容键已存在", ex);
        }
        catch (DataAccessException ex) {
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.METADATA_STORAGE_FAILURE,
                    "元数据写入失败", ex);
        }
        return new VersionedKnowledgeDocument(document, 0L);
    }

    @Override
    public Optional<VersionedKnowledgeDocument> findById(KnowledgeDocumentId documentId) {
        Objects.requireNonNull(documentId, "documentId 不能为 null");

        try {
            return this.jdbcClient.sql(KnowledgeDocumentSql.SELECT_BY_ID)
                    .param(1, documentId.value())
                    .query(KnowledgeDocumentRowMapper::mapRow)
                    .optional();
        }
        catch (KnowledgeApplicationException ex) {
            // RowMapper 已判定「快照不自洽」，不能被下面的通用映射覆盖
            throw ex;
        }
        catch (DataAccessException ex) {
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.METADATA_STORAGE_FAILURE,
                    "元数据读取失败", ex);
        }
    }

    @Override
    public VersionedKnowledgeDocument update(KnowledgeDocument document, long expectedVersion) {
        Objects.requireNonNull(document, "document 不能为 null");

        try {
            return this.transactions.execute(status -> compareAndSet(document, expectedVersion));
        }
        catch (KnowledgeApplicationException ex) {
            // 业务判定（不存在 / 版本冲突 / 快照不自洽）：原样抛出，事务由模板回滚
            throw ex;
        }
        catch (DataAccessException ex) {
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.METADATA_STORAGE_FAILURE,
                    "元数据更新失败", ex);
        }
    }

    private VersionedKnowledgeDocument compareAndSet(KnowledgeDocument document, long expectedVersion) {
        KnowledgeDocumentId documentId = document.id();

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
        if (current.version() != expectedVersion) {
            throw new KnowledgeApplicationException(
                    KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_VERSION_CONFLICT,
                    "文档版本不匹配");
        }
        if (!isAllowedTransition(current.status(), document.status())) {            // 版本相同但转换非法：用现有的稳定状态错误拒绝，不新增公开错误契约、不产生任何写入
            throw new KnowledgeApplicationException(
                    KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_NOT_PARSABLE,
                    "当前状态不允许该状态转换");
        }

        int affected = this.jdbcClient.sql(KnowledgeDocumentSql.CAS_UPDATE_STATUS)
                .param(1, document.status().name())
                .param(2, toOffsetDateTime(document.updatedAt()))
                .param(3, toOffsetDateTime(document.parsedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param(4, toOffsetDateTime(document.parseFailedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param(5, document.parseFailureCode() == null ? null : document.parseFailureCode().name(),
                        Types.VARCHAR)
                .param(6, documentId.value())
                .param(7, expectedVersion)
                .update();
        if (affected != 1) {
            // 行锁已保证不会走到这里；若真发生，说明写入不完整，按版本冲突处理并回滚
            throw new KnowledgeApplicationException(
                    KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_VERSION_CONFLICT, "并发更新冲突");
        }
        return findById(documentId)
                .orElseThrow(() -> new KnowledgeApplicationException(
                        KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_NOT_FOUND, "知识文档不存在"));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * 通用 update 允许的单步状态转换。
     *
     * <p>{@code PARSED} 不在任何「允许写入的目标」里：它只能由
     * {@code completeParsing} 的原子端口在一次事务中连同切片一起落库。</p>
     *
     * @param currentStatus 锁定后读到的当前状态名
     * @param targetStatus  调用方希望写入的状态
     * @return 是否允许
     */
    private static boolean isAllowedTransition(String currentStatus, KnowledgeDocumentStatus targetStatus) {
        return switch (currentStatus) {
            case "UPLOADED", "PARSE_FAILED" -> targetStatus == KnowledgeDocumentStatus.PARSING;
            case "PARSING" -> targetStatus == KnowledgeDocumentStatus.PARSE_FAILED;
            default -> false;
        };
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
