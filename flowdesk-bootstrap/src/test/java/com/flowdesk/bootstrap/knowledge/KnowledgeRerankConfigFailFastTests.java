package com.flowdesk.bootstrap.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowdesk.bootstrap.FlowDeskApplication;
import com.flowdesk.infrastructure.knowledge.rerank.DashScopeKnowledgeRerankAdapter;
import com.flowdesk.infrastructure.knowledge.rerank.DisabledKnowledgeRerank;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;

/**
 * 重排配置的启动期 fail-fast（RAG 6/6）。
 *
 * <p>开关打开就意味着「模型名、Endpoint、Key 都已经确定」；缺任何一项都必须在<b>装配阶段</b>
 * 失败，而不是等到第一次检索才表现为 502。同时，<b>关闭</b>状态必须什么都不要求：
 * 默认环境不配 Endpoint、不配 Key 也能启动，并且拿到的是「拒绝一切」的占位端口。</p>
 *
 * <p>这些用例使用默认 profile 或 {@code dashscope-embedding} profile + 一个不会被连接的
 * PostgreSQL URL（关闭 Flyway、Hikari 不预热），因此<b>不</b>连接数据库、<b>不</b>发出任何网络请求。
 * 环境变量被显式清空，保证断言与构建机器无关。</p>
 */
class KnowledgeRerankConfigFailFastTests {

    private static final String VALID_ENDPOINT = "https://example.invalid/compatible-api/v1/reranks";

    @Test
    void rerankIsOffByDefaultAndRequiresNothing() {
        try (ConfigurableApplicationContext context = builder().run(arguments())) {

            assertThat(context.getEnvironment().getProperty("flowdesk.knowledge.rerank.enabled", Boolean.class))
                    .as("默认必须关闭").isFalse();
            assertThat(context.getBean(
                    com.flowdesk.application.knowledge.port.out.KnowledgeRerankPort.class))
                    .as("关闭时是占位端口：Bean 存在，但任何调用都会失败")
                    .isInstanceOf(DisabledKnowledgeRerank.Port.class);
            assertThat(context.getEnvironment().getProperty("flowdesk.knowledge.rerank.endpoint"))
                    .as("Endpoint 没有默认值").isEmpty();
        }
    }

    @Test
    void enablingRerankWithoutEmbeddingFailsFast() {
        assertThatThrownBy(() -> builder().run(arguments(
                "--flowdesk.knowledge.rerank.enabled=true",
                "--flowdesk.knowledge.rerank.endpoint=" + VALID_ENDPOINT,
                "--flowdesk.knowledge.embedding.enabled=false",
                "--spring.ai.dashscope.api-key=test-fake-key-not-a-real-secret")))
                .as("重排只对向量检索的候选生效：开启重排却没有向量化是一份永远不生效的配置")
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("flowdesk.knowledge.embedding.enabled");
    }

    @Test
    void enablingRerankWithoutAnEndpointFailsFast() {
        assertThatThrownBy(() -> builder().run(dashscopeArguments(
                "--flowdesk.knowledge.rerank.enabled=true",
                "--spring.ai.dashscope.api-key=test-fake-key-not-a-real-secret")))
                .as("Endpoint 含业务空间 ID，必须显式配置")
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("flowdesk.knowledge.rerank.endpoint");
    }

