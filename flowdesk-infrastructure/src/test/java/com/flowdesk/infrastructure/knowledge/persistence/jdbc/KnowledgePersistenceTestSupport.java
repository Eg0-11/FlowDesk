package com.flowdesk.infrastructure.knowledge.persistence.jdbc;

import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.domain.knowledge.DocumentFormat;
import com.flowdesk.domain.knowledge.DocumentTitle;
import com.flowdesk.domain.knowledge.KnowledgeDocument;
import com.flowdesk.domain.knowledge.KnowledgeDocumentId;
import com.flowdesk.domain.knowledge.OriginalFilename;
import com.flowdesk.domain.knowledge.Sha256Digest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 知识文档持久化测试的共享装配（FD-0009）：真实 H2（PostgreSQL 兼容模式）+ 真实 Flyway 迁移。
 *
 * <p>每个测试类用一个独立的内存库名，避免并行执行时互相看到对方的数据。</p>
 */
final class KnowledgePersistenceTestSupport {

    static final Instant UPLOADED_AT = Instant.parse("2026-05-01T10:00:00Z");

    private KnowledgePersistenceTestSupport() {
    }

    /**
     * 建好库、跑完迁移，并返回可直接使用的仓储与切片存储。
     *
     * @param databaseName 内存库名（每个测试类唯一）
     * @return 装配结果
     */
    static Fixture migrate(String databaseName) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + databaseName
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        // 迁移用真实数据源；此后所有 JDBC 都经由语句拦截层，便于观测「是否下发了 SQL」
        StatementInterceptingDataSource intercepting = new StatementInterceptingDataSource(dataSource);
        JdbcClient jdbcClient = JdbcClient.create(intercepting);
        TransactionTemplate transactions = new TransactionTemplate(
                new DataSourceTransactionManager(intercepting));
        JdbcKnowledgeDocumentRepository repository = new JdbcKnowledgeDocumentRepository(jdbcClient, transactions);
        JdbcKnowledgeDocumentChunkStore chunkStore = new JdbcKnowledgeDocumentChunkStore(jdbcClient, transactions,
                repository);
        JdbcKnowledgeDocumentEmbeddingStore embeddingStore = new JdbcKnowledgeDocumentEmbeddingStore(jdbcClient,
                intercepting, transactions, repository);

