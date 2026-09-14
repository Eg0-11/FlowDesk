package com.flowdesk.bootstrap.knowledge;

import com.flowdesk.application.knowledge.port.out.DocumentChunker;
import com.flowdesk.application.knowledge.port.out.DocumentTextParser;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentChunkStore;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentContentReader;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentContentStore;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentIdGenerator;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentRepository;
import com.flowdesk.application.knowledge.port.out.KnowledgeTimeProvider;
import com.flowdesk.application.knowledge.service.KnowledgeDocumentApplicationService;
import com.flowdesk.application.knowledge.service.KnowledgeDocumentParsingService;
import com.flowdesk.infrastructure.knowledge.KnowledgeUploadProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 知识文档应用服务的装配。
 *
 * <p>{@link KnowledgeDocumentApplicationService} 同时实现上传与查询两个输入端口，
 * 因此注册为它自身的 Bean 后，输入适配器按任一接口类型注入都能拿到同一实例。</p>
 *
 * <p>上传大小上限在这里从 {@link KnowledgeUploadProperties}（Spring 的 {@code DataSize}）
 * 转成 {@code long} 字节数注入给用例 —— 应用层因此完全不需要认识 Spring 的类型。</p>
 *
 * <p>{@link KnowledgeDocumentParsingService} 同样是纯 Java 服务：它依赖的解析与切片端口
 * 分别由基础设施层的 Tika 适配器与确定性切片器实现（见 {@code KnowledgeConfiguration}）。</p>
 */
@Configuration(proxyBeanMethods = false)
public class KnowledgeApplicationConfiguration {

    /**
     * @param documentRepository 元数据存储端口
     * @param contentStore       内容存储端口
     * @param idGenerator        标识生成端口
     * @param timeProvider       时间端口
     * @param uploadProperties   上传配置
     * @return 知识文档用例服务
     */
    @Bean
    public KnowledgeDocumentApplicationService knowledgeDocumentApplicationService(
            KnowledgeDocumentRepository documentRepository,
            KnowledgeDocumentContentStore contentStore,
            KnowledgeDocumentIdGenerator idGenerator,
            KnowledgeTimeProvider timeProvider,
            KnowledgeUploadProperties uploadProperties) {

        return new KnowledgeDocumentApplicationService(documentRepository, contentStore, idGenerator,
                timeProvider, uploadProperties.getMaxSize().toBytes());
    }

    /**
     * 文档解析用例服务（FD-0009）。
     *
     * <p>六个端口全部由基础设施层实现：仓储与切片存储走 JDBC（显式事务），
     * 原文读取与解析走本地文件系统 + Tika，切片算法是纯 Java 的确定性实现。</p>
     *
     * @param documentRepository 元数据仓储（含 CAS 更新）
     * @param contentReader      原始内容读取端口
     * @param textParser         文本提取端口
     * @param chunker            确定性切片端口
     * @param chunkStore         切片与完成状态的原子写入端口
     * @param timeProvider       时间端口
     * @return 解析用例服务
     */
    @Bean
    public KnowledgeDocumentParsingService knowledgeDocumentParsingService(
            KnowledgeDocumentRepository documentRepository,
            KnowledgeDocumentContentReader contentReader,
            DocumentTextParser textParser,
            DocumentChunker chunker,
            KnowledgeDocumentChunkStore chunkStore,
            KnowledgeTimeProvider timeProvider) {

        return new KnowledgeDocumentParsingService(documentRepository, contentReader, textParser, chunker,
                chunkStore, timeProvider);
    }
}
