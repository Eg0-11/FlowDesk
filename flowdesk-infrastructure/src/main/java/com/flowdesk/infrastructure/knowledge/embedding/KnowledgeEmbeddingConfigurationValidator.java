package com.flowdesk.infrastructure.knowledge.embedding;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * 向量化配置的启动期校验（FD-0010 / FD-0010-R1）。
 *
 * <p>只有「已启用向量化」时才做额外检查，而且检查必须让应用<b>启动失败</b>，
 * 而不是等到请求打进来才暴露：一个配置错误在生产里表现为「每次索引都 500」，
 * 排查成本远高于启动即失败。</p>
 *
 * <h2>四条环境边界</h2>
 * <table border="1">
 *   <caption>环境矩阵</caption>
 *   <tr><th>环境</th><th>结果</th></tr>
 *   <tr><td>默认（H2，未启用向量化）</td><td>正常启动；索引接口返回 503，向量表不存在；<b>不需要任何 Key</b></td></tr>
 *   <tr><td>仅 deepseek profile</td><td>正常启动；只有 DeepSeek ChatModel；<b>不需要 DashScope Key</b></td></tr>
 *   <tr><td>已启用向量化但不是 PostgreSQL</td><td><b>启动失败</b>：pgvector 只存在于 PostgreSQL，
 *       静默退回 H2 或内存向量库都会让「以为已经索引成功」变成假象</td></tr>
 *   <tr><td>已启用向量化但 DashScope Key 缺失/空/纯空白</td><td><b>启动失败</b>：上游会以 401/403
 *       拒绝每一次调用，而「每次索引都 502」比起动失败难排查得多</td></tr>
 *   <tr><td>PostgreSQL + pgvector + dashscope-embedding + 真实 Key</td><td>真实索引链路可用</td></tr>
 * </table>
 *
 * <h2>为什么要读真实配置值（FD-0010-R1）</h2>
 * <p>FD-0010 只检查「{@link EmbeddingModel} Bean 是否存在」。实测表明这不够：
 * {@code DashScopeEmbeddingAutoConfiguration} 的自动配置条件是
 * {@code matchIfMissing=true}，因此 Bean 可以被创建出来，而可用性取决于<b>连接属性</b>。
 * 因此这里显式读取 DashScope 的连接属性本身。</p>
 *
 * <h2>只承认一条 Key 来源</h2>
 * <p>读取顺序与 starter 的 {@code DashScopeConnectionUtils} 一致：
 * {@code spring.ai.dashscope.api-key} 优先，其次 {@code spring.ai.dashscope.embedding.api-key}
 * （{@code dashscope-embedding} profile 把前者绑定到 {@code ${DASHSCOPE_API_KEY:}}）。</p>
 * <p>刻意<b>不</b>读取 starter 额外支持的 {@code AI_DASHSCOPE_API_KEY} 环境变量：
 * 「Key 从哪来」必须是启动期校验能确定性看到的事实，否则同一份配置在不同机器上会得到
 * 不同结论（本地有该环境变量时启动成功、CI 上启动失败）。这一点在 ADR 0007 中有记录。</p>
 *
 * <h2>错误信息不泄露任何凭证</h2>
 * <p>校验只比较「是否为空」，错误信息里只出现配置名（{@code DASHSCOPE_API_KEY} /
 * {@code spring.ai.dashscope.api-key}）与数据源类型，<b>不</b>包含 Key 内容、用户名、密码或连接串，
 * 也<b>不</b>建立任何连接、<b>不</b>发起任何请求。</p>
 */
public final class KnowledgeEmbeddingConfigurationValidator {

    /** PostgreSQL JDBC URL 前缀。 */
    private static final String POSTGRESQL_URL_PREFIX = "jdbc:postgresql:";

    /** DashScope 通用连接属性（与 starter 的 {@code DashScopeConnectionProperties} 同前缀）。 */
    static final String API_KEY_PROPERTY = "spring.ai.dashscope.api-key";

