package com.flowdesk.bootstrap.knowledge;

import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentContentStore;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentIdGenerator;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentRepository;
import com.flowdesk.application.knowledge.port.out.KnowledgeTimeProvider;
import com.flowdesk.application.knowledge.service.KnowledgeDocumentApplicationService;
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
}
