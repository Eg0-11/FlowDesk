package com.flowdesk.infrastructure.knowledge.rerank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 重排配置校验测试（RAG 6/6）。
 *
 * <p>三件事必须被锁死：默认<b>关闭</b>且关闭时不要求任何 Endpoint/Key；开启时模型名必须逐字
 * 等于 {@code qwen3-rerank}；Endpoint 必须显式配置且不含未替换的业务空间占位符。</p>
 */
class KnowledgeRerankPropertiesTest {

    private static KnowledgeRerankProperties enabled() {
        KnowledgeRerankProperties properties = new KnowledgeRerankProperties();
        properties.setEnabled(true);
        properties.setEndpoint("https://example.invalid/compatible-api/v1/reranks");
        return properties;
    }

    @Test
    void theDefaultsAreDisabledWithTheSupportedModelAndNoEndpoint() {
        KnowledgeRerankProperties properties = new KnowledgeRerankProperties();

        assertThat(properties.isEnabled()).as("默认必须关闭").isFalse();
        assertThat(properties.getModel()).isEqualTo(KnowledgeRerankProperties.SUPPORTED_MODEL);
        assertThat(properties.getEndpoint())
                .as("Endpoint 含业务空间 ID，因此没有默认值")
                .isEmpty();
        assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.getReadTimeout()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void theDefaultConfigurationValidatesWithoutAnyEndpointOrKey() {
        assertThatCode(() -> new KnowledgeRerankProperties().validate()).doesNotThrowAnyException();
    }

    @Test
    void aDisabledConfigurationDoesNotNeedAnEndpoint() {
        KnowledgeRerankProperties properties = new KnowledgeRerankProperties();
        properties.setEndpoint("");

        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void anEnabledConfigurationNeedsAnEndpoint() {
        KnowledgeRerankProperties properties = enabled();
        properties.setEndpoint("");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flowdesk.knowledge.rerank.endpoint");
    }

    @Test
    void anEndpointWithAnUnreplacedWorkspacePlaceholderIsRejected() {
        KnowledgeRerankProperties properties = enabled();
        properties.setEndpoint("https://{WorkspaceId}.ap-southeast-1.maas.aliyuncs.com/compatible-api/v1/reranks");

        assertThatThrownBy(properties::validate)
                .as("占位符没替换说明业务空间 ID 还没有真正配置")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("占位符");
    }

    @Test
    void anEndpointMustBeAnAbsoluteHttpUrlWithoutCredentialsQueryOrFragment() {
        for (String invalid : new String[] {
                "dashscope.aliyuncs.com/reranks",
                "ftp://example.invalid/reranks",
                "https:///reranks",
                "https://user:password@example.invalid/reranks",
                "https://example.invalid/reranks?api-key=sentinel",
                "https://example.invalid/reranks#fragment",
                "not a uri" }) {

            KnowledgeRerankProperties properties = enabled();
            properties.setEndpoint(invalid);

            assertThatThrownBy(properties::validate)
                    .as("endpoint=[%s]", invalid)
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(properties::validate)
                    .as("错误信息不得回显 Endpoint 原值")
                    .hasMessageNotContaining(invalid);
        }
    }

    @Test
    void theModelMustBeExactlyTheSupportedValueWhenEnabled() {
        for (String model : new String[] { "gte-rerank-v2", "Qwen3-Rerank", " qwen3-rerank", "" }) {
            KnowledgeRerankProperties properties = enabled();
            properties.setModel(model);

            assertThatThrownBy(properties::validate).as("model=[%s]", model)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void aDisabledConfigurationStillRejectsAnUnsupportedModelNameIfItIsBlank() {
        KnowledgeRerankProperties properties = new KnowledgeRerankProperties();
        properties.setModel("  ");

        assertThatThrownBy(properties::validate)
                .as("模型名不能是空白：它会被写进检索结果用于审计")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("model");
    }

    @Test
    void timeoutsMustBePositiveAndBounded() {
        KnowledgeRerankProperties zeroConnect = enabled();
        zeroConnect.setConnectTimeout(Duration.ZERO);
        assertThatThrownBy(zeroConnect::validate).hasMessageContaining("connect-timeout");

        KnowledgeRerankProperties negativeRead = enabled();
        negativeRead.setReadTimeout(Duration.ofSeconds(-1));
        assertThatThrownBy(negativeRead::validate).hasMessageContaining("read-timeout");

        KnowledgeRerankProperties hugeConnect = enabled();
        hugeConnect.setConnectTimeout(KnowledgeRerankProperties.MAX_CONNECT_TIMEOUT.plusSeconds(1));
        assertThatThrownBy(hugeConnect::validate).hasMessageContaining("connect-timeout");

        KnowledgeRerankProperties hugeRead = enabled();
        hugeRead.setReadTimeout(KnowledgeRerankProperties.MAX_READ_TIMEOUT.plusSeconds(1));
        assertThatThrownBy(hugeRead::validate).hasMessageContaining("read-timeout");
    }
}
