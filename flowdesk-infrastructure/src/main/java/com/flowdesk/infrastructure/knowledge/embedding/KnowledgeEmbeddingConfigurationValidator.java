package com.flowdesk.infrastructure.knowledge.embedding;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * 向量化配置的启动期校验（FD-0010 / FD-0010-R1 / FD-0010-R2）。
 *
 * <p>只有「已启用向量化」时才做额外检查，而且检查必须让应用<b>启动失败</b>，
 * 而不是等到请求打进来才暴露：一个配置错误在生产里表现为「每次索引都 500」，
 * 排查成本远高于启动即失败。</p>
 *
 * <h2>五条环境边界</h2>
 * <table border="1">
 *   <caption>环境矩阵</caption>
 *   <tr><th>环境</th><th>结果</th></tr>
 *   <tr><td>默认（H2，未启用向量化）</td><td>正常启动；索引接口返回 503，向量表不存在；<b>不需要任何 Key</b></td></tr>
 *   <tr><td>仅 deepseek profile</td><td>正常启动；只有 DeepSeek ChatModel；<b>不需要 DashScope Key</b></td></tr>
 *   <tr><td>已启用向量化但 provider 不是规范值 {@value #SUPPORTED_PROVIDER}</td>
 *       <td><b>启动失败</b>：当前版本只有一个向量适配器（DashScope），
 *       接受别的取值等于把「实际由 DashScope 生成」的向量标注成别的来源</td></tr>
 *   <tr><td>已启用向量化但不是 PostgreSQL</td><td><b>启动失败</b>：pgvector 只存在于 PostgreSQL，
 *       静默退回 H2 或内存向量库都会让「以为已经索引成功」变成假象</td></tr>
 *   <tr><td>已启用向量化但 DashScope Key 缺失/空/纯空白</td><td><b>启动失败</b>：上游会以 401/403
 *       拒绝每一次调用，而「每次索引都 502」比起动失败难排查得多</td></tr>
 *   <tr><td>PostgreSQL + pgvector + dashscope-embedding + 真实 Key</td><td>真实索引链路可用</td></tr>
 * </table>
 *
 * <h2>provider 是血缘字段，不是自由文本</h2>
 * <p>{@code flowdesk.knowledge.embedding.provider} 会被持久化到
 * {@code knowledge_documents.embedding_provider} 与向量表的 {@code provider} 列，
 * 检索阶段据此判断「库里的向量由谁生成」。而当前版本只实现
 * {@link DashScopeKnowledgeEmbeddingAdapter}，因此启用状态下它<b>只允许</b>规范值
 * {@value #SUPPORTED_PROVIDER}：</p>
 * <ul>
 *   <li>{@code null}、空串、纯空白、{@code openai}、{@code DashScope}（大小写不同）、
 *       {@code " dashscope "}（带前后空格）全部拒绝；</li>
 *   <li><b>不</b>做 trim、<b>不</b>做大小写归一 —— 静默纠正会让配置文件里写的东西
 *       与真正生效（并被持久化）的东西不一致，而那正是血缘错配的来源；</li>
 *   <li>开关关闭时不做该检查：此时 provider 只是一个不会被使用的字段（既有行为不变）。</li>
 * </ul>
 *
 * <h2>为什么必须读真实配置值（FD-0010-R1）</h2>
 * <p>FD-0010 只检查「{@link EmbeddingModel} Bean 是否存在」。实测表明这不够：
 * {@code DashScopeEmbeddingAutoConfiguration} 的自动配置条件是
 * {@code matchIfMissing=true}，因此 Bean 可以被创建出来，而可用性取决于<b>连接属性</b>。
 * 因此这里显式读取 DashScope 的连接属性本身。</p>
 *
 * <h2>Key 的优先级（与依赖 1.1.2.2 的真实行为一致，FD-0010-R2 修正）</h2>
 * <p>{@code DashScopeConnectionUtils.resolveConnectionProperties(common, model, taskName)}
 * 的实际顺序是：<b>先看模态级属性 {@code spring.ai.dashscope.embedding.api-key}，
 * 它有文本时优先；否则回退到通用属性 {@code spring.ai.dashscope.api-key}</b>
 * （{@code base-url} 与 {@code workspace-id} 同样是「模态优先、通用兜底」）。</p>
 * <p>只有在选出的配置值为 {@code null} 时，{@code DashScopeConnectionUtils} 才会尝试
 * {@code AI_DASHSCOPE_API_KEY} 环境变量。<b>本项目不承认这条来源</b>：
 * {@code dashscope-embedding} profile 把通用属性绑定为 {@code ${DASHSCOPE_API_KEY:}}，
 * 未设置时它的值是空字符串而不是 {@code null}（空字符串本来也不会触发那条回退），
 * 而空值由本项目的校验器直接拒绝。理由是「Key 从哪来」必须是启动期校验能确定性看到的事实，
 * 否则同一份配置在不同机器上会得到不同结论。这一点在 ADR 0007 中有记录。</p>
 *
 * <h2>错误信息不泄露任何凭证</h2>
 * <p>校验只比较「是否为空/是否为规范值」，错误信息里只出现配置名与受支持的取值，
 * <b>不</b>回显原始 provider、Key 内容、用户名、密码或连接串，
 * 也<b>不</b>建立任何连接、<b>不</b>发起任何请求。</p>
 */
public final class KnowledgeEmbeddingConfigurationValidator {

    /** PostgreSQL JDBC URL 前缀。 */
    private static final String POSTGRESQL_URL_PREFIX = "jdbc:postgresql:";

    /**
     * 本版本唯一受支持的向量提供方，也是 {@code flowdesk.knowledge.embedding.provider}
     * 必须逐字写成的规范值。
     */
    public static final String SUPPORTED_PROVIDER = "dashscope";

    /** DashScope 模态级连接属性：依赖库解析时<b>优先</b>取它。 */
    static final String EMBEDDING_API_KEY_PROPERTY = "spring.ai.dashscope.embedding.api-key";

    /** DashScope 通用连接属性：模态级属性没有文本时回退到它。 */
    static final String API_KEY_PROPERTY = "spring.ai.dashscope.api-key";

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

        // provider 先于一切检查：它决定「我们到底支持哪个适配器」，
        // 而且必须在任何一步去解析 EmbeddingModel（那是依赖库的 Bean）之前确定
        requireSupportedProvider(embedding);
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
     * 校验 provider 是本版本唯一支持的规范值（FD-0010-R2）。
     *
     * <p>该方法是<b>所有会创建向量适配器的装配路径的共同前置条件</b>：
     * 启动期校验 Bean 与 {@code knowledgeEmbeddingPort} 都调用它，
     * 因此无论容器以什么顺序装配 Bean，「provider 不是 {@value #SUPPORTED_PROVIDER}」时都不会
     * 创建出 {@link DashScopeKnowledgeEmbeddingAdapter}。</p>
     *
     * <p>比较是逐字比较：不做 trim、不做大小写归一，{@code null} 与纯空白同样被拒绝。
     * 错误信息只说当前版本只支持 {@value #SUPPORTED_PROVIDER}，
     * <b>不</b>回显原始取值。</p>
     *
     * @param embedding 向量化配置
     * @throws IllegalStateException 已启用向量化但 provider 不是规范值
     */
    public static void requireSupportedProvider(KnowledgeEmbeddingProperties embedding) {
        if (!embedding.isEnabled()) {
            // 关闭状态：provider 不会被任何适配器使用，保持既有行为（不因它启动失败）
            return;
        }
        if (!SUPPORTED_PROVIDER.equals(embedding.getProvider())) {
            throw new IllegalStateException("已启用 flowdesk.knowledge.embedding.enabled，但 "
                    + "flowdesk.knowledge.embedding.provider 不是受支持的取值：当前版本只支持 "
                    + SUPPORTED_PROVIDER + "（必须逐字写成 " + SUPPORTED_PROVIDER
                    + "，不接受大小写变体、前后空格或空值）。"
                    + "请把该属性设为 " + SUPPORTED_PROVIDER
                    + "，或关闭 flowdesk.knowledge.embedding.enabled。");
        }
    }

    /**
     * 校验 DashScope API Key 的<b>真实配置值</b>非空。
     *
     * <p>供所有会创建 {@code EmbeddingModel} 的装配路径复用：无论容器先装配哪个 Bean，
     * 拿到的都是同一条稳定错误信息（而不是依赖库的 {@code Assert} 或 Spring 的占位符解析失败）。</p>
     *
     * <p>只接受 DashScope 的两条静态连接属性（见 {@link #resolveApiKey(Environment)} 的优先级说明），
     * 刻意<b>不</b>读取 {@code AI_DASHSCOPE_API_KEY} 环境变量。</p>
     *
     * @param embedding   向量化配置
     * @param environment 配置环境
     * @throws IllegalStateException 已启用向量化且提供方为 DashScope，但 Key 缺失、为空或纯空白
     */
    public static void requireDashScopeApiKey(KnowledgeEmbeddingProperties embedding, Environment environment) {
        if (!embedding.isEnabled() || !SUPPORTED_PROVIDER.equals(embedding.getProvider())) {
            // 关闭状态不需要 Key；提供方不是 DashScope 时由 requireSupportedProvider 负责拒绝
            return;
        }
        if (resolveApiKey(environment) == null) {
            throw new IllegalStateException("已启用 DashScope 文档向量化，但 API Key 缺失或为空："
                    + "请设置环境变量 DASHSCOPE_API_KEY（dashscope-embedding profile 通过 "
                    + API_KEY_PROPERTY + " 绑定它），或直接配置 " + EMBEDDING_API_KEY_PROPERTY
                    + "（模态级，优先）与 " + API_KEY_PROPERTY + "（通用，兜底）。"
                    + "空白字符不算有效 Key。");
        }
    }

    /**
     * 按依赖库 1.1.2.2 的真实顺序解析 DashScope API Key。
     *
     * <p>{@code DashScopeConnectionUtils.resolveConnectionProperties(common, model, taskName)} 是
     * <b>模态级优先、通用兜底</b>：先取 {@code spring.ai.dashscope.embedding.api-key}，
     * 只在它没有文本时才用 {@code spring.ai.dashscope.api-key}。</p>
     *
     * <p>两条都没有文本时返回 {@code null}（调用方据此判定「缺失、空或纯空白」）。
     * 刻意不读取 {@code AI_DASHSCOPE_API_KEY}：依赖库只在选出的配置值为 {@code null} 时才回退到它，
     * 而本项目要求「Key 的来源必须能被启动期校验确定性看到」。</p>
     *
     * @param environment 配置环境
     * @return 生效的 Key 值；没有任何一条含非空白文本时为 {@code null}
     */
    static String resolveApiKey(Environment environment) {
        String modal = environment.getProperty(EMBEDDING_API_KEY_PROPERTY);
        if (StringUtils.hasText(modal)) {
            return modal;
        }
        String common = environment.getProperty(API_KEY_PROPERTY);
        return StringUtils.hasText(common) ? common : null;
    }
}
