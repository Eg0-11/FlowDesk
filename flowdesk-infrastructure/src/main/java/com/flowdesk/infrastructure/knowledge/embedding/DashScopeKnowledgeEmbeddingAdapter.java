package com.flowdesk.infrastructure.knowledge.embedding;

import com.flowdesk.application.knowledge.index.DocumentIndexingException;
import com.flowdesk.application.knowledge.port.out.KnowledgeEmbeddingPort;
import com.flowdesk.domain.knowledge.EmbeddingDescriptor;
import com.flowdesk.domain.knowledge.KnowledgeIndexFailureCode;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import com.alibaba.cloud.ai.dashscope.spec.DashScopeModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.Embedding;

/**
 * DashScope（阿里云百炼）文本向量适配器（FD-0010）。
 *
 * <h2>职责边界</h2>
 * <p>本类只做三件事：把文本列表翻译成 Spring AI 的 {@link EmbeddingRequest}、
 * 调用 {@link EmbeddingModel}、把结果按<b>原顺序</b>取出向量。合法性校验属于应用层
 * （数量、维度、NaN/Infinity、全零），本类<b>不</b>做「纠错」，也不猜测上游意图。</p>
 *
 * <h2>每次请求都显式指定模型、维度与语义</h2>
 * <p>{@code model}、{@code dimensions}、{@code textType=document} 都在请求选项里显式给出，
 * 而不是依赖 starter 的默认值：默认值会随依赖版本变化，而「用哪个模型、多少维」是
 * 已经写进数据库的契约（{@code embedding_model} / {@code embedding_dimensions}）。</p>
 * <p>{@code textType=document} 是 DashScope 的检索语义约定：文档侧必须用 {@code document}，
 * 查询侧（下一阶段）才用 {@code query}。</p>
 *
 * <h2>不记录敏感内容</h2>
 * <p>本类不打印切片正文、向量数值、API Key 或完整响应；失败时只抛出携带<b>稳定失败码</b>的
 * {@link DocumentIndexingException}，把上游异常作为 cause 留在服务端（不进入消息、不进入数据库）。</p>
 *
 * <h2>维度探测被禁止</h2>
 * <p>刻意<b>不</b>调用 {@link EmbeddingModel#dimensions()}：它的默认实现可能发起一次远端请求
 * 来探测维度，既慢又会在启动阶段产生网络依赖。维度只来自本项目配置，并在写入前与响应逐条比对。</p>
 */
public final class DashScopeKnowledgeEmbeddingAdapter implements KnowledgeEmbeddingPort {

    private final EmbeddingModel embeddingModel;

    /**
     * @param embeddingModel Spring AI 的 EmbeddingModel（由 DashScope starter 提供）
     */
    public DashScopeKnowledgeEmbeddingAdapter(EmbeddingModel embeddingModel) {
        this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel 不能为 null");
    }

    @Override
    public List<float[]> embedAll(List<String> texts, EmbeddingDescriptor descriptor) {
        Objects.requireNonNull(texts, "texts 不能为 null");
        Objects.requireNonNull(descriptor, "descriptor 不能为 null");
        if (texts.isEmpty()) {
            throw new DocumentIndexingException(KnowledgeIndexFailureCode.INVALID_EMBEDDING_RESPONSE,
                    "向量请求不能为空");
        }

        // 每次请求显式给出模型、维度与文档语义，不依赖 starter 的默认值
        DashScopeEmbeddingOptions options = DashScopeEmbeddingOptions.builder()
                .model(descriptor.model())
                .dimensions(descriptor.dimensions())
                .textType(DashScopeModel.EmbeddingTextType.DOCUMENT.getValue())
                .build();

        EmbeddingResponse response;
        try {
            response = this.embeddingModel.call(new EmbeddingRequest(texts, options));
        }
        catch (RuntimeException ex) {
            // 上游异常（超时、限流、5xx、鉴权失败…）统一映射为稳定失败码，细节只留在 cause
            throw new DocumentIndexingException(KnowledgeIndexFailureCode.EMBEDDING_PROVIDER_FAILURE,
                    "向量服务调用失败", ex);
        }

        if (response == null || response.getResults() == null) {
            throw new DocumentIndexingException(KnowledgeIndexFailureCode.INVALID_EMBEDDING_RESPONSE,
                    "向量服务返回了空响应");
        }

        List<Embedding> results = response.getResults();
        if (results.size() != texts.size()) {
            throw new DocumentIndexingException(KnowledgeIndexFailureCode.INVALID_EMBEDDING_RESPONSE,
                    "向量服务返回的向量数量与请求不一致");
        }

        List<float[]> vectors = new ArrayList<>(results.size());
        for (Embedding embedding : results) {
            if (embedding == null) {
                throw new DocumentIndexingException(KnowledgeIndexFailureCode.INVALID_EMBEDDING_RESPONSE,
                        "向量服务返回了空向量");
            }
            vectors.add(embedding.getOutput());
        }
        // 刻意不用 List.copyOf：它会拒绝 null 元素，那样「上游返回了 null 向量」会在适配器内部
        // 变成 NullPointerException，而调用方需要的是稳定的 INVALID_EMBEDDING_RESPONSE
        return java.util.Collections.unmodifiableList(vectors);
    }
}