    /** DashScope 模态级连接属性：starter 在通用属性为空时回退到它。 */
    static final String EMBEDDING_API_KEY_PROPERTY = "spring.ai.dashscope.embedding.api-key";

    /** 本项目的向量提供方：只有它才需要 DashScope Key。 */
    private static final String DASHSCOPE_PROVIDER = "dashscope";

    private KnowledgeEmbeddingConfigurationValidator() {
    }

    /**
     * @param embedding     向量化配置
     * @param dataSource    数据源配置（含 URL）
     * @param embeddingModel EmbeddingModel 提供者（可能不存在）
     * @param environment   配置环境（用于读取 DashScope 连接属性）
     * @return 校验通过标记
     * @throws IllegalStateException 已启用向量化但环境不满足要求
     */
    public static Boolean validate(KnowledgeEmbeddingProperties embedding, DataSourceProperties dataSource,
            ObjectProvider<EmbeddingModel> embeddingModel, Environment environment) {

        embedding.validate();
        if (!embedding.isEnabled()) {
            // 关闭状态：不要求 PostgreSQL，也不要求 EmbeddingModel 与任何 Key
            return Boolean.TRUE;
        }

        String url = dataSource.getUrl();
        if (url == null || !url.startsWith(POSTGRESQL_URL_PREFIX)) {
            throw new IllegalStateException("已启用 flowdesk.knowledge.embedding.enabled，但数据源不是 PostgreSQL："
                    + "切片向量依赖 pgvector，只能在 PostgreSQL 上运行。"
                    + "请使用 --spring.profiles.active=postgres,dashscope-embedding（H2 环境下请保持该开关为 false）");
        }

        // Key 检查必须在「解析 EmbeddingModel」之前：取 EmbeddingModel 会触发 starter 的 Bean 创建，
        // 那条路径上的失败信息由依赖库决定，而我们需要一条稳定、可断言、只提配置名的信息
        requireDashScopeApiKey(embedding, environment);

        if (embeddingModel.getIfAvailable() == null) {
            throw new IllegalStateException("已启用 flowdesk.knowledge.embedding.enabled，但没有可用的 EmbeddingModel："
                    + "请同时启用 dashscope-embedding profile 并配置 DASHSCOPE_API_KEY");
        }
        return Boolean.TRUE;
    }

    /**
     * 校验 DashScope API Key 的<b>真实配置值</b>非空。
     *
     * <p>供所有会创建 {@code EmbeddingModel} 的装配路径复用：无论容器先装配哪个 Bean，
     * 拿到的都是同一条稳定错误信息（而不是依赖库的 {@code Assert} 或 Spring 的占位符解析失败）。</p>
     *
     * @param embedding   向量化配置
     * @param environment 配置环境
     * @throws IllegalStateException 已启用向量化且提供方为 DashScope，但 Key 缺失、为空或纯空白
     */
    public static void requireDashScopeApiKey(KnowledgeEmbeddingProperties embedding, Environment environment) {
        if (!embedding.isEnabled() || !DASHSCOPE_PROVIDER.equals(embedding.getProvider())) {
            // 关闭状态不需要 Key；提供方不是 DashScope 时也不该要求 DashScope 的凭证
            return;
        }
        String apiKey = firstWithText(environment.getProperty(API_KEY_PROPERTY),
                environment.getProperty(EMBEDDING_API_KEY_PROPERTY));
        if (apiKey == null) {
            throw new IllegalStateException("已启用 DashScope 文档向量化，但 API Key 缺失或为空："
                    + "请设置环境变量 DASHSCOPE_API_KEY（dashscope-embedding profile 通过 "
                    + API_KEY_PROPERTY + " 绑定它），或直接配置 " + API_KEY_PROPERTY
                    + " / " + EMBEDDING_API_KEY_PROPERTY + "。"
                    + "空白字符不算有效 Key。");
        }
    }

    /**
     * @param candidates 候选值（按优先级）
     * @return 第一个含非空白文本的值；没有则 {@code null}
     */
    private static String firstWithText(String... candidates) {
        for (String candidate : candidates) {
            if (StringUtils.hasText(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
