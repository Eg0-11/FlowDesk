package com.flowdesk.application.knowledge.service;

import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.application.knowledge.port.in.RetrieveKnowledgeUseCase;
import com.flowdesk.application.knowledge.port.out.KnowledgeQueryEmbeddingPort;
import com.flowdesk.application.knowledge.port.out.KnowledgeVectorMatch;
import com.flowdesk.application.knowledge.port.out.KnowledgeVectorSearchPort;
import com.flowdesk.application.knowledge.query.KnowledgeQueryNormalizer;
import com.flowdesk.application.knowledge.query.RetrieveKnowledgeQuery;
import com.flowdesk.application.knowledge.view.KnowledgeCitationView;
import com.flowdesk.application.knowledge.view.KnowledgeRetrievalView;
import com.flowdesk.domain.knowledge.EmbeddingDescriptor;
import com.flowdesk.domain.knowledge.KnowledgeDomainException;
import com.flowdesk.domain.knowledge.KnowledgeQueryEmbedding;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 知识检索用例服务：纯 Java、无状态、框架无关（RAG 4/6）。
 *
 * <h2>固定时序</h2>
 * <ol>
 *   <li><b>校验并规范化请求</b>：NFC 规范化 → {@code strip} → 判空 → code point 上限 →
 *       拒绝 ISO 控制字符；{@code topK} / {@code minScore} 套默认值并校验范围。
 *       全部发生在<b>任何端口调用之前</b>；</li>
 *   <li><b>开关检查</b>：未启用向量化时抛 {@code KNOWLEDGE_EMBEDDING_DISABLED}，
 *       <b>不调用模型、不访问向量表</b>；</li>
 *   <li><b>查询向量</b>：{@link KnowledgeQueryEmbeddingPort}（一次调用、一条向量、查询语义）；
 *       这一步<b>不在任何数据库事务里</b>；</li>
 *   <li><b>领域校验</b>：把原始向量包装成 {@link KnowledgeQueryEmbedding}
 *       （维度、有限性、非全零，并做防御性复制）；</li>
 *   <li><b>向量检索</b>：{@link KnowledgeVectorSearchPort}（只读、单条 SQL）。
 *       该端口只允许用 {@code KNOWLEDGE_RETRIEVAL_FAILURE} 表达失败；任何其它错误码、
 *       领域异常或运行期异常都在端口边界被收敛为它（见 {@link #search});</li>
 *   <li><b>结果校验</b>：条数不超过 {@code topK}、分数有限且落在 {@code 0..1} 且不低于阈值、
 *       顺序满足「score 降序 → documentId 升序 → chunkIndex 升序」、无重复切片；</li>
 *   <li><b>生成引用</b>：按最终顺序编号 {@code K1}、{@code K2}……，{@code rank} 从 1 连续递增。</li>
 * </ol>
 *
 * <h2>为什么结果校验是「拒绝」而不是「修正」</h2>
 * <p>第 6 步<b>不</b>排序、<b>不</b>去重、<b>不</b>截断、<b>不</b>修正：</p>
 * <ul>
 *   <li>排序与去重是 SQL 的职责（那里有 HNSW 索引与确定性 tie-break）；
 *       在应用层再排一次，等于把「适配器返回了乱序结果」这个真实缺陷掩盖掉；</li>
 *   <li>截断会悄悄丢掉本应更相关的命中，而调用方无从察觉；</li>
 *   <li>因此违反契约的结果统一视为 {@code KNOWLEDGE_RETRIEVAL_FAILURE}（HTTP 500），
 *       让问题在测试与告警里暴露出来，而不是被静默修复。</li>
 * </ul>
 *
 * <h2>只读</h2>
 * <p>整个用例不修改任何状态：不更新文档版本、状态、失败码，也不写入或删除任何向量。
 * 因此检索失败<b>不会</b>留下 {@code INDEX_FAILED} 之类的痕迹，响应里也没有 {@code failureCode}。</p>
 */
public final class KnowledgeRetrievalService implements RetrieveKnowledgeUseCase {

    /** {@code topK} 允许的最小值。 */
    public static final int MIN_TOP_K = 1;

    /**
     * {@code topK} 的<b>公开硬上限</b>：配置可以收紧，但不允许扩大。
     *
     * <p>任务契约把公开输入限定为 {@code 1..20}；配置项
     * {@code flowdesk.knowledge.retrieval.max-top-k} 只能取更小的值。</p>
     */
    public static final int MAX_TOP_K_LIMIT = 20;

    /**
     * 单个 query 的<b>公开硬上限</b>（Unicode code point）：配置可以收紧，但不允许扩大。
     *
     * <p>任务契约把公开输入限定为 2000 个 code point；配置项
     * {@code flowdesk.knowledge.retrieval.max-query-code-points} 只能取更小的值。</p>
     */
    public static final int MAX_QUERY_CODE_POINTS_LIMIT = 2000;

    /** 单个 query 的下限：至少要有 1 个 code point 才可能是有意义的问题。 */
    public static final int MIN_QUERY_CODE_POINTS = 1;

    /** {@code minScore} 的合法下界（含边界）。 */
    public static final double MIN_MIN_SCORE = 0.0;

    /** {@code minScore} 的合法上界（含边界）。 */
    public static final double MAX_MIN_SCORE = 1.0;

    private final KnowledgeQueryEmbeddingPort queryEmbeddingPort;

    private final KnowledgeVectorSearchPort vectorSearchPort;

    private final boolean embeddingEnabled;

    private final EmbeddingDescriptor descriptor;

    private final int maxQueryCodePoints;

    private final int defaultTopK;

    private final int maxTopK;

    private final double defaultMinScore;

    /**
     * @param queryEmbeddingPort  查询向量生成端口
     * @param vectorSearchPort    向量相似度检索端口
     * @param embeddingEnabled    当前环境是否启用向量化
     * @param descriptor          与文档侧共用的向量描述符
     * @param maxQueryCodePoints  单个 query 允许的最大 code point 数
     * @param defaultTopK         {@code topK} 默认值
     * @param maxTopK             {@code topK} 上限
     * @param defaultMinScore     {@code minScore} 默认值
     */
    public KnowledgeRetrievalService(KnowledgeQueryEmbeddingPort queryEmbeddingPort,
            KnowledgeVectorSearchPort vectorSearchPort,
            boolean embeddingEnabled,
            EmbeddingDescriptor descriptor,
            int maxQueryCodePoints,
            int defaultTopK,
            int maxTopK,
            double defaultMinScore) {

        this.queryEmbeddingPort = Objects.requireNonNull(queryEmbeddingPort, "queryEmbeddingPort 不能为 null");
        this.vectorSearchPort = Objects.requireNonNull(vectorSearchPort, "vectorSearchPort 不能为 null");
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor 不能为 null");
        // 硬上限校验放在构造器里，而不是只依赖 Spring 配置类的 validate()：
        // 直接构造用例（测试、其它装配方式）同样不能突破公开契约
        if (maxQueryCodePoints < MIN_QUERY_CODE_POINTS
                || maxQueryCodePoints > MAX_QUERY_CODE_POINTS_LIMIT) {
            throw new IllegalArgumentException("maxQueryCodePoints 必须在 " + MIN_QUERY_CODE_POINTS + ".."
                    + MAX_QUERY_CODE_POINTS_LIMIT + " 之间");
        }
        if (maxTopK < MIN_TOP_K || maxTopK > MAX_TOP_K_LIMIT) {
            throw new IllegalArgumentException("maxTopK 必须在 " + MIN_TOP_K + ".." + MAX_TOP_K_LIMIT + " 之间");
        }
        if (defaultTopK < MIN_TOP_K || defaultTopK > maxTopK) {
            throw new IllegalArgumentException("defaultTopK 必须在 " + MIN_TOP_K + ".." + maxTopK + " 之间");
        }
        if (!Double.isFinite(defaultMinScore) || defaultMinScore < MIN_MIN_SCORE
                || defaultMinScore > MAX_MIN_SCORE) {
            throw new IllegalArgumentException("defaultMinScore 必须是 0.0..1.0 之间的有限数值");
        }

        this.embeddingEnabled = embeddingEnabled;
        this.maxQueryCodePoints = maxQueryCodePoints;
        this.defaultTopK = defaultTopK;
        this.maxTopK = maxTopK;
        this.defaultMinScore = defaultMinScore;
    }

    @Override
    public KnowledgeRetrievalView retrieve(RetrieveKnowledgeQuery query) {
        // ① 校验并规范化：全部发生在任何端口调用之前
        String normalizedQuery = normalizeQuery(query);
        int topK = resolveTopK(query);
        double minScore = resolveMinScore(query);

        // ② 开关检查：不调用模型、不访问向量表
        if (!this.embeddingEnabled) {
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.KNOWLEDGE_EMBEDDING_DISABLED,
                    "当前环境未启用文档向量化");
        }

        // ③④ 查询向量 + 领域校验（此处没有数据库事务）
        KnowledgeQueryEmbedding queryEmbedding = embedQuery(normalizedQuery);

        // ⑤ 向量检索（只读）
        List<KnowledgeVectorMatch> matches = search(queryEmbedding, minScore, topK);

        // ⑥ 结果契约校验：拒绝，而不是修正
        requireValidMatches(matches, topK, minScore);

        // ⑦ 按最终顺序生成引用
        return new KnowledgeRetrievalView(this.descriptor.provider(), this.descriptor.model(),
                this.descriptor.dimensions(), topK, minScore, cite(matches));
    }

    /**
     * 调用向量检索端口，并把该端口边界上的所有失败收敛到<b>端口契约允许的那一个错误码</b>。
     *
     * <h2>检索端口的错误分类契约</h2>
     * <p>{@link KnowledgeVectorSearchPort} 只允许用一种方式表达失败：
     * {@link KnowledgeApplicationErrorCode#KNOWLEDGE_RETRIEVAL_FAILURE}（HTTP 500）。
     * 因此本方法的分支是：</p>
     * <ul>
     *   <li><b>{@code KNOWLEDGE_RETRIEVAL_FAILURE} 原样上抛</b> —— 这是端口契约允许的失败类别，
     *       不二次包装（保留原始异常实例，便于测试与排障）；</li>
     *   <li><b>其余任何 {@link KnowledgeApplicationException}</b>（例如
     *       {@code INVALID_RETRIEVAL_QUERY}、{@code KNOWLEDGE_DOCUMENT_NOT_FOUND}、
     *       {@code KNOWLEDGE_EMBEDDING_DISABLED}、{@code EMBEDDING_PROVIDER_ERROR}，
     *       以及将来新增的错误码）表示端口<b>违反错误分类契约</b>，统一包装成
     *       {@code KNOWLEDGE_RETRIEVAL_FAILURE}，原异常作为 cause 保留；</li>
     *   <li><b>其它运行期异常</b>（含 {@link KnowledgeDomainException}、驱动异常、
     *       行映射期的领域不变量失败）同样收敛为 {@code KNOWLEDGE_RETRIEVAL_FAILURE}。</li>
     * </ul>
     * <p>为什么必须这样收口：如果让端口的任意错误码穿透到 HTTP 层，
     * 一个「端口实现或装配出了问题」的内部故障就可能表现为 <b>400 / 404 / 502 / 503</b> ——
     * 把服务端内部问题说成调用方输入错误、文档不存在或上游不可用。
     * 收口之后，检索链路上的失败只有两种对外形态：400（<b>调用方输入</b>，在调用本方法之前判定）
     * 与 500（<b>服务端</b>）。</p>
     * <p>对外文案是<b>两条</b>固定文案之一（按失败形态区分，均不含 query、向量、SQL、连接串或摘要原值）：
     * 端口抛出异常时为「向量检索端口调用失败」，端口返回契约之外的错误类别时为
     * 「向量检索端口返回了非法错误类别」。这两条文案只进服务端日志；HTTP 响应仍是 500 的固定文案
     * 「服务暂时不可用，请稍后重试」。</p>
     * <p>本方法只收紧<b>检索端口</b>的边界：查询向量端口（{@link #embedQuery(String)}）的
     * {@code EMBEDDING_PROVIDER_ERROR}（HTTP 502）不受影响。</p>
     *
     * @param queryEmbedding 已校验的查询向量
     * @param minScore       生效的阈值
     * @param topK           生效的条数上限
     * @return 命中结果（可能为空列表）
     */
    private List<KnowledgeVectorMatch> search(KnowledgeQueryEmbedding queryEmbedding, double minScore, int topK) {
        List<KnowledgeVectorMatch> matches;
        try {
            matches = this.vectorSearchPort.search(queryEmbedding, minScore, topK);
        }
        catch (KnowledgeApplicationException ex) {
            if (ex.errorCode() == KnowledgeApplicationErrorCode.KNOWLEDGE_RETRIEVAL_FAILURE) {
                // 端口契约允许的唯一失败类别：原样上抛，不二次包装
                throw ex;
            }
            throw retrievalFailure("向量检索端口返回了非法错误类别", ex);
        }
        catch (RuntimeException ex) {
            throw retrievalFailure("向量检索端口调用失败", ex);
        }
        if (matches == null) {
            throw retrievalFailure("向量检索端口返回了 null");
        }
        return matches;
    }

    /**
     * 规范化并校验 query：NFC → strip → 判空 → code point 上限 → 拒绝控制字符。
     *
     * <p>NFC 与 {@code strip} 由 {@link KnowledgeQueryNormalizer} 提供 —— 那是检索与问答
     * <b>共用</b>的唯一实现，因此送给查询向量端口的问题与写进模型提示词的问题逐字符相同；
     * 本方法只补上「合法性」这一层（空值、长度、控制字符），它也只在这里判定。</p>
     *
     * @param query 原始请求
     * @return 规范化后的 query
     */
    private String normalizeQuery(RetrieveKnowledgeQuery query) {
        if (query == null || query.query() == null) {
            throw invalidQuery("query 不能为空");
        }
        String normalized = KnowledgeQueryNormalizer.normalize(query.query());
        if (normalized.isEmpty()) {
            throw invalidQuery("query 不能为空");
        }
        int codePoints = normalized.codePointCount(0, normalized.length());
        if (codePoints > this.maxQueryCodePoints) {
            throw invalidQuery("query 长度不能超过 " + this.maxQueryCodePoints + " 个 code point");
        }
        // 控制字符（含 NUL、退格、转义）在检索里没有任何语义，只会污染向量与日志
        for (int index = 0; index < normalized.length();) {
            int codePoint = normalized.codePointAt(index);
            if (Character.isISOControl(codePoint)) {
                throw invalidQuery("query 不能包含控制字符");
            }
            index += Character.charCount(codePoint);
        }
        return normalized;
    }

    /**
     * @param query 原始请求
     * @return 生效的 topK（未提供时用默认值）
     */
    private int resolveTopK(RetrieveKnowledgeQuery query) {
        Integer requested = query.topK();
        if (requested == null) {
            return this.defaultTopK;
        }
        if (requested < MIN_TOP_K || requested > this.maxTopK) {
            throw invalidQuery("topK 必须在 " + MIN_TOP_K + " 到 " + this.maxTopK + " 之间");
        }
        return requested;
    }

    /**
     * @param query 原始请求
     * @return 生效的 minScore（未提供时用默认值）
     */
    private double resolveMinScore(RetrieveKnowledgeQuery query) {
        Double requested = query.minScore();
        if (requested == null) {
            return this.defaultMinScore;
        }
        double value = requested;
        if (!Double.isFinite(value) || value < MIN_MIN_SCORE || value > MAX_MIN_SCORE) {
            throw invalidQuery("minScore 必须是 " + MIN_MIN_SCORE + " 到 " + MAX_MIN_SCORE + " 之间的有限数值");
        }
        return value;
    }

    /**
     * 生成查询向量并用领域值对象校验。
     *
     * <p>上游异常与响应非法由适配器映射（502 / 500），这里只负责把原始数组包成
     * {@link KnowledgeQueryEmbedding}；不满足不变量（维度不符、{@code NaN}、全零等）
     * 说明上游返回了不可用的向量，属于内部检索失败。</p>
     *
     * @param normalizedQuery 已规范化的用户问题
     * @return 校验通过的查询向量
     */
    private KnowledgeQueryEmbedding embedQuery(String normalizedQuery) {
        float[] vector = this.queryEmbeddingPort.embedQuery(normalizedQuery, this.descriptor);
        try {
            return new KnowledgeQueryEmbedding(this.descriptor, vector);
        }
        catch (KnowledgeDomainException ex) {
            throw retrievalFailure("查询向量不满足不变量", ex);
        }
    }

    /**
     * 结果契约校验：条数、字段、分数、顺序、去重。
     *
     * <p>任何一条不满足都直接失败：<b>不</b>排序、<b>不</b>去重、<b>不</b>截断。</p>
     *
     * @param matches  端口返回的命中
     * @param topK     本次生效的条数上限
     * @param minScore 本次生效的分数下限
     */
    private static void requireValidMatches(List<KnowledgeVectorMatch> matches, int topK, double minScore) {
        if (matches.size() > topK) {
            throw retrievalFailure("向量检索返回的条数超过 topK");
        }
        Set<String> seen = new HashSet<>();
        KnowledgeVectorMatch previous = null;
        for (KnowledgeVectorMatch match : matches) {
            if (match == null) {
                throw retrievalFailure("向量检索返回了空命中");
            }
            requirePresentFields(match);
            requireValidScore(match.score(), minScore);
            if (previous != null && !precedes(previous, match)) {
                throw retrievalFailure("向量检索返回的结果顺序违反契约"
                        + "（必须按 score 降序、score 相同时按 documentId 与 chunkIndex 升序）");
            }
            if (!seen.add(match.documentId().value() + "#" + match.chunkIndex())) {
                throw retrievalFailure("向量检索返回了重复的 documentId 与 chunkIndex");
            }
            previous = match;
        }
    }

    /**
     * @param match 命中
     */
    private static void requirePresentFields(KnowledgeVectorMatch match) {
        if (match.documentId() == null || match.chunkSha256() == null) {
            throw retrievalFailure("向量检索返回的命中缺少标识或摘要");
        }
        if (match.documentTitle() == null || match.documentTitle().isBlank()) {
            throw retrievalFailure("向量检索返回的命中缺少文档标题");
        }
        if (match.content() == null || match.content().isBlank()) {
            throw retrievalFailure("向量检索返回的命中缺少切片内容");
        }
        if (match.chunkIndex() < 0 || match.documentVersion() < 0L) {
            throw retrievalFailure("向量检索返回的命中包含非法序号或版本");
        }
    }

    /**
     * @param score    命中的相似度
     * @param minScore 本次生效的下限
     */
    private static void requireValidScore(double score, double minScore) {
        if (!Double.isFinite(score) || score < MIN_MIN_SCORE || score > MAX_MIN_SCORE) {
            throw retrievalFailure("向量检索返回的相似度必须是 0.0 到 1.0 之间的有限数值");
        }
        if (score < minScore) {
            throw retrievalFailure("向量检索返回的相似度低于本次生效的 minScore");
        }
    }

    /**
     * 稳定顺序：score 降序 → documentId 升序 → chunkIndex 升序。
     *
     * @param left  前一条
     * @param right 后一条
     * @return {@code left} 是否必须排在 {@code right} 之前
     */
    private static boolean precedes(KnowledgeVectorMatch left, KnowledgeVectorMatch right) {
        if (left.score() != right.score()) {
            return left.score() > right.score();
        }
        int byDocument = left.documentId().value().compareTo(right.documentId().value());
        if (byDocument != 0) {
            return byDocument < 0;
        }
        return left.chunkIndex() < right.chunkIndex();
    }

    /**
     * 生成引用编号：{@code K1}、{@code K2}……与 {@code rank} 1、2……按最终顺序确定。
     *
     * @param matches 已校验的命中
     * @return 引用列表
     */
    private static List<KnowledgeCitationView> cite(List<KnowledgeVectorMatch> matches) {
        List<KnowledgeCitationView> citations = new ArrayList<>(matches.size());
        int rank = 1;
        for (KnowledgeVectorMatch match : matches) {
            citations.add(new KnowledgeCitationView("K" + rank, rank, match.documentId().value(),
                    match.documentVersion(), match.documentTitle(), match.chunkIndex(),
                    match.chunkSha256().value(), match.content(), match.score()));
            rank++;
        }
        return List.copyOf(citations);
    }

    private static KnowledgeApplicationException invalidQuery(String detail) {
        return new KnowledgeApplicationException(KnowledgeApplicationErrorCode.INVALID_RETRIEVAL_QUERY, detail);
    }

    private static KnowledgeApplicationException retrievalFailure(String detail) {
        return retrievalFailure(detail, null);
    }

    private static KnowledgeApplicationException retrievalFailure(String detail, Throwable cause) {
        return new KnowledgeApplicationException(KnowledgeApplicationErrorCode.KNOWLEDGE_RETRIEVAL_FAILURE,
                detail, cause);
    }
}
