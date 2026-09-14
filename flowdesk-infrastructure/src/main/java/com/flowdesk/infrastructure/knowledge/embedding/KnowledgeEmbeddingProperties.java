package com.flowdesk.infrastructure.knowledge.embedding;

import com.flowdesk.domain.knowledge.EmbeddingDescriptor;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 文档向量化配置（FD-0010）。
 *
 * <p>默认值刻意是「关闭」：默认 profile 不创建 EmbeddingModel、不发生网络请求、
 * 也不需要任何 API Key（见 ADR 0007）。只有显式启用 {@code dashscope-embedding} profile
 * 才打开这条链路。</p>
 *
 * <p>{@link #validate()} 在装配阶段执行，配置不合法就让应用启动失败，
 * 而不是等到某个请求打进来才报错。</p>
 */
@ConfigurationProperties(prefix = "flowdesk.knowledge.embedding")
public class KnowledgeEmbeddingProperties {

    /** 单批最大切片数：DashScope Embedding 接口对一次请求的输入条数有上限。 */
    public static final int MAX_BATCH_SIZE = 10;

    /** 是否启用真实向量化。默认关闭。 */
    private boolean enabled = false;

    /** 向量服务提供方标识（会持久化到文档元数据）。 */
    private String provider = "dashscope";

    /** 向量模型标识（会持久化到文档元数据）。 */
    private String model = "text-embedding-v4";

    /** 向量维度：本项目固定 1024，与 {@code vector(1024)} 列一致。 */
    private int dimensions = EmbeddingDescriptor.REQUIRED_DIMENSIONS;

    /** 单批切片数，必须在 1..{@link #MAX_BATCH_SIZE}。 */
    private int batchSize = MAX_BATCH_SIZE;

    /**
     * 严格校验配置，任何不合法都抛异常让应用启动失败。
     */
    public void validate() {
        if (this.provider == null || this.provider.isBlank()) {
            throw new IllegalStateException("flowdesk.knowledge.embedding.provider 不能为空");
        }
        if (this.model == null || this.model.isBlank()) {
            throw new IllegalStateException("flowdesk.knowledge.embedding.model 不能为空");
        }
        if (this.dimensions != EmbeddingDescriptor.REQUIRED_DIMENSIONS) {
            throw new IllegalStateException("flowdesk.knowledge.embedding.dimensions 必须等于 "
                    + EmbeddingDescriptor.REQUIRED_DIMENSIONS + "（与 vector(1024) 列一致）");
        }
        if (this.batchSize < 1 || this.batchSize > MAX_BATCH_SIZE) {
            throw new IllegalStateException("flowdesk.knowledge.embedding.batch-size 必须在 1.."
                    + MAX_BATCH_SIZE + " 之间");
        }
    }

    /**
     * @return 向量描述符（provider / model / dimensions）
     */
    public EmbeddingDescriptor descriptor() {
        return new EmbeddingDescriptor(this.provider, this.model, this.dimensions);
    }

    /**
     * @return 是否启用真实向量化
     */
    public boolean isEnabled() {
        return this.enabled;
    }

    /**
     * @param enabled 是否启用真实向量化
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * @return 向量服务提供方
     */
    public String getProvider() {
        return this.provider;
    }

    /**
     * @param provider 向量服务提供方
     */
    public void setProvider(String provider) {
        this.provider = provider;
    }

    /**
     * @return 向量模型标识
     */
    public String getModel() {
        return this.model;
    }

    /**
     * @param model 向量模型标识
     */
    public void setModel(String model) {
        this.model = model;
    }

    /**
     * @return 向量维度
     */
    public int getDimensions() {
        return this.dimensions;
    }

    /**
     * @param dimensions 向量维度，必须等于 1024
     */
    public void setDimensions(int dimensions) {
        this.dimensions = dimensions;
    }

    /**
     * @return 单批切片数
     */
    public int getBatchSize() {
        return this.batchSize;
    }

    /**
     * @param batchSize 单批切片数
     */
    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }
}
