package com.flowdesk.infrastructure.knowledge;

import com.flowdesk.application.knowledge.port.out.DocumentChunker;
import com.flowdesk.application.knowledge.port.out.DocumentTextParser;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentChunkStore;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentContentStore;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentIdGenerator;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentRepository;
import com.flowdesk.application.knowledge.port.out.KnowledgeTimeProvider;
import com.flowdesk.infrastructure.knowledge.chunking.DeterministicDocumentChunker;
import com.flowdesk.infrastructure.knowledge.chunking.KnowledgeChunkingProperties;
import com.flowdesk.infrastructure.knowledge.parsing.TikaDocumentTextParser;
import com.flowdesk.infrastructure.knowledge.persistence.jdbc.JdbcKnowledgeDocumentChunkStore;
import com.flowdesk.infrastructure.knowledge.persistence.jdbc.JdbcKnowledgeDocumentRepository;
import com.flowdesk.infrastructure.knowledge.storage.LocalFileSystemKnowledgeContentStore;
import com.flowdesk.infrastructure.knowledge.support.SystemKnowledgeTimeProvider;
import com.flowdesk.infrastructure.knowledge.support.UuidKnowledgeDocumentIdGenerator;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 知识文档基础设施装配。
 *
 * <p>把应用层定义的四个输出端口落实为具体实现：</p>
 * <ul>
 *   <li>{@link KnowledgeDocumentRepository} → {@link JdbcKnowledgeDocumentRepository}（Spring JDBC）；</li>
 *   <li>{@link KnowledgeDocumentContentStore} → {@link LocalFileSystemKnowledgeContentStore}
 *       （本地文件系统；将来可整体替换为对象存储适配器）；</li>
 *   <li>{@link KnowledgeDocumentIdGenerator} → {@link UuidKnowledgeDocumentIdGenerator}；</li>
 *   <li>{@link KnowledgeTimeProvider} → {@link SystemKnowledgeTimeProvider}。</li>
 * </ul>
 *
 * <p>应用层与领域层<b>不含任何 Spring 注解</b>，装配集中在这里与 bootstrap 层。
 * 上传大小上限也在这里绑定成属性，由 bootstrap 注入给用例服务 ——
 * 应用服务只拿到一个 {@code long}，不认识 Spring 的 {@code DataSize}。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ KnowledgeUploadProperties.class, KnowledgeStorageProperties.class,
        KnowledgeChunkingProperties.class })
public class KnowledgeConfiguration {

    /**
     * 启动期校验两个上传上限不会冲突。
     *
     * <p>Bean 在装配阶段创建，因此配置冲突（例如应用上限 25MB 但容器上限仍是 20MB）
     * 会让应用<b>启动失败</b>，而不是在生产里表现为「配了 25MB 却传不上去」。</p>
     *
     * @param upload    应用层上传配置
     * @param multipart Spring Boot 的 multipart 配置（含默认值）
     * @return 校验通过标记
     */
    @Bean
    public Boolean knowledgeUploadLimitConsistency(KnowledgeUploadProperties upload,
            MultipartProperties multipart) {

        return KnowledgeUploadLimitValidator.validateOrFail(upload, multipart);
    }

