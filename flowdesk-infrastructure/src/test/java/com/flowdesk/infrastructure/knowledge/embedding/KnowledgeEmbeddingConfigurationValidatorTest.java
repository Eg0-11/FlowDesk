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
import org.springframework.mock.env.MockEnvironment;

/**
 * 向量化环境校验测试（FD-0010 / FD-0010-R1）。
 *
 * <p>四条环境边界必须能被明确区分，而且<b>配置错误必须让应用启动失败</b>：
 * 关闭时任何数据源、任何 Key 都可以；启用时必须是 PostgreSQL；启用时 DashScope API Key
 * 必须非空；启用时必须有 EmbeddingModel。</p>
 *
 * <p>错误信息里不得出现 Key 内容、数据库密码或连接串细节 —— 只允许出现配置名。</p>
 */
class KnowledgeEmbeddingConfigurationValidatorTest {

    private static final String H2_URL = "jdbc:h2:mem:flowdesk;MODE=PostgreSQL";
    private static final String POSTGRES_URL = "jdbc:postgresql://localhost:5432/flowdesk";
    private static final String POSTGRES_URL_WITH_CREDENTIALS =
            "jdbc:postgresql://localhost:5432/flowdesk?user=flowdesk&password=SENTINEL-DB-PASSWORD";
    private static final String API_KEY_PROPERTY = "spring.ai.dashscope.api-key";
    private static final String EMBEDDING_API_KEY_PROPERTY = "spring.ai.dashscope.embedding.api-key";

    @Test
    void disabledEmbeddingAcceptsAnyDataSourceAndNoModel() {
        assertThat(validate(properties(false), H2_URL, null, environment(null, null))).isTrue();
        assertThat(validate(properties(false), POSTGRES_URL, null, environment(null, null))).isTrue();
    }

    @Test
    void disabledEmbeddingDoesNotRequireAnyApiKey() {
        // 默认 profile / 仅 deepseek profile：完全没有 DashScope Key 也必须能通过校验
        assertThat(validate(properties(false), H2_URL, null, environment("", ""))).isTrue();
        assertThat(validate(properties(false), POSTGRES_URL, new StubEmbeddingModel(), environment(null, null)))
                .isTrue();
    }