    @Test
    void anEndpointWithAnUnreplacedWorkspacePlaceholderFailsFast() {
        assertThatThrownBy(() -> builder().run(dashscopeArguments(
                "--flowdesk.knowledge.rerank.enabled=true",
                "--flowdesk.knowledge.rerank.endpoint=https://{WorkspaceId}.ap-southeast-1.maas.aliyuncs.com"
                        + "/compatible-api/v1/reranks",
                "--spring.ai.dashscope.api-key=test-fake-key-not-a-real-secret")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("占位符");
    }

    @Test
    void aPlainHttpEndpointOutsideLoopbackFailsFast() {
        // FD-0013-R1：Bearer Key 不得随明文 HTTP 离开本机
        assertThatThrownBy(() -> builder().run(dashscopeArguments(
                "--flowdesk.knowledge.rerank.enabled=true",
                "--flowdesk.knowledge.rerank.endpoint=http://rerank.example.com/compatible-api/v1/reranks",
                "--spring.ai.dashscope.api-key=test-fake-key-not-a-real-secret")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    void aHostThatOnlyStartsWithTheLoopbackPrefixFailsFast() {
        // FD-0013-R2：127.example.com 这类「前缀像回环」的写法不是回环，必须在启动期被拒绝
        for (String hostile : new String[] {
                "http://127.example.com/compatible-api/v1/reranks",
                "http://127.0.0.1.attacker.example/compatible-api/v1/reranks",
                "http://127.999.999.999/compatible-api/v1/reranks",
                "http://127.5/compatible-api/v1/reranks" }) {

            assertThatThrownBy(() -> builder().run(dashscopeArguments(
                    "--flowdesk.knowledge.rerank.enabled=true",
                    "--flowdesk.knowledge.rerank.endpoint=" + hostile,
                    "--spring.ai.dashscope.api-key=test-fake-key-not-a-real-secret")))
                    .as("endpoint=%s", hostile)
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageNotContaining(hostile);
        }
    }

    @Test
    void aLoopbackHttpEndpointStillStarts() {
        // 本地合成端点必须仍然可用：明文 HTTP 只对本机回环放行
        try (ConfigurableApplicationContext context = builder().run(dashscopeArguments(
                "--flowdesk.knowledge.rerank.enabled=true",
                "--flowdesk.knowledge.rerank.endpoint=http://127.0.0.1:8080/compatible-api/v1/reranks",
                "--spring.ai.dashscope.api-key=test-fake-key-not-a-real-secret"))) {

            assertThat(context.getBean(
                    com.flowdesk.application.knowledge.port.out.KnowledgeRerankPort.class))
                    .isInstanceOf(DashScopeKnowledgeRerankAdapter.class);
        }
    }

    @Test
    void anUnsupportedRerankModelFailsFast() {
        assertThatThrownBy(() -> builder().run(dashscopeArguments(
                "--flowdesk.knowledge.rerank.enabled=true",
                "--flowdesk.knowledge.rerank.endpoint=" + VALID_ENDPOINT,
                "--flowdesk.knowledge.rerank.model=gte-rerank-v2",
                "--spring.ai.dashscope.api-key=test-fake-key-not-a-real-secret")))
                .as("本版本只实现 qwen3-rerank 的协议，别的模型名必须启动失败")
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("qwen3-rerank");
    }

    @Test
    void enablingRerankWithoutARerankKeyFailsFast() {
        // 只给向量化的模态级 Key：向量化校验通过，重排的 Key 仍然缺失
        assertThatThrownBy(() -> builder().run(dashscopeArguments(
                "--flowdesk.knowledge.rerank.enabled=true",
                "--flowdesk.knowledge.rerank.endpoint=" + VALID_ENDPOINT,
                "--spring.ai.dashscope.embedding.api-key=test-fake-key-not-a-real-secret")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DASHSCOPE_API_KEY")
                .hasMessageContaining("spring.ai.dashscope.rerank.api-key");
    }

    @Test
    void aCompleteRerankConfigurationAssemblesTheRealAdapter() {
        try (ConfigurableApplicationContext context = builder().run(dashscopeArguments(
                "--flowdesk.knowledge.rerank.enabled=true",
                "--flowdesk.knowledge.rerank.endpoint=" + VALID_ENDPOINT,
                "--flowdesk.knowledge.rerank.connect-timeout=2s",
                "--flowdesk.knowledge.rerank.read-timeout=5s",
                "--spring.ai.dashscope.api-key=test-fake-key-not-a-real-secret"))) {

            assertThat(context.getBean(
                    com.flowdesk.application.knowledge.port.out.KnowledgeRerankPort.class))
                    .as("启用后装配真正的 DashScope 适配器（装配本身不发任何请求）")
                    .isInstanceOf(DashScopeKnowledgeRerankAdapter.class);
            assertThat(context.getEnvironment().getProperty("flowdesk.knowledge.rerank.model"))
                    .isEqualTo("qwen3-rerank");
            assertThat(context.getBeanNamesForType(
                    com.flowdesk.application.knowledge.service.KnowledgeRetrievalService.class))
                    .as("检索用例仍然是唯一的重排入口").hasSize(1);
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
     * 默认 profile 的启动参数：H2 内存库 + 独立存储目录。
     *
     * @param extra 额外参数
     * @return 参数数组
     */
    private static String[] arguments(String... extra) {
        List<String> arguments = new ArrayList<>(List.of(
                "--spring.datasource.url=jdbc:h2:mem:flowdesk_rerank_config_it"
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "--flowdesk.knowledge.storage.root=target/knowledge-rerank-config-it"));
        arguments.addAll(List.of(extra));
        return arguments.toArray(String[]::new);
    }

    /**
     * dashscope-embedding profile 的启动参数：不会连接的 PostgreSQL + 假 Key。
     *
     * @param extra 额外参数
     * @return 参数数组
     */
    private static String[] dashscopeArguments(String... extra) {
        List<String> arguments = new ArrayList<>(List.of(
                "--spring.profiles.active=dashscope-embedding",
                "--spring.datasource.url=jdbc:postgresql://localhost:5432/flowdesk",
                "--spring.datasource.username=flowdesk",
                "--spring.datasource.password=flowdesk",
                "--spring.flyway.enabled=false",
                "--spring.datasource.hikari.initialization-fail-timeout=-1",
                "--flowdesk.knowledge.storage.root=target/knowledge-rerank-config-it"));
        arguments.addAll(List.of(extra));
        return arguments.toArray(String[]::new);
    }
}
