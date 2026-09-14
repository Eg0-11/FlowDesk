package com.flowdesk.infrastructure.knowledge.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;

/**
 * 向量化环境校验测试（FD-0010）。
 *
 * <p>三条环境边界必须能被明确区分，而且<b>配置错误必须让应用启动失败</b>：
 * 关闭时任何数据源都可以；启用时必须是 PostgreSQL；启用时必须有 EmbeddingModel。
 * 错误信息里不得出现用户名、密码或 API Key。</p>
 */
class KnowledgeEmbeddingConfigurationValidatorTest {

    private static final String H2_URL = "jdbc:h2:mem:flowdesk;MODE=PostgreSQL";
    private static final String POSTGRES_URL = "jdbc:postgresql://localhost:5432/flowdesk";

    @Test
    void disabledEmbeddingAcceptsAnyDataSourceAndNoModel() {
        assertThat(KnowledgeEmbeddingConfigurationValidator.validate(properties(false), dataSource(H2_URL),
                provider(null))).isTrue();
        assertThat(KnowledgeEmbeddingConfigurationValidator.validate(properties(false),
                dataSource(POSTGRES_URL), provider(null))).isTrue();
    }

    @Test
    void enabledEmbeddingOnH2FailsFast() {
        assertThatThrownBy(() -> KnowledgeEmbeddingConfigurationValidator.validate(properties(true),
                dataSource(H2_URL), provider(new StubEmbeddingModel())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PostgreSQL")
                .hasMessageContaining("pgvector")
                .as("错误信息不得包含连接串细节")
                .hasMessageNotContaining("password");
    }

    @Test
    void enabledEmbeddingWithoutADataSourceUrlFailsFast() {
        assertThatThrownBy(() -> KnowledgeEmbeddingConfigurationValidator.validate(properties(true),
                dataSource(null), provider(new StubEmbeddingModel())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PostgreSQL");
    }

    @Test
    void enabledEmbeddingWithoutAnEmbeddingModelFailsFast() {
        assertThatThrownBy(() -> KnowledgeEmbeddingConfigurationValidator.validate(properties(true),
                dataSource(POSTGRES_URL), provider(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EmbeddingModel")
                .hasMessageContaining("DASHSCOPE_API_KEY");
    }

    @Test
    void enabledEmbeddingOnPostgresWithAModelPasses() {
        assertThatCode(() -> KnowledgeEmbeddingConfigurationValidator.validate(properties(true),
                dataSource(POSTGRES_URL), provider(new StubEmbeddingModel()))).doesNotThrowAnyException();
    }

    @Test
    void invalidPropertiesFailEvenWhenDisabled() {
        KnowledgeEmbeddingProperties properties = properties(false);
        properties.setDimensions(512);

        assertThatThrownBy(() -> KnowledgeEmbeddingConfigurationValidator.validate(properties,
                dataSource(H2_URL), provider(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dimensions");
    }

    @Test
    void theDisabledPlaceholdersRefuseEveryCall() {
        assertThatThrownBy(() -> new DisabledKnowledgeEmbedding.Port()
                .embedAll(java.util.List.of("文本"), com.flowdesk.domain.knowledge.EmbeddingDescriptor
                        .of("dashscope", "text-embedding-v4")))
                .isInstanceOf(com.flowdesk.application.knowledge.KnowledgeApplicationException.class)
                .extracting(thrown -> ((com.flowdesk.application.knowledge.KnowledgeApplicationException) thrown)
                        .errorCode())
                .isEqualTo(com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode
                        .KNOWLEDGE_EMBEDDING_DISABLED);

        assertThatThrownBy(() -> new DisabledKnowledgeEmbedding.Store()
                .completeIndexing(null, 0L, java.util.List.of()))
                .isInstanceOf(com.flowdesk.application.knowledge.KnowledgeApplicationException.class);
    }

    private static KnowledgeEmbeddingProperties properties(boolean enabled) {
        KnowledgeEmbeddingProperties properties = new KnowledgeEmbeddingProperties();
        properties.setEnabled(enabled);
        return properties;
    }

    private static DataSourceProperties dataSource(String url) {
        DataSourceProperties properties = new DataSourceProperties();
        properties.setUrl(url);
        return properties;
    }

    private static ObjectProvider<EmbeddingModel> provider(EmbeddingModel model) {
        return new ObjectProvider<>() {

            @Override
            public EmbeddingModel getObject() {
                if (model == null) {
                    throw new org.springframework.beans.factory.NoSuchBeanDefinitionException(EmbeddingModel.class);
                }
                return model;
            }

            @Override
            public EmbeddingModel getObject(Object... args) {
                return getObject();
            }

            @Override
            public EmbeddingModel getIfAvailable() {
                return model;
            }

            @Override
            public EmbeddingModel getIfUnique() {
                return model;
            }
        };
    }

    /**
     * 最小可用的 EmbeddingModel 替身：本测试只关心「Bean 是否存在」，不关心其行为。
     */
    private static final class StubEmbeddingModel implements EmbeddingModel {

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            return new EmbeddingResponse(java.util.List.of());
        }

        @Override
        public float[] embed(org.springframework.ai.document.Document document) {
            return new float[1024];
        }
    }
}