        return new Fixture(jdbcClient, transactions, repository, chunkStore, embeddingStore, intercepting);
    }

    /**
     * 与 {@link #migrate(String)} 相同，但把数据库写批次收窄到 {@code writeBatchSize}，
     * 用于制造「前一批已执行、后一批失败」的场景。
     *
     * @param databaseName   内存库名（每个测试类唯一）
     * @param writeBatchSize 单批最大行数
     * @return 装配结果
     */
    static Fixture migrateWithWriteBatchSize(String databaseName, int writeBatchSize) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + databaseName
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        StatementInterceptingDataSource intercepting = new StatementInterceptingDataSource(dataSource);
        JdbcClient jdbcClient = JdbcClient.create(intercepting);
        TransactionTemplate transactions = new TransactionTemplate(
                new DataSourceTransactionManager(intercepting));
        JdbcKnowledgeDocumentRepository repository = new JdbcKnowledgeDocumentRepository(jdbcClient, transactions);
        JdbcKnowledgeDocumentChunkStore chunkStore = new JdbcKnowledgeDocumentChunkStore(jdbcClient, transactions,
                repository);
        JdbcKnowledgeDocumentEmbeddingStore embeddingStore = new JdbcKnowledgeDocumentEmbeddingStore(jdbcClient,
                intercepting, transactions, repository, writeBatchSize);

        return new Fixture(jdbcClient, transactions, repository, chunkStore, embeddingStore, intercepting);
    }

    /**
     * 新建一个处于 {@code UPLOADED} 的文档并入库。
     *
     * @param repository 仓储
     * @param contentKey 内容键
     * @return 已入库的文档（版本 0）
     */
    static KnowledgeDocument insertUploaded(JdbcKnowledgeDocumentRepository repository, String contentKey) {
        return insertUploaded(repository, randomId(), contentKey);
    }

    static KnowledgeDocument insertUploaded(JdbcKnowledgeDocumentRepository repository, KnowledgeDocumentId id,
            String contentKey) {

        KnowledgeDocument document = KnowledgeDocument.create(id, DocumentTitle.of("季度运维报告"),
                OriginalFilename.of("report.txt"), DocumentFormat.TEXT, "text/plain", 64L,
                Sha256Digest.of(sha256Of(contentKey)), contentKey, UPLOADED_AT);
        repository.insert(document);
        return document;
    }

    /**
     * 领取解析：{@code markParsing} + CAS 更新，返回领取后的版本。
     *
     * @param repository 仓储
     * @param document   文档（{@code UPLOADED} 或 {@code PARSE_FAILED}）
     * @param version    当前版本
     * @return 领取后的版本（{@code version + 1}）
     */
    static long claim(JdbcKnowledgeDocumentRepository repository, KnowledgeDocument document, long version) {
        return claim(repository, document, version, UPLOADED_AT.plusSeconds(1));
    }

    /**
     * 领取解析（可指定领取时间，用于「更新时间已经被推进过」的场景）。
     *
     * @param repository 仓储
     * @param document   文档
     * @param version    当前版本
     * @param claimedAt  领取时间，不得早于文档的 updatedAt
     * @return 领取后的版本
     */
    static long claim(JdbcKnowledgeDocumentRepository repository, KnowledgeDocument document, long version,
            Instant claimedAt) {

        document.markParsing(claimedAt);
        return repository.update(document, version).version();
    }

    static KnowledgeDocumentId randomId() {
        return KnowledgeDocumentId.of(UUID.randomUUID());
    }

    static String sha256Of(String seed) {
        return com.flowdesk.infrastructure.knowledge.KnowledgeTestContent
                .sha256Hex(seed.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 断言应用层错误码。
     */
    static void assertApplicationError(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable,
            KnowledgeApplicationErrorCode expected) {

        org.assertj.core.api.Assertions.assertThatThrownBy(callable)
                .isInstanceOf(KnowledgeApplicationException.class)
                .extracting(thrown -> ((KnowledgeApplicationException) thrown).errorCode())
                .isEqualTo(expected);
    }

    /**
     * 装配好的测试夹具。
     *
     * @param jdbcClient  JDBC 客户端
     * @param transactions 事务模板
     * @param repository   元数据仓储
     * @param chunkStore   切片存储
     * @param embeddingStore 切片向量存储（FD-0010）
     * @param statements   语句拦截层（用于断言「零写入」与定点制造写入失败）
     */
    record Fixture(JdbcClient jdbcClient, TransactionTemplate transactions,
            JdbcKnowledgeDocumentRepository repository, JdbcKnowledgeDocumentChunkStore chunkStore,
            JdbcKnowledgeDocumentEmbeddingStore embeddingStore,
            StatementInterceptingDataSource statements) {

        /** @return 自上次复位以来下发的语句数 */
        int statementsExecuted() {
            return this.statements.statementsExecuted();
        }

        /** 复位语句计数（在「被测操作」之前调用）。 */
        void resetStatements() {
            this.statements.resetStatements();
        }

        /**
         * 让第 N 条语句失败。
         *
         * @param statementNumber 从 1 开始；0 表示关闭
         */
        void failOnStatement(int statementNumber) {
            this.statements.failOnStatement(statementNumber);
        }

        /**
         * 让第 N 次 {@code executeBatch()} 失败（此前的批次已经真实下发）。
         *
         * @param batchNumber 从 1 开始；0 表示关闭
         */
        void failOnBatchExecution(int batchNumber) {
            this.statements.failOnBatchExecution(batchNumber);
        }

        /**
         * 让 SQL 中包含指定片段的语句失败（与序号无关的语义化注入）。
         *
         * @param sqlFragment SQL 片段；{@code null} 表示关闭
         */
        void failOnSql(String sqlFragment) {
            this.statements.failOnSql(sqlFragment);
        }

        /** @return {@code addBatch()} 调用次数 */
        int batchAdds() {
            return this.statements.batchAdds();
        }

        /** @return 成功下发的批次数 */
        int batchExecutions() {
            return this.statements.batchExecutions();
        }

        /** @return 被注入失败的批次数 */
        int failedBatchExecutions() {
            return this.statements.batchExecutionsFailed();
        }

        /** @return 每批行数 */
        java.util.List<Integer> batchSizes() {
            return this.statements.batchSizes();
        }

        /** @return 通过 {@code executeBatch()} 下发的语句 SQL */
        java.util.List<String> batchExecutionSql() {
            return this.statements.batchExecutionSql();
        }

        /** @return 通过单条 {@code executeUpdate()} 下发的语句 SQL */
        java.util.List<String> singleExecutionSql() {
            return this.statements.singleExecutionSql();
        }
    }

    /**
     * 构造一段切片：内容为 {@code chunk-<index>}，摘要随内容确定。
     *
     * @param documentId 文档标识
     * @param count      切片数量
     * @return 切片列表（序号从 0 连续递增）
     */
    static java.util.List<com.flowdesk.domain.knowledge.KnowledgeDocumentChunk> chunksOf(
            KnowledgeDocumentId documentId, int count) {

        java.util.List<com.flowdesk.domain.knowledge.KnowledgeDocumentChunk> chunks =
                new java.util.ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            String content = "chunk-" + index;
            chunks.add(new com.flowdesk.domain.knowledge.KnowledgeDocumentChunk(documentId, index, content,
                    content.codePointCount(0, content.length()), Sha256Digest.of(sha256Of(content)),
                    Instant.parse("2026-05-01T10:00:04Z")));
        }
        return java.util.List.copyOf(chunks);
    }
}