package com.flowdesk.bootstrap.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentEmbeddingStore;
import com.flowdesk.application.knowledge.port.out.KnowledgeEmbeddingPort;
import com.flowdesk.infrastructure.knowledge.embedding.DashScopeKnowledgeEmbeddingAdapter;
import com.flowdesk.infrastructure.knowledge.embedding.DisabledKnowledgeEmbedding;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * 默认 profile 的模型装配契约（FD-0010）。
 *
 * <p>默认环境必须：不创建 EmbeddingModel、不发生网络请求、不需要任何 API Key；
 * 同时索引端口仍然存在（由占位实现提供），因此 503 是稳定契约而不是「Bean 缺失」。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:flowdesk_embedding_default_ctx"
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "flowdesk.knowledge.storage.root=target/flowdesk-embedding-default-ctx"
})
class KnowledgeEmbeddingDefaultContextTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void createsNoEmbeddingModel() {
        assertThat(this.applicationContext.getBeanNamesForType(EmbeddingModel.class))
                .as("默认 profile 不得创建 EmbeddingModel：没有 API Key 也要能启动")
                .isEmpty();
    }

    @Test
    void keepsTheIndexingUseCaseAndDisabledPortsAvailable() {
        assertThat(this.applicationContext.getBeanNamesForType(
                com.flowdesk.application.knowledge.port.in.IndexKnowledgeDocumentUseCase.class))
                .as("索引用例必须存在，这样关闭状态下返回的是 503 而不是 404/500")
                .hasSize(1);
        assertThat(this.applicationContext.getBean(KnowledgeEmbeddingPort.class))
                .isInstanceOf(DisabledKnowledgeEmbedding.Port.class);
        assertThat(this.applicationContext.getBean(KnowledgeDocumentEmbeddingStore.class))
                .isInstanceOf(DisabledKnowledgeEmbedding.Store.class);
    }

    @Test
    void keepsDashScopeAndDeepSeekDisabledByDefault() {
        assertThat(this.applicationContext.getEnvironment().getProperty("spring.ai.dashscope.enabled"))
                .as("默认环境必须显式关闭 DashScope：它的自动配置项 matchIfMissing=true，不写关不掉")
                .isEqualTo("false");
        assertThat(this.applicationContext.getEnvironment().getProperty("spring.ai.model.embedding"))
                .isEqualTo("none");
        assertThat(this.applicationContext.getEnvironment().getProperty("flowdesk.knowledge.embedding.enabled"))
                .isEqualTo("false");
    }

    @Test
    void noDashScopeAdapterIsWired() {
        assertThat(this.applicationContext.getBeanNamesForType(DashScopeKnowledgeEmbeddingAdapter.class))
                .isEmpty();
    }
}
