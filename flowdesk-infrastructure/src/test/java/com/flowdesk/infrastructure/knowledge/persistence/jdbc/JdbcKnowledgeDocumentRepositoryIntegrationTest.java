package com.flowdesk.infrastructure.knowledge.persistence.jdbc;

import static com.flowdesk.infrastructure.knowledge.KnowledgeTestContent.sha256Hex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.application.knowledge.port.out.VersionedKnowledgeDocument;
import com.flowdesk.domain.knowledge.DocumentFormat;
import com.flowdesk.domain.knowledge.DocumentTitle;
import com.flowdesk.domain.knowledge.KnowledgeDocument;
import com.flowdesk.domain.knowledge.KnowledgeDocumentId;
import com.flowdesk.domain.knowledge.KnowledgeDocumentStatus;
import com.flowdesk.domain.knowledge.OriginalFilename;
import com.flowdesk.domain.knowledge.Sha256Digest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 知识文档元数据 JDBC 测试：真实数据库（H2 的 PostgreSQL 兼容模式）+ 真实 Flyway 迁移。
 *
 * <p>覆盖：迁移链、完整 round-trip、重复主键/内容键映射、非法快照映射、数据库 CHECK 约束，
 * 以及「sha256 只建普通索引、允许相同内容作为不同文档上传」。</p>
 */
class JdbcKnowledgeDocumentRepositoryIntegrationTest {

    private static final String MIGRATION_LOCATION = "classpath:db/migration";

    private static final Instant UPLOADED_AT = Instant.parse("2026-05-01T10:00:00Z");

    private static MigrateResult migrateResult;

    private static JdbcClient jdbcClient;

    private static JdbcKnowledgeDocumentRepository repository;

    @BeforeAll
    static void migrateAnEmptyDatabase() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:flowdesk_knowledge_it"
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        migrateResult = Flyway.configure()
                .dataSource(dataSource)
                .locations(MIGRATION_LOCATION)
                .load()
                .migrate();

