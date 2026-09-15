package com.flowdesk.infrastructure.knowledge.retrieval;

import com.flowdesk.application.knowledge.service.KnowledgeRetrievalService;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 知识检索配置（RAG 4/6）。
 *
 * <p>默认值：{@code max-query-code-points} 2000、{@code default-top-k} 5、
 * {@code max-top-k} 20、{@code default-min-score} 0.30。全部以 Unicode <b>code point</b>
 * 与「余弦相似度」为单位（相似度 = 1 - 余弦距离）。</p>
 *
 * <p>{@link #validate()} 在装配阶段执行：配置不合法就让应用启动失败，
 * 而不是等到某次检索才在运行期暴露。应用层只接收纯 Java 数值，
 * 由装配层把这里读到的值传进 {@link KnowledgeRetrievalService} —— 用例服务不认识 Spring。</p>
 */
@ConfigurationProperties(prefix = "flowdesk.knowledge.retrieval")
public class KnowledgeRetrievalProperties {

    /** 单个 query 允许的最大 code point 数上限（硬约束，配置只能更小）。 */
    public static final int MAX_QUERY_CODE_POINTS_LIMIT = 100_000;

    /** 单个 query 的下限：至少要有 1 个 code point 才可能是有意义的问题。 */
    public static final int MIN_QUERY_CODE_POINTS = 1;

    private int maxQueryCodePoints = 2000;

    private int defaultTopK = 5;

    private int maxTopK = 20;

    private double defaultMinScore = 0.30;

    /**
     * 严格校验配置，任何不合法都抛异常让应用启动失败。
     *
     * <p>三条约束：{@code 1 <= default-top-k <= max-top-k <= 50}；
     * {@code max-query-code-points} 在合理正数范围内；{@code default-min-score} 必须有限且在
     * {@code 0..1}。</p>
     */
    public void validate() {
        if (this.maxQueryCodePoints < MIN_QUERY_CODE_POINTS
                || this.maxQueryCodePoints > MAX_QUERY_CODE_POINTS_LIMIT) {
            throw new IllegalStateException("flowdesk.knowledge.retrieval.max-query-code-points 必须在 "
                    + MIN_QUERY_CODE_POINTS + ".." + MAX_QUERY_CODE_POINTS_LIMIT + " 之间");
        }
        if (this.maxTopK < KnowledgeRetrievalService.MIN_TOP_K
                || this.maxTopK > KnowledgeRetrievalService.MAX_TOP_K_LIMIT) {
            throw new IllegalStateException("flowdesk.knowledge.retrieval.max-top-k 必须在 "
                    + KnowledgeRetrievalService.MIN_TOP_K + ".."
                    + KnowledgeRetrievalService.MAX_TOP_K_LIMIT + " 之间");
        }
        if (this.defaultTopK < KnowledgeRetrievalService.MIN_TOP_K || this.defaultTopK > this.maxTopK) {
            throw new IllegalStateException("flowdesk.knowledge.retrieval.default-top-k 必须在 "
                    + KnowledgeRetrievalService.MIN_TOP_K + ".." + this.maxTopK + " 之间"
                    + "（且不能超过 max-top-k）");
        }
        if (!Double.isFinite(this.defaultMinScore)
                || this.defaultMinScore < KnowledgeRetrievalService.MIN_MIN_SCORE
                || this.defaultMinScore > KnowledgeRetrievalService.MAX_MIN_SCORE) {
            throw new IllegalStateException("flowdesk.knowledge.retrieval.default-min-score 必须是 "
                    + KnowledgeRetrievalService.MIN_MIN_SCORE + ".." + KnowledgeRetrievalService.MAX_MIN_SCORE
                    + " 之间的有限数值");
        }
    }

    /**
     * @return 单个 query 允许的最大 code point 数
     */
    public int getMaxQueryCodePoints() {
        return this.maxQueryCodePoints;
    }

    /**
     * @param maxQueryCodePoints 单个 query 允许的最大 code point 数
     */
    public void setMaxQueryCodePoints(int maxQueryCodePoints) {
        this.maxQueryCodePoints = maxQueryCodePoints;
    }

    /**
     * @return {@code topK} 默认值
     */
    public int getDefaultTopK() {
        return this.defaultTopK;
    }

    /**
     * @param defaultTopK {@code topK} 默认值
     */
    public void setDefaultTopK(int defaultTopK) {
        this.defaultTopK = defaultTopK;
    }

    /**
     * @return {@code topK} 上限
     */
    public int getMaxTopK() {
        return this.maxTopK;
    }

    /**
     * @param maxTopK {@code topK} 上限
     */
    public void setMaxTopK(int maxTopK) {
        this.maxTopK = maxTopK;
    }

    /**
     * @return {@code minScore} 默认值
     */
    public double getDefaultMinScore() {
        return this.defaultMinScore;
    }

    /**
     * @param defaultMinScore {@code minScore} 默认值
     */
    public void setDefaultMinScore(double defaultMinScore) {
        this.defaultMinScore = defaultMinScore;
    }
}
