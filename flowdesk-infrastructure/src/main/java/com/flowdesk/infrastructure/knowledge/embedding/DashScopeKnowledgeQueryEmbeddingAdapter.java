package com.flowdesk.infrastructure.knowledge.embedding;

import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import com.alibaba.cloud.ai.dashscope.spec.DashScopeModel;
import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.application.knowledge.port.out.KnowledgeQueryEmbeddingPort;
import com.flowdesk.domain.knowledge.EmbeddingDescriptor;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * DashScope 查询向量适配器（RAG 4/6）。
 *
 * <h2>为什么是独立适配器，而不是复用文档侧端口</h2>
 * <p>文档侧请求固定 {@code textType=document}，查询侧必须用 {@code textType=query}：
 * DashScope 的检索语义约定要求两侧分别标注，混用会让相似度整体偏斜。
 * 用一个布尔参数在同一个适配器里分流会让「同一次批量里两种语义混在一起」成为可能，
 * 因此这里用<b>独立类型 + 独立选项</b>把差异固化在结构上。
 * 两者复用<b>同一个 {@link EmbeddingModel} Bean 与同一份描述符</b>：
 * 查询向量必须与库里的文档向量来自同一模型、同一维度，否则余弦相似度没有意义。</p>
 *
 * <h2>一次请求一个 query</h2>
 * <p>请求里只放规范化后的那一个 query，并严格校验响应：结果必须恰好 1 条、
 * 该条的 {@link Embedding#getIndex()} 必须恰好为 0、向量对象与数值都不能为 {@code null}。
 * 任何结构偏差都视为内部检索失败，而不是「凑合用第一条」。</p>
 *
 * <h2>不记录敏感内容</h2>
 * <p>本类<b>不</b>打印 query、向量数值、API Key 或完整响应。自有日志只写稳定失败码与异常类名
 * （上游 message 可能回显请求内容，堆栈首行就是 message，因此两者都不记录）。
 * 依赖库 {@code DashScopeEmbeddingModel} 会在异常与空结果分支上打印
 * {@code request.getInstructions()} ——也就是 query 本身——该 logger 由
 * {@code dashscope-embedding} profile 设为 {@code OFF}（见 ADR 0007 R1 §3、ADR 0008）。</p>
 *
 * <h2>维度探测被禁止</h2>
 * <p>刻意<b>不</b>调用 {@link EmbeddingModel#dimensions()}：它的默认实现可能发起一次远端请求。
 * 维度只来自本项目配置，并由领域值对象在应用层校验。</p>
 */
public final class DashScopeKnowledgeQueryEmbeddingAdapter implements KnowledgeQueryEmbeddingPort {

    private static final Logger log = LoggerFactory.getLogger(DashScopeKnowledgeQueryEmbeddingAdapter.class);

    private final EmbeddingModel embeddingModel;

    /**
     * @param embeddingModel 与文档侧共用的 EmbeddingModel（由 DashScope starter 提供）
     */
    public DashScopeKnowledgeQueryEmbeddingAdapter(EmbeddingModel embeddingModel) {
        this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel 不能为 null");
    }

    @Override
    public float[] embedQuery(String query, EmbeddingDescriptor descriptor) {
        Objects.requireNonNull(query, "query 不能为 null");
        Objects.requireNonNull(descriptor, "descriptor 不能为 null");
        if (query.isEmpty()) {
            throw new IllegalArgumentException("query 不能为空");
        }

        // 每次请求显式给出模型、维度与「查询」语义，不依赖 starter 的默认值
        DashScopeEmbeddingOptions options = DashScopeEmbeddingOptions.builder()
                .model(descriptor.model())
                .dimensions(descriptor.dimensions())
                .textType(DashScopeModel.EmbeddingTextType.QUERY.getValue())
                .build();

        EmbeddingResponse response;
        try {
            // 一次请求只包含一个 query：批量在这里没有意义，也会让「哪个向量属于哪个问题」变得可猜
            response = this.embeddingModel.call(new EmbeddingRequest(List.of(query), options));
        }
        catch (RuntimeException ex) {
            // 上游异常（超时、限流、5xx、鉴权失败…）→ 502；细节只留在 cause
            log.error("DashScope 查询向量调用失败：errorCode={} exception={}",
                    KnowledgeApplicationErrorCode.EMBEDDING_PROVIDER_ERROR, ex.getClass().getName());
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.EMBEDDING_PROVIDER_ERROR,
                    "查询向量服务调用失败", ex);
        }

        return extractSingleVector(response);
    }

    /**
     * 严格取出唯一一条向量：数量、index、输出都不能含糊。
     *
     * @param response 上游响应
     * @return 查询向量数值（此处不复制：调用方会立刻包进领域值对象，那个对象自己会复制）
     */
    private static float[] extractSingleVector(EmbeddingResponse response) {
        if (response == null || response.getResults() == null) {
            throw invalidResponse("查询向量服务返回了空响应");
        }
        List<Embedding> results = response.getResults();
        if (results.size() != 1) {
            throw invalidResponse("查询向量服务返回的向量数量不是 1");
        }
        Embedding embedding = results.get(0);
        if (embedding == null) {
            throw invalidResponse("查询向量服务返回了空向量");
        }
        Integer index = embedding.getIndex();
        if (index == null || index != 0) {
            throw invalidResponse("查询向量服务返回的 index 不是 0");
        }
        float[] output = embedding.getOutput();
        if (output == null) {
            throw invalidResponse("查询向量服务返回了空向量数值");
        }
        return output;
    }

    private static KnowledgeApplicationException invalidResponse(String detail) {
        return new KnowledgeApplicationException(KnowledgeApplicationErrorCode.KNOWLEDGE_RETRIEVAL_FAILURE, detail);
    }
}