    @Test
    void enabledEmbeddingOnH2FailsFast() {
        assertThatThrownBy(() -> validate(properties(true), H2_URL, new StubEmbeddingModel(),
                environment("fake-key", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PostgreSQL")
                .hasMessageContaining("pgvector")
                .as("错误信息不得包含连接串细节")
                .hasMessageNotContaining("password");
    }

    @Test
    void enabledEmbeddingWithoutADataSourceUrlFailsFast() {
        assertThatThrownBy(() -> validate(properties(true), null, new StubEmbeddingModel(),
                environment("fake-key", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PostgreSQL");
    }

    // ---------- DashScope API Key（FD-0010-R1） ----------

    @Test
    void enabledEmbeddingWithoutAnApiKeyFailsFast() {
        assertThatThrownBy(() -> validate(properties(true), POSTGRES_URL, new StubEmbeddingModel(),
                environment(null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DASHSCOPE_API_KEY")
                .hasMessageContaining(API_KEY_PROPERTY);
    }

    @Test
    void blankApiKeysFailFast() {
        for (String blank : new String[] { "", "   ", "\t", "\n  " }) {
            assertThatThrownBy(() -> validate(properties(true), POSTGRES_URL, new StubEmbeddingModel(),
                    environment(blank, null)))
                    .as("Key=[%s] 必须被拒绝", blank)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DASHSCOPE_API_KEY");
        }
    }

    @Test
    void aBlankCommonKeyIsNotRescuedByABlankModalKey() {
        assertThatThrownBy(() -> validate(properties(true), POSTGRES_URL, new StubEmbeddingModel(),
                environment(" ", "  ")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DASHSCOPE_API_KEY");
    }

    @Test
    void theModalLevelApiKeyIsAcceptedAsTheStarterDoes() {
        // starter 的解析顺序是「通用属性优先，其次模态属性」：只配模态属性也必须能启动
        assertThatCode(() -> validate(properties(true), POSTGRES_URL, new StubEmbeddingModel(),
                environment(null, "fake-modal-key"))).doesNotThrowAnyException();
        assertThatCode(() -> validate(properties(true), POSTGRES_URL, new StubEmbeddingModel(),
                environment("fake-common-key", "fake-modal-key"))).doesNotThrowAnyException();
    }

    @Test
    void aNonDashScopeProviderDoesNotRequireTheDashScopeKey() {
        KnowledgeEmbeddingProperties properties = properties(true);
        properties.setProvider("openai");

        assertThatCode(() -> validate(properties, POSTGRES_URL, new StubEmbeddingModel(),
                environment(null, null))).doesNotThrowAnyException();
    }

    @Test
    void theApiKeyFailureMessageLeaksNeitherTheKeyNorTheConnectionString() {
        String keySentinel = "SENTINEL-KEY-VALUE-NEVER-PRINTED";

        assertThatThrownBy(() -> validate(properties(true), POSTGRES_URL_WITH_CREDENTIALS, new StubEmbeddingModel(),
                environment(null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DASHSCOPE_API_KEY")
                .as("不得出现连接串、用户名或密码")
                .hasMessageNotContaining("SENTINEL-DB-PASSWORD")
                .hasMessageNotContaining("jdbc:")
                .hasMessageNotContaining("localhost")
                .as("不得出现 Key 内容")
                .hasMessageNotContaining(keySentinel);
    }

    @Test
    void theStandaloneApiKeyCheckIsUsableWithoutTheModelProvider() {
        // 装配路径（knowledgeEmbeddingPort）会在解析 EmbeddingModel 之前调用它
        assertThatCode(() -> KnowledgeEmbeddingConfigurationValidator
                .requireDashScopeApiKey(properties(true), environment("fake-key", null)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> KnowledgeEmbeddingConfigurationValidator
                .requireDashScopeApiKey(properties(true), environment(" ", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DASHSCOPE_API_KEY");
        assertThatCode(() -> KnowledgeEmbeddingConfigurationValidator
                .requireDashScopeApiKey(properties(false), environment(null, null)))
                .doesNotThrowAnyException();
    }

    // ---------- 其余组合 ----------

    @Test
    void enabledEmbeddingWithoutAnEmbeddingModelFailsFast() {
        assertThatThrownBy(() -> validate(properties(true), POSTGRES_URL, null, environment("fake-key", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EmbeddingModel")
                .hasMessageContaining("DASHSCOPE_API_KEY");
    }

    @Test
    void enabledEmbeddingOnPostgresWithAKeyAndAModelPasses() {
        assertThatCode(() -> validate(properties(true), POSTGRES_URL, new StubEmbeddingModel(),
                environment("fake-key", null))).doesNotThrowAnyException();
    }

    @Test
    void invalidPropertiesFailEvenWhenDisabled() {
        KnowledgeEmbeddingProperties properties = properties(false);
        properties.setDimensions(512);

        assertThatThrownBy(() -> validate(properties, H2_URL, null, environment(null, null)))
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

    // ---------- 辅助 ----------

    private static Boolean validate(KnowledgeEmbeddingProperties properties, String url, EmbeddingModel model,
            MockEnvironment environment) {

        return KnowledgeEmbeddingConfigurationValidator.validate(properties, dataSource(url), provider(model),
                environment);
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

    /**
     * @param commonKey   {@code spring.ai.dashscope.api-key} 的值；{@code null} 表示完全不配置
     * @param embeddingKey {@code spring.ai.dashscope.embedding.api-key} 的值
     * @return 只含这两条属性的环境
     */
    private static MockEnvironment environment(String commonKey, String embeddingKey) {
        MockEnvironment environment = new MockEnvironment();
        if (commonKey != null) {
            environment.setProperty(API_KEY_PROPERTY, commonKey);
        }
        if (embeddingKey != null) {
            environment.setProperty(EMBEDDING_API_KEY_PROPERTY, embeddingKey);
        }
        return environment;
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
