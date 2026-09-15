package com.flowdesk.bootstrap.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.flowdesk.bootstrap.FlowDeskApplication;
import com.flowdesk.infrastructure.knowledge.embedding.DashScopeKnowledgeEmbeddingAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;

/**
 * DashScope API Key 的启动期 fail-fast（FD-0010-R1）。
 *
 * <p>FD-0010 只检查了「{@link EmbeddingModel} Bean 是否存在」，而
 * {@code DashScopeEmbeddingAutoConfiguration} 的自动配置条件是 {@code matchIfMissing=true}：
 * Bean 能被创建出来，可用性却取决于<b>连接属性里的真实 Key</b>。这组测试用<b>真实 Spring 上下文</b>
 * 验证四种情况：缺失、空字符串、纯空白都必须启动失败；提供假 Key 必须能完成装配。</p>
 *
 * <p>所有用例都<b>不会连接真实数据库</b>（PostgreSQL URL + Flyway 关闭 + Hikari 不预热）
 * 也<b>不会调用真实模型</b>（假 Key）。为了把「环境变量未设置」变成确定事实，
 * 嵌套上下文使用一个移除了系统环境变量的 {@link StandardEnvironment}：即使构建机器上
 * 恰好导出了 {@code DASHSCOPE_API_KEY}，这些断言也不会变成「时好时坏」。</p>
 */
class KnowledgeEmbeddingApiKeyFailFastTests {

    private static final String POSTGRES_URL = "jdbc:postgresql://localhost:5432/flowdesk";
    private static final String H2_URL = "jdbc:h2:mem:flowdesk_api_key_it"
            + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
    private static final String FAKE_KEY = "test-fake-key-not-a-real-secret";
    private static final String STORAGE_ROOT = "target/knowledge-api-key-it";

    // ---------- 缺失 / 空 / 纯空白：必须启动失败 ----------

    @Test
    void aMissingApiKeyFailsFast() {
        assertThatThrownBy(() -> isolatedBuilder("dashscope-embedding")
                .run(postgresArguments()))
                .as("Key 完全没配置时必须启动失败")
                .hasMessageContaining("DASHSCOPE_API_KEY")
                .hasMessageContaining("spring.ai.dashscope.api-key");
    }

    @Test
    void anEmptyApiKeyFailsFast() {
        assertThatThrownBy(() -> isolatedBuilder("dashscope-embedding")
                .run(argumentsWith("--DASHSCOPE_API_KEY=")))
                .as("空字符串必须被当成缺失")
                .hasMessageContaining("DASHSCOPE_API_KEY");
    }

    @Test
    void aBlankApiKeyFailsFast() {
        assertThatThrownBy(() -> isolatedBuilder("dashscope-embedding")
                .run(argumentsWith("--DASHSCOPE_API_KEY=    ")))
                .as("纯空白必须被当成缺失")
                .hasMessageContaining("DASHSCOPE_API_KEY");
    }

    @Test
    void theFailureMessageLeaksNeitherCredentialsNorTheConnectionString() {
        assertThatThrownBy(() -> isolatedBuilder("dashscope-embedding")
                .run("--spring.datasource.url=jdbc:postgresql://db-host:5432/flowdesk?password=SENTINEL-DB-PASSWORD",
                        "--spring.datasource.username=flowdesk-user",
                        "--spring.datasource.password=SENTINEL-DB-PASSWORD",
                        "--spring.flyway.enabled=false",
                        "--spring.datasource.hikari.initialization-fail-timeout=-1",
                        "--flowdesk.knowledge.storage.root=" + STORAGE_ROOT,
                        "--server.port=0"))
                .hasMessageContaining("DASHSCOPE_API_KEY")
                .hasMessageNotContaining("SENTINEL-DB-PASSWORD")
                .hasMessageNotContaining("flowdesk-user");
    }

    // ---------- 提供假 Key：必须完成装配 ----------

    @Test
    void aFakeApiKeyAssemblesTheEmbeddingOnlyContext() {
        try (ConfigurableApplicationContext context = isolatedBuilder("dashscope-embedding")
                .run(argumentsWith("--DASHSCOPE_API_KEY=" + FAKE_KEY))) {

            assertThat(context.getBeanNamesForType(EmbeddingModel.class))
                    .as("有且只有一个 EmbeddingModel")
                    .hasSize(1);
            assertThat(context.getBean(EmbeddingModel.class)).isInstanceOf(DashScopeEmbeddingModel.class);
            assertThat(context.getBean(com.flowdesk.application.knowledge.port.out.KnowledgeEmbeddingPort.class))
                    .isInstanceOf(DashScopeKnowledgeEmbeddingAdapter.class);
            // 本 profile 只提供 embedding：Chat 仍由 DeepSeek 负责，因此这里没有 ChatModel
            assertThat(context.getBeanNamesForType(ChatModel.class)).isEmpty();
        }
    }

