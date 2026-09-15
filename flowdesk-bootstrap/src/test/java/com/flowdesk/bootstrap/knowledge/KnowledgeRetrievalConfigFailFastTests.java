package com.flowdesk.bootstrap.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowdesk.application.knowledge.service.KnowledgeRetrievalService;
import com.flowdesk.bootstrap.FlowDeskApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;

/**
 * 检索配置的启动期 fail-fast（FD-0011-R1）。
 *
 * <p>公开输入上限是任务契约的一部分（query ≤ 2000 个 code point、topK ∈ 1..20）：
 * 配置只能<b>收紧</b>，不能扩大。把 {@code max-top-k} 或 {@code max-query-code-points}
 * 调到超出契约的值，必须在装配阶段让应用启动失败 —— 否则公开契约会被一份配置文件悄悄放大。</p>
 *
 * <p>这些用例使用默认 profile + H2：不连真实数据库、不需要任何 API Key、不访问外网。</p>
 */
class KnowledgeRetrievalConfigFailFastTests {

    @Test
    void aMaxTopKBeyondThePublicContractFailsFast() {
        assertThatThrownBy(() -> builder().run(arguments("--flowdesk.knowledge.retrieval.max-top-k=21")))
                .as("max-top-k=21 超出公开上限 20，必须启动失败")
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("max-top-k")
                .hasMessageContaining("20");
    }

    @Test
    void aMaxQueryCodePointsBeyondThePublicContractFailsFast() {
        assertThatThrownBy(() -> builder().run(arguments(
                "--flowdesk.knowledge.retrieval.max-query-code-points=2001")))
                .as("max-query-code-points=2001 超出公开上限 2000，必须启动失败")
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("max-query-code-points")
                .hasMessageContaining("2000");
    }

    @Test
    void aDefaultTopKLargerThanTheLimitFailsFast() {
        assertThatThrownBy(() -> builder().run(arguments(
                "--flowdesk.knowledge.retrieval.default-top-k=6",
                "--flowdesk.knowledge.retrieval.max-top-k=5")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("default-top-k");
    }

    @Test
    void aTightenedConfigurationStillStarts() {
        try (ConfigurableApplicationContext context = builder().run(arguments(
                "--flowdesk.knowledge.retrieval.max-top-k=3",
                "--flowdesk.knowledge.retrieval.default-top-k=2",
                "--flowdesk.knowledge.retrieval.max-query-code-points=500",
                "--flowdesk.knowledge.retrieval.default-min-score=0.5",
                "--server.port=0"))) {

            assertThat(context.getBean(KnowledgeRetrievalService.class)).isNotNull();
            assertThat(context.getBeanNamesForType(
                    com.flowdesk.application.knowledge.port.in.RetrieveKnowledgeUseCase.class))
                    .as("收紧配置不影响用例与接口的装配").hasSize(1);
            assertThat(context.getEnvironment().getProperty("flowdesk.knowledge.retrieval.max-top-k"))
                    .isEqualTo("3");
            assertThat(KnowledgeRetrievalService.MAX_TOP_K_LIMIT).isEqualTo(20);
            assertThat(KnowledgeRetrievalService.MAX_QUERY_CODE_POINTS_LIMIT).isEqualTo(2000);
        }
    }

    /**
     * @return 默认 profile 的嵌套上下文构建器（不继承构建机器的环境变量，保证断言确定性）
     */
    private static SpringApplicationBuilder builder() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        return new SpringApplicationBuilder(FlowDeskApplication.class).environment(environment);
    }

    /**
     * 通用启动参数：H2 内存库 + 独立的存储目录。
     *
     * @param extra 额外参数
     * @return 参数数组
     */
    private static String[] arguments(String... extra) {
        String database = "jdbc:h2:mem:flowdesk_retrieval_config_it"
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        java.util.List<String> arguments = new java.util.ArrayList<>(java.util.List.of(
                "--spring.datasource.url=" + database,
                "--flowdesk.knowledge.storage.root=target/knowledge-retrieval-config-it"));
        arguments.addAll(java.util.List.of(extra));
        return arguments.toArray(String[]::new);
    }
}
