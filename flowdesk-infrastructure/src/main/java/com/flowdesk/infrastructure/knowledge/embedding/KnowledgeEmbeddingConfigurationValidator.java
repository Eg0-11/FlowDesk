package com.flowdesk.infrastructure.knowledge.embedding;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;

/**
 * 向量化配置的启动期校验（FD-0010）。
 *
 * <p>只有「已启用向量化」时才做额外检查，而且检查必须让应用<b>启动失败</b>，
 * 而不是等到请求打进来才暴露：一个配置错误在生产里表现为「每次索引都 500」，
 * 排查成本远高于启动即失败。</p>
 *
 * <h2>三条环境必须清楚区分</h2>
 * <table border="1">
 *   <caption>环境矩阵</caption>
 *   <tr><th>环境</th><th>结果</th></tr>
 *   <tr><td>默认（H2，未启用向量化）</td><td>正常启动；索引接口返回 503，向量表不存在</td></tr>
 *   <tr><td>已启用向量化但不是 PostgreSQL</td><td><b>启动失败</b>：pgvector 只存在于 PostgreSQL，
 *       静默退回 H2 或内存向量库都会让「以为已经索引成功」变成假象</td></tr>
 *   <tr><td>已启用向量化但没有 EmbeddingModel</td><td><b>启动失败</b>：缺少
 *       {@code dashscope-embedding} profile 或 API Key</td></tr>
 *   <tr><td>PostgreSQL + pgvector + dashscope-embedding</td><td>真实索引链路可用</td></tr>
 * </table>
 *
 * <p>校验只看数据源 URL 前缀与 Bean 是否可用，<b>不</b>建立连接、<b>不</b>读取任何密钥，
 * 因此错误信息里不会出现用户名、密码或 API Key。</p>
 */
public final class KnowledgeEmbeddingConfigurationValidator {

    /** PostgreSQL JDBC URL 前缀。 */
    private static final String POSTGRESQL_URL_PREFIX = "jdbc:postgresql:";

    private KnowledgeEmbeddingConfigurationValidator() {
    }

    /**
     * @param embedding     向量化配置
     * @param dataSource    数据源配置（含 URL）
     * @param embeddingModel EmbeddingModel 提供者（可能不存在）
     * @return 校验通过标记
     * @throws IllegalStateException 已启用向量化但环境不满足要求
     */
    public static Boolean validate(KnowledgeEmbeddingProperties embedding, DataSourceProperties dataSource,
            ObjectProvider<EmbeddingModel> embeddingModel) {

        embedding.validate();
        if (!embedding.isEnabled()) {
            // 关闭状态：不要求 PostgreSQL，也不要求 EmbeddingModel
            return Boolean.TRUE;
        }

        String url = dataSource.getUrl();
        if (url == null || !url.startsWith(POSTGRESQL_URL_PREFIX)) {
            throw new IllegalStateException("已启用 flowdesk.knowledge.embedding.enabled，但数据源不是 PostgreSQL："
                    + "切片向量依赖 pgvector，只能在 PostgreSQL 上运行。"
                    + "请使用 --spring.profiles.active=postgres,dashscope-embedding（H2 环境下请保持该开关为 false）");
        }
        if (embeddingModel.getIfAvailable() == null) {
            throw new IllegalStateException("已启用 flowdesk.knowledge.embedding.enabled，但没有可用的 EmbeddingModel："
                    + "请同时启用 dashscope-embedding profile 并配置 DASHSCOPE_API_KEY");
        }
        return Boolean.TRUE;
    }
}
