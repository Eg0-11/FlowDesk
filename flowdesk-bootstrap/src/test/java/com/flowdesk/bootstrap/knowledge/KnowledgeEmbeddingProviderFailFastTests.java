package com.flowdesk.bootstrap.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.flowdesk.bootstrap.FlowDeskApplication;
import com.flowdesk.infrastructure.knowledge.embedding.DashScopeKnowledgeEmbeddingAdapter;
import com.flowdesk.infrastructure.knowledge.embedding.KnowledgeEmbeddingConfigurationValidator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;

/**
 * provider 血缘校验的启动期 fail-fast（FD-0010-R2）。
 *
 * <p>{@code flowdesk.knowledge.embedding.provider} 是会被持久化到文档与向量表的<b>血缘字段</b>，
 * 而本版本只有 {@link DashScopeKnowledgeEmbeddingAdapter} 一个向量适配器。
 * 此前 {@code provider=openai} 也能启动：向量实际由 DashScope 生成，来源却被写成 openai。</p>
 *
 * <p>这组测试用<b>真实 Spring 上下文</b>验证：启用状态下 provider 必须是逐字
 * {@value KnowledgeEmbeddingConfigurationValidator#SUPPORTED_PROVIDER}；
 * 非规范值（含大小写变体、前后空格）在创建任何向量适配器之前就启动失败，
 * 且失败信息<b>不</b>回显原始取值；关闭状态下 provider 不受约束（既有行为不变）。</p>
 *
 * <p>与 Key 的测试一样，嵌套上下文不连真实数据库（PostgreSQL URL + Flyway 关闭 + Hikari 不预热）、
 * 不调用真实模型（假 Key），并使用移除了系统环境变量的环境让断言保持确定性。</p>
 */
class KnowledgeEmbeddingProviderFailFastTests {

    private static final String POSTGRES_URL = "jdbc:postgresql://localhost:5432/flowdesk";
    private static final String H2_URL = "jdbc:h2:mem:flowdesk_provider_it"
            + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
    private static final String FAKE_KEY = "test-fake-key-not-a-real-secret";
    private static final String STORAGE_ROOT = "target/knowledge-provider-it";
    private static final String PROVIDER_PROPERTY = "flowdesk.knowledge.embedding.provider";

    @Test
    void anUnsupportedProviderFailsFast() {
        assertThatThrownBy(() -> isolatedBuilder("dashscope-embedding")
                .run(argumentsWith("--DASHSCOPE_API_KEY=" + FAKE_KEY, "--" + PROVIDER_PROPERTY + "=openai")))
                .as("provider=openai 绝不能启动（否则血缘会被写成 openai）")
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("只支持 dashscope")
                .as("不得回显原始 provider")
                .hasMessageNotContaining("openai");
    }

    @Test
    void aCaseVariantOrPaddedProviderFailsFast() {
        for (String unsupported : new String[] { "DashScope", " dashscope ", "dashscope " }) {
            assertThatThrownBy(() -> isolatedBuilder("dashscope-embedding")
                    .run(argumentsWith("--DASHSCOPE_API_KEY=" + FAKE_KEY,
                            "--" + PROVIDER_PROPERTY + "=" + unsupported)))
                    .as("provider=[%s] 必须被拒绝（不得静默 trim 或改大小写）", unsupported)
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("只支持 dashscope")
                    .hasMessageNotContaining(unsupported);
        }
    }

    @Test
    void theCanonicalProviderAssemblesTheContext() {
        try (ConfigurableApplicationContext context = isolatedBuilder("dashscope-embedding")
                .run(argumentsWith("--DASHSCOPE_API_KEY=" + FAKE_KEY))) {

            assertThat(context.getBeanNamesForType(EmbeddingModel.class)).hasSize(1);
            assertThat(context.getBean(EmbeddingModel.class)).isInstanceOf(DashScopeEmbeddingModel.class);
            assertThat(context.getBean(com.flowdesk.application.knowledge.port.out.KnowledgeEmbeddingPort.class))
                    .as("只有规范 provider 才会装配出 DashScope 适配器")
                    .isInstanceOf(DashScopeKnowledgeEmbeddingAdapter.class);
            assertThat(context.getEnvironment().getProperty(PROVIDER_PROPERTY))
                    .isEqualTo(KnowledgeEmbeddingConfigurationValidator.SUPPORTED_PROVIDER);
        }
    }

    @Test
    void theDisabledSwitchLeavesTheProviderUnconstrained() {
        // 关闭状态下 provider 只是一个不会被使用的字段：既有行为不变（不因它启动失败）
        try (ConfigurableApplicationContext context = isolatedBuilder()
                .run("--spring.datasource.url=" + H2_URL,
                        "--flowdesk.knowledge.storage.root=" + STORAGE_ROOT + "-disabled",
                        "--" + PROVIDER_PROPERTY + "=openai",
                        "--server.port=0")) {

            assertThat(context.getEnvironment().getProperty("flowdesk.knowledge.embedding.enabled"))
                    .isEqualTo("false");
            assertThat(context.getBeanNamesForType(EmbeddingModel.class))
                    .as("关闭状态不创建 EmbeddingModel")
                    .isEmpty();
            assertThat(context.getBeanNamesForType(DashScopeKnowledgeEmbeddingAdapter.class))
                    .as("关闭状态不创建任何向量适配器")
                    .isEmpty();
        }
    }

    // ---------- 辅助 ----------

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