    /**
     * 知识文档写事务模板：CAS 更新、切片替换与状态更新需要显式事务边界。
     *
     * <p>与列表查询的只读模板无关：这里的写事务很短（解析与切片都在事务之外完成）。</p>
     *
     * @param transactionManager Spring Boot 自动配置的数据源事务管理器
     * @return 写事务模板
     */
    @Bean
    public TransactionOperations knowledgeWriteTransactions(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    /**
     * @param jdbcClient JDBC 客户端
     * @return 元数据仓储
     */
    @Bean
    public JdbcKnowledgeDocumentRepository knowledgeDocumentRepository(JdbcClient jdbcClient,
            @Qualifier("knowledgeWriteTransactions") TransactionOperations knowledgeWriteTransactions) {

        return new JdbcKnowledgeDocumentRepository(jdbcClient, knowledgeWriteTransactions);
    }

    /**
     * @param jdbcClient             JDBC 客户端
     * @param knowledgeWriteTransactions 写事务模板
     * @param documentRepository     文档仓储（同一事务内复用）
     * @return 切片与「完成解析」的原子存储
     */
    @Bean
    public KnowledgeDocumentChunkStore knowledgeDocumentChunkStore(JdbcClient jdbcClient,
            @Qualifier("knowledgeWriteTransactions") TransactionOperations knowledgeWriteTransactions,
            JdbcKnowledgeDocumentRepository documentRepository) {

        return new JdbcKnowledgeDocumentChunkStore(jdbcClient, knowledgeWriteTransactions, documentRepository);
    }

    /**
     * 文档文本提取适配器（Apache Tika 3.x）。
     *
     * @param chunking 切片配置（提供提取文本上限）
     * @return 文本提取端口
     */
    @Bean
    public DocumentTextParser documentTextParser(KnowledgeChunkingProperties chunking) {
        return new TikaDocumentTextParser(chunking.getMaxExtractedCodePoints());
    }

    /**
     * 确定性切片适配器。
     *
     * @param chunking 切片配置
     * @return 切片端口
     */
    @Bean
    public DocumentChunker documentChunker(KnowledgeChunkingProperties chunking) {
        return new DeterministicDocumentChunker(chunking.getChunkSize(), chunking.getOverlap(),
                chunking.getMaxChunks());
    }

    /**
     * 启动期校验切片与解析规模配置。
     *
     * <p>Bean 在装配阶段创建，因此非法配置（chunk-size 超过列容量、overlap 不小于 chunk-size、
     * 上限组合溢出等）会让应用<b>启动失败</b>，而不是等到解析某个文档时才在运行期暴露。</p>
     *
     * <p>刻意<b>不</b>校验「提取文本上限 vs 上传大小上限」的关系：两者量纲不同 ——
     * 上传上限约束的是压缩后的字节数，而提取上限约束的是解压后的文本量，
     * 一个 1KB 的 DOCX 完全可能展开出数兆文本。提取上限存在的意义正是挡住这类解压炸弹，
     * 因此它与上传上限之间不存在「必须大于/小于」的固定关系。</p>
     *
     * @param chunking 切片配置
     * @return 校验通过标记
     */
    @Bean
    public Boolean knowledgeChunkingConsistency(KnowledgeChunkingProperties chunking) {
        chunking.validate();
        return Boolean.TRUE;
    }

    /**
     * 本地文件系统内容存储。
     *
     * <p>Bean 的声明类型是具体类而不是端口接口：同一个适配器同时实现
     * {@link KnowledgeDocumentContentStore}（写）与
     * {@link com.flowdesk.application.knowledge.port.out.KnowledgeDocumentContentReader}（读），
     * 两条路径共用同一份路径安全校验。声明为具体类后，按任一端口类型注入都能拿到它。</p>
     *
     * @param properties 存储配置
     * @return 本地文件系统内容存储
     */
    @Bean
    public LocalFileSystemKnowledgeContentStore knowledgeDocumentContentStore(
            KnowledgeStorageProperties properties) {

        return new LocalFileSystemKnowledgeContentStore(properties.getRoot());
    }

    /**
     * @return 文档标识生成器
     */
    @Bean
    public KnowledgeDocumentIdGenerator knowledgeDocumentIdGenerator() {
        return new UuidKnowledgeDocumentIdGenerator();
    }

    /**
     * @param clock 时钟（复用容器里已存在的 UTC 时钟）
     * @return 时间端口
     */
    @Bean
    public KnowledgeTimeProvider knowledgeTimeProvider(Clock clock) {
        return new SystemKnowledgeTimeProvider(clock);
    }
}
