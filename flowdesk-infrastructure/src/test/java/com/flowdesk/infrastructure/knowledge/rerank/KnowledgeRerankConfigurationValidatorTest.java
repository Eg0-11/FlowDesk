package com.flowdesk.infrastructure.knowledge.rerank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 重排启动期校验测试（RAG 6/6）。
 *
 * <p>与向量化一致：开启开关就意味着「Key、模型名、Endpoint 都已经确定」，
 * 缺任何一项都必须在装配阶段失败，而不是等到第一次检索才表现为 502。</p>
 */
class KnowledgeRerankConfigurationValidatorTest {

    private static final String COMMON_KEY = "spring.ai.dashscope.api-key";

    private static final String MODAL_KEY = "spring.ai.dashscope.rerank.api-key";

    private static KnowledgeRerankProperties enabled() {
        KnowledgeRerankProperties properties = new KnowledgeRerankProperties();
        properties.setEnabled(true);
        properties.setEndpoint("https://example.invalid/compatible-api/v1/reranks");
        return properties;
    }

    // ---------- Key 的优先级 ----------

    @Test
    void theModalKeyWinsOverTheCommonOne() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(MODAL_KEY, "modal-key")
                .withProperty(COMMON_KEY, "common-key");

        assertThat(KnowledgeRerankConfigurationValidator.resolveApiKey(environment)).isEqualTo("modal-key");
    }

    @Test
    void theCommonKeyIsTheFallback() {
        MockEnvironment environment = new MockEnvironment().withProperty(COMMON_KEY, "common-key");

        assertThat(KnowledgeRerankConfigurationValidator.resolveApiKey(environment)).isEqualTo("common-key");
    }

    @Test
    void aBlankModalKeyFallsBackToTheCommonOne() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(MODAL_KEY, "   ")
                .withProperty(COMMON_KEY, "common-key");

        assertThat(KnowledgeRerankConfigurationValidator.resolveApiKey(environment)).isEqualTo("common-key");
    }

    @Test
    void withNoKeyAtAllTheResolverReturnsNull() {
        assertThat(KnowledgeRerankConfigurationValidator.resolveApiKey(new MockEnvironment())).isNull();
    }

    // ---------- 启用时必须校验 Key ----------

    @Test
    void enablingRerankWithoutAKeyFailsFast() {
        assertThatThrownBy(() -> KnowledgeRerankConfigurationValidator.requireDashScopeApiKey(
                enabled(), new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DASHSCOPE_API_KEY")
                .hasMessageContaining(MODAL_KEY);
    }

    @Test
    void aBlankKeyCountsAsMissing() {
        MockEnvironment environment = new MockEnvironment().withProperty(COMMON_KEY, "   ");

        assertThatThrownBy(() -> KnowledgeRerankConfigurationValidator.requireDashScopeApiKey(
                enabled(), environment))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aDisabledConfigurationNeedsNoKey() {
        assertThatCode(() -> KnowledgeRerankConfigurationValidator.requireDashScopeApiKey(
                new KnowledgeRerankProperties(), new MockEnvironment()))
                .doesNotThrowAnyException();
    }

    @Test
    void anEnabledConfigurationWithAKeyPasses() {
        MockEnvironment environment = new MockEnvironment().withProperty(COMMON_KEY, "test-fake-key");

        assertThatCode(() -> KnowledgeRerankConfigurationValidator.requireDashScopeApiKey(enabled(), environment))
                .doesNotThrowAnyException();
    }

    @Test
    void theErrorMessagesNeverCarryCredentials() {
        String sentinelKey = "sentinel-key-value";
        MockEnvironment environment = new MockEnvironment().withProperty(MODAL_KEY, sentinelKey);

        // 1) Endpoint 配置错误：错误信息里不得出现任何 Key 值
        KnowledgeRerankProperties missingEndpoint = enabled();
        missingEndpoint.setEndpoint("");
        assertThatThrownBy(() -> KnowledgeRerankConfigurationValidator.requireValidConfiguration(missingEndpoint))
                .hasMessageNotContaining(sentinelKey);

        // 2) Key 缺失错误：只提配置名与环境变量名，不回显任何取值
        assertThatThrownBy(() -> KnowledgeRerankConfigurationValidator.requireDashScopeApiKey(
                enabled(), new MockEnvironment().withProperty(MODAL_KEY, "   ")))
                .hasMessageContaining(MODAL_KEY)
                .hasMessageContaining(COMMON_KEY)
                .hasMessageNotContaining(sentinelKey);
    }

    // ---------- 与向量化的一致性 ----------

    @Test
    void enablingRerankWithoutEmbeddingFailsFast() {
        assertThatThrownBy(() -> KnowledgeRerankConfigurationValidator.requireEmbeddingEnabled(enabled(), false))
                .as("重排只对向量检索的候选生效，配置成「开启但没有向量化」永远不会被执行")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flowdesk.knowledge.embedding.enabled");
    }

    @Test
    void enablingRerankTogetherWithEmbeddingPasses() {
        assertThatCode(() -> KnowledgeRerankConfigurationValidator.requireEmbeddingEnabled(enabled(), true))
                .doesNotThrowAnyException();
    }

    @Test
    void aDisabledConfigurationIsIndependentOfEmbedding() {
        assertThatCode(() -> KnowledgeRerankConfigurationValidator.requireEmbeddingEnabled(
                new KnowledgeRerankProperties(), false))
                .doesNotThrowAnyException();
    }
}