    @Test
    void theCombinedProfileCreatesOneChatModelAndOneDashScopeEmbeddingModel() {
        try (ConfigurableApplicationContext context = isolatedBuilder(
                "postgres", "deepseek", "dashscope-embedding")
                .run(argumentsWith("--DASHSCOPE_API_KEY=" + FAKE_KEY, "--DEEPSEEK_API_KEY=" + FAKE_KEY))) {

            assertThat(context.getBeanNamesForType(ChatModel.class))
                    .as("组合 profile 里恰好一个 ChatModel（DeepSeek 走 OpenAI 兼容传输）")
                    .hasSize(1);
            assertThat(context.getBean(ChatModel.class)).isInstanceOf(OpenAiChatModel.class);

            assertThat(context.getBeanNamesForType(EmbeddingModel.class))
                    .as("组合 profile 里恰好一个 EmbeddingModel（DashScope）")
                    .hasSize(1);
            assertThat(context.getBean(EmbeddingModel.class)).isInstanceOf(DashScopeEmbeddingModel.class);

            assertThat(context.getEnvironment().getProperty("spring.ai.model.chat")).isEqualTo("openai");
            assertThat(context.getEnvironment().getProperty("spring.ai.model.embedding")).isEqualTo("dashscope");
            assertThat(context.getEnvironment().getProperty("flowdesk.knowledge.embedding.enabled"))
                    .isEqualTo("true");
        }
    }

    // ---------- 默认与仅 DeepSeek：不得要求 DashScope Key ----------

    @Test
    void theDefaultProfileRequiresNoDashScopeKey() {
        try (ConfigurableApplicationContext context = isolatedBuilder()
                .run("--spring.datasource.url=" + H2_URL,
                        "--flowdesk.knowledge.storage.root=" + STORAGE_ROOT + "-default",
                        "--server.port=0")) {

            assertThat(context.getBeanNamesForType(EmbeddingModel.class))
                    .as("默认环境不需要 Key、也不创建 EmbeddingModel")
                    .isEmpty();
            assertThat(context.getEnvironment().getProperty("flowdesk.knowledge.embedding.enabled"))
                    .isEqualTo("false");
        }
    }

    @Test
    void theDeepSeekOnlyProfileRequiresNoDashScopeKey() {
        try (ConfigurableApplicationContext context = isolatedBuilder("deepseek")
                .run("--spring.datasource.url=" + H2_URL,
                        "--spring.ai.openai.api-key=" + FAKE_KEY,
                        "--flowdesk.knowledge.storage.root=" + STORAGE_ROOT + "-deepseek",
                        "--server.port=0")) {

            assertThat(context.getBeanNamesForType(ChatModel.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(EmbeddingModel.class))
                    .as("只有 Chat 的 profile 不得要求 DashScope Key")
                    .isEmpty();
        }
    }

    // ---------- 辅助 ----------

    /** dashscope-embedding + PostgreSQL URL + Flyway 关闭 + Hikari 不预热（不连真实数据库）。 */
    private static String[] postgresArguments() {
        return argumentsWith();
    }

    private static String[] argumentsWith(String... extra) {
        java.util.List<String> arguments = new java.util.ArrayList<>(java.util.List.of(
                "--spring.datasource.url=" + POSTGRES_URL,
                "--spring.datasource.username=flowdesk",
                "--spring.datasource.password=flowdesk",
                "--spring.flyway.enabled=false",
                "--spring.datasource.hikari.initialization-fail-timeout=-1",
                "--flowdesk.knowledge.storage.root=" + STORAGE_ROOT,
                "--server.port=0"));
        arguments.addAll(java.util.List.of(extra));
        return arguments.toArray(String[]::new);
    }

    /**
     * 用「不含系统环境变量」的环境构建嵌套上下文。
     *
     * @param profiles 激活的 profile
     * @return 构建器
     */
    private static SpringApplicationBuilder isolatedBuilder(String... profiles) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        SpringApplicationBuilder builder = new SpringApplicationBuilder(FlowDeskApplication.class)
                .environment(environment);
        return profiles.length == 0 ? builder : builder.profiles(profiles);
    }
}
