package com.flowdesk.bootstrap.knowledge;

import com.flowdesk.application.knowledge.port.out.DocumentChunker;
import com.flowdesk.application.knowledge.port.out.DocumentTextParser;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentChunkStore;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentEmbeddingStore;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentContentReader;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentContentStore;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentIdGenerator;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentRepository;
import com.flowdesk.application.knowledge.port.out.KnowledgeEmbeddingPort;
import com.flowdesk.application.knowledge.port.out.KnowledgeTimeProvider;
import com.flowdesk.application.knowledge.service.KnowledgeDocumentApplicationService;
import com.flowdesk.application.knowledge.service.KnowledgeDocumentIndexingService;
import com.flowdesk.application.knowledge.service.KnowledgeDocumentParsingService;
import com.flowdesk.infrastructure.knowledge.KnowledgeUploadProperties;
import com.flowdesk.infrastructure.knowledge.embedding.KnowledgeEmbeddingProperties;
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

    /**
     * 文档索引用例服务（FD-0010）。
     *
     * <p>开关与模型参数从 {@link KnowledgeEmbeddingProperties} 取出后以纯值注入：
     * 应用层只认识 {@code boolean}、维度描述符与批次大小，不认识 Spring 的配置类型。
     * 关闭状态下端口由「拒绝一切」的占位实现提供，用例服务本身仍然存在 ——
     * 这样 HTTP 契约（503 {@code KNOWLEDGE_EMBEDDING_DISABLED}）在默认环境也成立。</p>
     *
     * @param documentRepository 元数据仓储（含 CAS 更新）
     * @param chunkStore         切片分页读取端口
     * @param embeddingPort      向量生成端口
     * @param embeddingStore     向量原子写入端口
     * @param timeProvider       时间端口
     * @param embedding          向量化配置
     * @return 索引用例服务
     */
    @Bean
    public KnowledgeDocumentIndexingService knowledgeDocumentIndexingService(
            KnowledgeDocumentRepository documentRepository,
            KnowledgeDocumentChunkStore chunkStore,
            KnowledgeEmbeddingPort embeddingPort,
            KnowledgeDocumentEmbeddingStore embeddingStore,
            KnowledgeTimeProvider timeProvider,
            KnowledgeEmbeddingProperties embedding) {

        embedding.validate();
        return new KnowledgeDocumentIndexingService(documentRepository, chunkStore, embeddingPort,
                embeddingStore, timeProvider, embedding.isEnabled(), embedding.descriptor(),
                embedding.getBatchSize());
    }
}
