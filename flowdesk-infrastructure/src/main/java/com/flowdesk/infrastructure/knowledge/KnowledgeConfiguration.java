package com.flowdesk.infrastructure.knowledge;

import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentContentStore;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentIdGenerator;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentRepository;
import com.flowdesk.application.knowledge.port.out.KnowledgeTimeProvider;
import com.flowdesk.infrastructure.knowledge.persistence.jdbc.JdbcKnowledgeDocumentRepository;
import com.flowdesk.infrastructure.knowledge.storage.LocalFileSystemKnowledgeContentStore;
import com.flowdesk.infrastructure.knowledge.support.SystemKnowledgeTimeProvider;
import com.flowdesk.infrastructure.knowledge.support.UuidKnowledgeDocumentIdGenerator;
import java.time.Clock;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

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
@EnableConfigurationProperties({ KnowledgeUploadProperties.class, KnowledgeStorageProperties.class })
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
     * @param jdbcClient JDBC 客户端
     * @return 元数据仓储
     */
    @Bean
    public KnowledgeDocumentRepository knowledgeDocumentRepository(JdbcClient jdbcClient) {
        return new JdbcKnowledgeDocumentRepository(jdbcClient);
    }

    /**
     * @param properties 存储配置
     * @return 本地文件系统内容存储
     */
    @Bean
    public KnowledgeDocumentContentStore knowledgeDocumentContentStore(KnowledgeStorageProperties properties) {
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
