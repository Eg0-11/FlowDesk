package com.flowdesk.bootstrap.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.flowdesk.infrastructure.knowledge.embedding.DashScopeKnowledgeEmbeddingAdapter;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * dashscope-embedding profile 的装配契约（FD-0010）。
 *
 * <p>本测试用<b>假 Key</b> 与一个不会被连接的 PostgreSQL URL（关闭 Flyway、
 * Hikari 不预热），因此只验证「Bean 怎么装配」，不会发出任何真实请求、
 * 也不会连接任何数据库。真实调用状态在交付报告中如实标注。</p>
 */
@SpringBootTest(properties = {
        "spring.profiles.active=dashscope-embedding",
        "DASHSCOPE_API_KEY=test-fake-key-not-a-real-secret",
        "spring.datasource.url=jdbc:postgresql://localhost:5432/flowdesk",
        "spring.datasource.username=flowdesk",
        "spring.datasource.password=flowdesk",
        "spring.flyway.enabled=false",
        "spring.datasource.hikari.initialization-fail-timeout=-1",
        "flowdesk.knowledge.storage.root=target/flowdesk-embedding-dashscope-ctx"
})
class KnowledgeEmbeddingDashScopeContextTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void createsExactlyOneDashScopeEmbeddingModel() {
        String[] names = this.applicationContext.getBeanNamesForType(EmbeddingModel.class);

        assertThat(names).as("有且只有一个 EmbeddingModel").hasSize(1);
        assertThat(this.applicationContext.getBean(EmbeddingModel.class))
                .isInstanceOf(DashScopeEmbeddingModel.class);
    }

    @Test
    void replacesTheDisabledPortWithTheRealAdapter() {
        assertThat(this.applicationContext.getBean(
                com.flowdesk.application.knowledge.port.out.KnowledgeEmbeddingPort.class))
                .isInstanceOf(DashScopeKnowledgeEmbeddingAdapter.class);
        assertThat(this.applicationContext.getBeanNamesForType(DashScopeKnowledgeEmbeddingAdapter.class))
                .hasSize(1);
    }

    @Test
    void doesNotCreateADashScopeChatModel() {
        assertThat(this.applicationContext.getBeanNamesForType(ChatModel.class))
                .as("本 profile 只启用 embedding：不得创建 DashScope ChatModel（DeepSeek 负责 Chat）")
                .isEmpty();
    }

    @Test
    void theEmbeddingEndpointConfigurationMatchesTheDocumentedContract() {
        assertThat(this.applicationContext.getEnvironment().getProperty("spring.ai.model.embedding"))
                .isEqualTo("dashscope");
        assertThat(this.applicationContext.getEnvironment()
                .getProperty("spring.ai.dashscope.embedding.options.model")).isEqualTo("text-embedding-v4");
        assertThat(this.applicationContext.getEnvironment()
                .getProperty("spring.ai.dashscope.embedding.options.dimensions")).isEqualTo("1024");
        assertThat(this.applicationContext.getEnvironment()
                .getProperty("flowdesk.knowledge.embedding.batch-size")).isEqualTo("10");
        assertThat(this.applicationContext.getEnvironment()
                .getProperty("spring.ai.model.image")).isEqualTo("none");
        assertThat(this.applicationContext.getEnvironment()
                .getProperty("spring.ai.model.rerank")).isEqualTo("none");
    }

    @Test
    void theShippedProfileReadsTheApiKeyFromTheEnvironmentOnly() throws Exception {
        String profile;
        try (java.io.InputStream stream = getClass()
                .getResourceAsStream("/application-dashscope-embedding.yml")) {
            profile = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(profile)
                // FD-0010-R1：带空默认值，让「Key 缺失」由我们自己的启动期校验统一处理
                .contains("api-key: ${DASHSCOPE_API_KEY:}")
                .contains("model: text-embedding-v4")
                .contains("dimensions: 1024")
                .contains("batch-size: 10")
                .contains("embedding: dashscope")
                .contains("read-timeout")
                .contains("retry")
                // FD-0010-R1：依赖库的 logger 必须关闭（它会把切片正文写进日志）
                .contains("logging:")
                .contains("com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel: \"OFF\"");

        // 仓库中不得出现真实 Key：整个 profile 里只有一处 api-key，且必须是环境变量占位符
        assertThat(countOccurrences(profile, "api-key")).isEqualTo(1);
    }

    @Test
    void enabledEmbeddingOnH2FailsFast() {
        // 真实校验：启用向量化但数据源是 H2 时必须启动失败，而不是静默退回内存向量库
        assertThatThrownBy(() -> new SpringApplicationBuilder(
                com.flowdesk.bootstrap.FlowDeskApplication.class)
                .profiles("dashscope-embedding")
                .run("--spring.datasource.url=jdbc:h2:mem:flowdesk_embedding_h2_fail"
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                        "--spring.datasource.username=sa",
                        "--spring.datasource.password=",
                        "--DASHSCOPE_API_KEY=test-fake-key-not-a-real-secret",
                        "--flowdesk.knowledge.storage.root=target/flowdesk-embedding-h2-fail",
                        "--server.port=0"))
                .hasMessageContaining("PostgreSQL")
                .hasMessageContaining("pgvector");
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