        jdbcClient = JdbcClient.create(dataSource);
        repository = new JdbcKnowledgeDocumentRepository(jdbcClient,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    @BeforeEach
    void clearDocuments() {
        jdbcClient.sql("DELETE FROM knowledge_documents").update();
    }

    // ---------- ① 迁移 ----------

    @Test
    void flywayAppliesAllFourMigrations() {
        assertThat(migrateResult.migrationsExecuted)
                .as("V1 工单表 + V2 搜索索引 + V3 知识文档表 + V4 解析字段与切片表")
                .isEqualTo(4);
    }

    @Test
    void createsTheKnowledgeDocumentsTable() {
        assertThat(scalarLong("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_name = 'knowledge_documents'")).isEqualTo(1L);
    }

    @Test
    void createsTheExpectedIndexesAndSha256IsNotUnique() {
        List<String> indexes = jdbcClient
                .sql("SELECT index_name FROM information_schema.indexes WHERE table_name = 'knowledge_documents'")
                .query(String.class)
                .list();

        assertThat(indexes).contains("idx_knowledge_documents_sha256", "idx_knowledge_documents_created_at");

        // 相同内容必须允许作为不同逻辑文档上传，因此 sha256 只有普通索引
        Long uniqueSha256Indexes = jdbcClient.sql("SELECT COUNT(*) FROM information_schema.indexes "
                + "WHERE table_name = 'knowledge_documents' AND index_name = 'idx_knowledge_documents_sha256' "
                + "AND index_type_name LIKE '%UNIQUE%'").query(Long.class).single();
        assertThat(uniqueSha256Indexes).isZero();
    }

    @Test
    void contentKeyIsUnique() {
        insert(document(randomId(), "a", "a.txt", sha256Hex("a".getBytes(StandardCharsets.UTF_8)), "shared-key"));

        assertThatThrownBy(() -> insert(document(randomId(), "b", "b.txt",
                sha256Hex("b".getBytes(StandardCharsets.UTF_8)), "shared-key")))
                .isInstanceOf(KnowledgeApplicationException.class)
                .extracting(thrown -> ((KnowledgeApplicationException) thrown).errorCode())
                .isEqualTo(KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_ALREADY_EXISTS);
    }

    // ---------- ② 完整 round-trip ----------

    @Test
    void roundTripsEveryField() {
        byte[] content = "知识库内容".getBytes(StandardCharsets.UTF_8);
        KnowledgeDocument document = new KnowledgeDocumentBuilder()
                .id(randomId())
                .title("季度运维报告")
                .fileName("报告.PDF")
                .sizeBytes(content.length)
                .digest(sha256Hex(content))
                .contentKey("content-key-1")
                .build();

        VersionedKnowledgeDocument saved = repository.insert(document);

        assertThat(saved.version()).isZero();

        VersionedKnowledgeDocument loaded = repository.findById(document.id()).orElseThrow();
        assertThat(loaded.version()).isZero();
        assertThat(loaded.document().id()).isEqualTo(document.id());
        assertThat(loaded.document().title().value()).isEqualTo("季度运维报告");
        assertThat(loaded.document().originalFilename().value()).isEqualTo("报告.PDF");
        assertThat(loaded.document().format()).isEqualTo(DocumentFormat.PDF);
        assertThat(loaded.document().mediaType()).isEqualTo("application/pdf");
        assertThat(loaded.document().sizeBytes()).isEqualTo(content.length);
        assertThat(loaded.document().sha256().value()).isEqualTo(sha256Hex(content));
        assertThat(loaded.document().contentKey()).isEqualTo("content-key-1");
        assertThat(loaded.document().status()).isEqualTo(KnowledgeDocumentStatus.UPLOADED);
        assertThat(loaded.document().createdAt()).isEqualTo(UPLOADED_AT);
        assertThat(loaded.document().updatedAt()).isEqualTo(UPLOADED_AT);
        assertThat(loaded.document()).isEqualTo(document);
    }

    @Test
    void roundTripsEverySupportedFormat() {
        for (DocumentFormat format : DocumentFormat.values()) {
            KnowledgeDocument document = new KnowledgeDocumentBuilder()
                    .id(randomId())
                    .format(format)
                    .fileName("file." + format.extension())
                    .contentKey("key-" + format.name())
                    .build();

            repository.insert(document);

            assertThat(repository.findById(document.id()).orElseThrow().document().format())
                    .isEqualTo(format);
        }
    }

    @Test
    void returnsTheSameContentUnderDifferentDocumentIdsAndSha256() {
        String digest = sha256Hex("identical".getBytes(StandardCharsets.UTF_8));
        KnowledgeDocumentId firstId = randomId();
        KnowledgeDocumentId secondId = randomId();

        repository.insert(document(firstId, "a", "a.txt", digest, "key-a"));
        repository.insert(document(secondId, "b", "b.txt", digest, "key-b"));

        assertThat(repository.findById(firstId)).isPresent();
        assertThat(repository.findById(secondId)).isPresent();
        assertThat(scalarLong("SELECT COUNT(*) FROM knowledge_documents")).isEqualTo(2L);
    }

    @Test
    void returnsEmptyForUnknownId() {
        assertThat(repository.findById(randomId())).isEmpty();
    }

    @Test
    void returnsIndependentAggregates() {
        KnowledgeDocument document = document(randomId(), "a", "a.txt",
                sha256Hex("a".getBytes(StandardCharsets.UTF_8)), "key-a");
        repository.insert(document);

        VersionedKnowledgeDocument first = repository.findById(document.id()).orElseThrow();
        VersionedKnowledgeDocument second = repository.findById(document.id()).orElseThrow();

        assertThat(first.document()).isNotSameAs(second.document());
        assertThat(first.document()).isEqualTo(second.document());
    }

    @Test
    void rejectsDuplicateIdAtomically() {
        KnowledgeDocumentId id = randomId();
        insert(document(id, "a", "a.txt", sha256Hex("a".getBytes(StandardCharsets.UTF_8)), "key-a"));

        assertThatThrownBy(() -> insert(document(id, "b", "b.txt",
                sha256Hex("b".getBytes(StandardCharsets.UTF_8)), "key-b")))
                .isInstanceOf(KnowledgeApplicationException.class)
                .extracting(thrown -> ((KnowledgeApplicationException) thrown).errorCode())
                .isEqualTo(KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_ALREADY_EXISTS);

        assertThat(scalarLong("SELECT COUNT(*) FROM knowledge_documents")).as("只应有一条").isEqualTo(1L);
    }

    @Test
    void rejectsNullArguments() {
        assertThatThrownBy(() -> repository.insert(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> repository.findById(null)).isInstanceOf(NullPointerException.class);
    }

    // ---------- ③ 非法快照 ----------

    @Test
    void mapsBrokenSnapshotsToInternalError() {
        KnowledgeDocument document = insertSample();

        // 绕过领域直接改库：标题变成「首尾有空白」——数据库 CHECK（trim 后非空）放行，
        // 但领域层要求标题必须已经 strip，因此恢复时必须失败
        jdbcClient.sql("UPDATE knowledge_documents SET title = ? WHERE id = ?")
                .param(1, " 报告 ").param(2, document.id().value()).update();

        assertThatThrownBy(() -> repository.findById(document.id()))
                .isInstanceOf(KnowledgeApplicationException.class)
                .extracting(thrown -> ((KnowledgeApplicationException) thrown).errorCode())
                .isEqualTo(KnowledgeApplicationErrorCode.INVALID_PERSISTED_DOCUMENT);
    }

    @Test
    void mapsPathTraversalFileNameInTheDatabaseToInternalError() {
        KnowledgeDocument document = insertSample();

        // 数据库只校验「非空白」，因此一个带路径分隔符的文件名能进库；
        // 它必须在恢复时被领域层挡住，绝不能作为正常数据继续使用
        jdbcClient.sql("UPDATE knowledge_documents SET original_filename = ? WHERE id = ?")
                .param(1, "../etc/passwd").param(2, document.id().value()).update();

        assertThatThrownBy(() -> repository.findById(document.id()))
                .isInstanceOf(KnowledgeApplicationException.class)
                .extracting(thrown -> ((KnowledgeApplicationException) thrown).errorCode())
                .isEqualTo(KnowledgeApplicationErrorCode.INVALID_PERSISTED_DOCUMENT);
    }

    @Test
    void mapsNonHexDigestInTheDatabaseToInternalError() {
        KnowledgeDocument document = insertSample();

        // 64 位小写但不是十六进制：数据库的「长度 64 + 小写」CHECK 会放行，领域层必须拒绝
        jdbcClient.sql("UPDATE knowledge_documents SET sha256 = ? WHERE id = ?")
                .param(1, "z".repeat(64)).param(2, document.id().value()).update();

        assertThatThrownBy(() -> repository.findById(document.id()))
                .isInstanceOf(KnowledgeApplicationException.class)
                .extracting(thrown -> ((KnowledgeApplicationException) thrown).errorCode())
                .isEqualTo(KnowledgeApplicationErrorCode.INVALID_PERSISTED_DOCUMENT);
    }

    @Test
    void doesNotLeakSqlOrTableDetailsInTheError() {
        KnowledgeDocument document = insertSample();
        String brokenDigest = "z".repeat(64);
        jdbcClient.sql("UPDATE knowledge_documents SET sha256 = ? WHERE id = ?")
                .param(1, brokenDigest).param(2, document.id().value()).update();

        KnowledgeApplicationException thrown = (KnowledgeApplicationException) org.assertj.core.api.Assertions
                .catchThrowable(() -> repository.findById(document.id()));

        assertThat(thrown.getMessage())
                .doesNotContain("SELECT")
                .doesNotContain("knowledge_documents")
                .doesNotContain(brokenDigest);
    }

    // ---------- ④ 数据库约束 ----------

    @Test
    void databaseRejectsInvalidValuesWrittenBehindTheDomain() {
        assertDatabaseRejects("UPDATE knowledge_documents SET size_bytes = 0 WHERE id = ?");
        assertDatabaseRejects("UPDATE knowledge_documents SET version = -1 WHERE id = ?");
        assertDatabaseRejects("UPDATE knowledge_documents SET format = 'EXE' WHERE id = ?");
        assertDatabaseRejects("UPDATE knowledge_documents SET status = 'PARSED' WHERE id = ?");
        assertDatabaseRejects("UPDATE knowledge_documents SET sha256 = 'ABCDEF' WHERE id = ?");
        assertDatabaseRejects("UPDATE knowledge_documents SET sha256 = 'ABCDEF' || "
                + "substring(sha256, 7) WHERE id = ?");
        assertDatabaseRejects("UPDATE knowledge_documents SET created_at = updated_at + INTERVAL '1' DAY "
                + "WHERE id = ?");
    }

    @Test
    void databaseRejectsBlankTitleOrFileName() {
        KnowledgeDocument document = insertSample();

        assertThatThrownBy(() -> jdbcClient.sql("UPDATE knowledge_documents SET title = ? WHERE id = ?")
                .param(1, "  ").param(2, document.id().value()).update())
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcClient.sql(
                "UPDATE knowledge_documents SET original_filename = ? WHERE id = ?")
                .param(1, "").param(2, document.id().value()).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------- ⑤ 数据库故障映射（FD-0008-R1） ----------

    @Test
    void insertMapsAnyDatabaseFailureToMetadataStorageFailure() {
        JdbcKnowledgeDocumentRepository broken = brokenRepository();

        KnowledgeApplicationException thrown = (KnowledgeApplicationException) org.assertj.core.api.Assertions
                .catchThrowable(() -> broken.insert(document(randomId(), "a", "a.txt",
                        sha256Hex("a".getBytes(StandardCharsets.UTF_8)), "key-a")));

        assertThat(thrown.errorCode()).isEqualTo(KnowledgeApplicationErrorCode.METADATA_STORAGE_FAILURE);
        assertThat(thrown.getCause()).as("必须保留原异常作为 cause 供服务端诊断")
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(thrown.getMessage())
                .as("对外文案不得包含 SQL、表名、驱动或路径")
                .doesNotContain("SELECT").doesNotContain("INSERT")
                .doesNotContain("knowledge_documents").doesNotContain("jdbc").doesNotContain("h2");
    }

    @Test
    void findByIdMapsAnyDatabaseFailureToMetadataStorageFailure() {
        JdbcKnowledgeDocumentRepository broken = brokenRepository();

        KnowledgeApplicationException thrown = (KnowledgeApplicationException) org.assertj.core.api.Assertions
                .catchThrowable(() -> broken.findById(randomId()));

        assertThat(thrown.errorCode()).isEqualTo(KnowledgeApplicationErrorCode.METADATA_STORAGE_FAILURE);
        assertThat(thrown.getCause()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(thrown.getMessage()).doesNotContain("knowledge_documents").doesNotContain("jdbc");
    }

    @Test
    void duplicateKeyStaysMappedToAlreadyExistsRatherThanStorageFailure() {
        KnowledgeDocument document = insertSample();

        KnowledgeApplicationException thrown = (KnowledgeApplicationException) org.assertj.core.api.Assertions
                .catchThrowable(() -> repository.insert(document));

        assertThat(thrown.errorCode())
                .as("重复键必须保持自己的语义，不能被通用映射吞掉")
                .isEqualTo(KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_ALREADY_EXISTS);
    }

    @Test
    void rowMapperFailureIsNotOverwrittenByTheGenericDatabaseMapping() {
        KnowledgeDocument document = insertSample();
        jdbcClient.sql("UPDATE knowledge_documents SET sha256 = ? WHERE id = ?")
                .param(1, "z".repeat(64)).param(2, document.id().value()).update();

        KnowledgeApplicationException thrown = (KnowledgeApplicationException) org.assertj.core.api.Assertions
                .catchThrowable(() -> repository.findById(document.id()));

        assertThat(thrown.errorCode())
                .as("快照不自洽必须保持自己的错误码，而不是被当成数据库访问失败")
                .isEqualTo(KnowledgeApplicationErrorCode.INVALID_PERSISTED_DOCUMENT);
    }

    /**
     * 指向一个<b>没有建表</b>的独立 H2 数据库：用真实数据库故障驱动异常映射。
     */
    private static JdbcKnowledgeDocumentRepository brokenRepository() {
        JdbcDataSource empty = new JdbcDataSource();
        empty.setURL("jdbc:h2:mem:flowdesk_knowledge_broken_it"
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        empty.setUser("sa");
        empty.setPassword("");
        // 刻意不执行 Flyway：knowledge_documents 表不存在，任何访问都会失败
        return new JdbcKnowledgeDocumentRepository(JdbcClient.create(empty),
                new TransactionTemplate(new DataSourceTransactionManager(empty)));
    }

    // ---------- 辅助 ----------

    private static void assertDatabaseRejects(String sql) {
        KnowledgeDocument document = document(randomId(), "a", "a.txt",
                sha256Hex(("a" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8)),
                "key-" + UUID.randomUUID());
        repository.insert(document);

        assertThatThrownBy(() -> jdbcClient.sql(sql).param(1, document.id().value()).update())
                .as(sql)
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbcClient.sql("DELETE FROM knowledge_documents WHERE id = ?")
                .param(1, document.id().value()).update();
    }

    /**
     * 插入一条合法样本文档（后续用例会绕过领域改库，制造非法快照）。
     */
    private static KnowledgeDocument insertSample() {
        KnowledgeDocument document = document(randomId(), "季度运维报告", "report.pdf",
                sha256Hex("sample".getBytes(StandardCharsets.UTF_8)), "key-" + UUID.randomUUID());
        repository.insert(document);
        return document;
    }

    private static VersionedKnowledgeDocument insert(KnowledgeDocument document) {
        return repository.insert(document);
    }

    private static KnowledgeDocumentId randomId() {
        return KnowledgeDocumentId.of(UUID.randomUUID());
    }

    private static KnowledgeDocument document(KnowledgeDocumentId id, String title, String fileName,
            String digest, String contentKey) {

        return new KnowledgeDocumentBuilder()
                .id(id)
                .title(title)
                .fileName(fileName)
                .digest(digest)
                .contentKey(contentKey)
                .build();
    }

    private static long scalarLong(String sql) {
        Long value = jdbcClient.sql(sql).query(Long.class).single();
        return value == null ? -1L : value;
    }

    /**
     * 便于按需覆盖字段的构造器。
     */
    private static final class KnowledgeDocumentBuilder {

        private KnowledgeDocumentId id = randomId();

        private String title = "季度运维报告";

        private String fileName = "report.pdf";

        private DocumentFormat format = DocumentFormat.PDF;

        private String mediaType = "application/pdf";

        private long sizeBytes = 12L;

        private String digest = sha256Hex("content".getBytes(StandardCharsets.UTF_8));

        private String contentKey = "content-" + UUID.randomUUID();

        private Instant uploadedAt = UPLOADED_AT;

        KnowledgeDocumentBuilder id(KnowledgeDocumentId value) {
            this.id = value;
            return this;
        }

        KnowledgeDocumentBuilder title(String value) {
            this.title = value;
            return this;
        }

        KnowledgeDocumentBuilder fileName(String value) {
            this.fileName = value;
            return this;
        }

        KnowledgeDocumentBuilder sizeBytes(long value) {
            this.sizeBytes = value;
            return this;
        }

        KnowledgeDocumentBuilder format(DocumentFormat value) {
            this.format = value;
            this.mediaType = value.canonicalMediaType();
            return this;
        }

        KnowledgeDocumentBuilder digest(String value) {
            this.digest = value;
            return this;
        }

        KnowledgeDocumentBuilder contentKey(String value) {
            this.contentKey = value;
            return this;
        }

        KnowledgeDocument build() {
            return KnowledgeDocument.create(this.id, DocumentTitle.of(this.title),
                    OriginalFilename.of(this.fileName), this.format, this.mediaType, this.sizeBytes,
                    Sha256Digest.of(this.digest), this.contentKey, this.uploadedAt);
        }
    }
}
