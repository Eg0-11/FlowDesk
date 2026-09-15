package com.flowdesk.infrastructure.knowledge.embedding;

import com.flowdesk.application.knowledge.index.DocumentIndexingException;
import com.flowdesk.application.knowledge.port.out.KnowledgeEmbeddingPort;
import com.flowdesk.domain.knowledge.EmbeddingDescriptor;
import com.flowdesk.domain.knowledge.KnowledgeIndexFailureCode;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import com.alibaba.cloud.ai.dashscope.spec.DashScopeModel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * DashScope（阿里云百炼）文本向量适配器（FD-0010 / FD-0010-R1 / FD-0010-R3）。
 *
 * <h2>职责边界</h2>
 * <p>本类只做三件事：把文本列表翻译成 Spring AI 的 {@link EmbeddingRequest}、
 * 调用 {@link EmbeddingModel}、把结果按 <b>{@link Embedding#getIndex()} 声明的原请求位置</b>
 * 取出向量。合法性校验属于应用层（数量、维度、NaN/Infinity、全零），本类<b>不</b>做业务纠错，
 * 也不猜测上游意图。</p>
 *
 * <h2>为什么不依赖响应列表顺序（FD-0010-R1）</h2>
 * <p>上游返回的向量列表顺序<b>不是</b>契约的一部分：并发批量、内部重试、分片聚合都可能让
 * 响应顺序与请求顺序不同。用 {@code results.get(i)} 直接当作「第 i 条文本的向量」，
 * 一旦上游乱序就会把 A 的向量写到 B 的切片上 —— 而且不会有任何报错，检索阶段才发现召回错乱。</p>
 * <p>因此本类按 {@link Embedding#getIndex()} 把结果放回原请求位置，并对以下情况统一抛
 * {@link KnowledgeIndexFailureCode#INVALID_EMBEDDING_RESPONSE}：index 为 {@code null}、
 * 为负、越界、重复、缺失，或结果数量与请求数量不一致。<b>不</b>静默跳过、<b>不</b>覆盖已有位置、
 * <b>不</b>按列表顺序猜测。</p>
 * <p><b>这是协议映射，不是纠错</b>（FD-0010-R3）：{@code index} 是供应商明确声明的字段，
 * 按它归位就是把上游协议翻译成本端「第 i 个向量属于第 i 段文本」的稳定顺序；
 * 与之相对，<b>持久化层</b>（{@code JdbcKnowledgeDocumentEmbeddingStore}）拿到的是已经绑定好的
 * {@code (documentId, chunkIndex, chunkSha256, vector)} 业务记录，那里既无法也<b>不得</b>
 * 靠重排来修复错配，只能拒绝。</p>
 *
 * <h2>每次请求都显式指定模型、维度与语义</h2>
 * <p>{@code model}、{@code dimensions}、{@code textType=document} 都在请求选项里显式给出，
 * 而不是依赖 starter 的默认值：默认值会随依赖版本变化，而「用哪个模型、多少维」是
 * 已经写进数据库的契约（{@code embedding_model} / {@code embedding_dimensions}）。</p>
 * <p>{@code textType=document} 是 DashScope 的检索语义约定：文档侧必须用 {@code document}，
 * 查询侧（RAG 4/6 的检索阶段，尚未实现）才用 {@code query}。</p>
 *
 * <h2>不记录敏感内容</h2>
 * <p>本类不打印切片正文、向量数值、API Key 或完整响应。失败时只抛出携带<b>稳定失败码</b>的
 * {@link DocumentIndexingException}，把上游异常作为 cause 留在服务端（不进入消息、不进入数据库）。</p>
 * <p>自有日志同样只写<b>稳定失败码与异常类名</b>：上游异常的 message 可能回显请求内容或响应体，
 * 因此这里既不记录 {@code ex.getMessage()}，也不记录堆栈（堆栈首行就是 message）。</p>
 * <p><b>依赖库自己会打印正文</b>：{@code DashScopeEmbeddingModel} 在异常与空结果分支上以
 * {@code request.getInstructions()}（即切片正文）作为日志参数，早于本类的捕获。
 * 该日志只能由 {@code dashscope-embedding} profile 的
 * {@code logging.level.com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel=OFF} 关闭，
 * 原因与取舍见 ADR 0007。</p>
 *
 * <h2>维度探测被禁止</h2>
 * <p>刻意<b>不</b>调用 {@link EmbeddingModel#dimensions()}：它的默认实现可能发起一次远端请求
 * 来探测维度，既慢又会在启动阶段产生网络依赖。维度只来自本项目配置，并在写入前与响应逐条比对。</p>
 */
public final class DashScopeKnowledgeEmbeddingAdapter implements KnowledgeEmbeddingPort {

    private static final Logger log = LoggerFactory.getLogger(DashScopeKnowledgeEmbeddingAdapter.class);

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
            throw invalidResponse("向量请求不能为空");
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
            // 上游异常（超时、限流、5xx、鉴权失败…）统一映射为稳定失败码，细节只留在 cause。
            // 日志只写失败码与异常类名：上游 message 可能回显请求内容，堆栈首行就是 message。
            log.error("DashScope 向量调用失败：failureCode={} exception={}",
                    KnowledgeIndexFailureCode.EMBEDDING_PROVIDER_FAILURE, ex.getClass().getName());
            throw new DocumentIndexingException(KnowledgeIndexFailureCode.EMBEDDING_PROVIDER_FAILURE,
                    "向量服务调用失败", ex);
        }

        if (response == null || response.getResults() == null) {
            throw invalidResponse("向量服务返回了空响应");
        }

        return orderByIndex(response.getResults(), texts.size());
    }

    /**
     * 按 {@link Embedding#getIndex()} 把结果放回原请求位置。
     *
     * @param results   上游返回的向量列表（顺序不承诺与请求一致）
     * @param expected  请求的文本数量
     * @return 与请求一一对应、可直接按位置使用的向量列表
     */
    private static List<float[]> orderByIndex(List<Embedding> results, int expected) {
        if (results.size() != expected) {
            throw invalidResponse("向量服务返回的向量数量与请求不一致");
        }

        float[][] ordered = new float[expected][];
        boolean[] filled = new boolean[expected];
        for (Embedding embedding : results) {
            if (embedding == null) {
                throw invalidResponse("向量服务返回了空向量");
            }
            Integer declaredIndex = embedding.getIndex();
            if (declaredIndex == null) {
                throw invalidResponse("向量服务返回的向量缺少 index");
            }
            int position = declaredIndex;
            if (position < 0 || position >= expected) {
                throw invalidResponse("向量服务返回的 index 越界");
            }
            if (filled[position]) {
                // 覆盖已有位置会静默丢掉另一条文本的向量，必须拒绝
                throw invalidResponse("向量服务返回的 index 重复");
            }
            filled[position] = true;
            ordered[position] = embedding.getOutput();
        }
        for (boolean present : filled) {
            if (!present) {
                // 数量一致 + 无重复 + 无越界时不可达，保留为显式防线（例如上游返回的 index 不是整数）
                throw invalidResponse("向量服务返回的 index 缺失");
            }
        }
        // 刻意不用 List.copyOf：它会拒绝 null 元素，那样「上游返回了 null 向量」会在适配器内部
        // 变成 NullPointerException，而调用方需要的是稳定的 INVALID_EMBEDDING_RESPONSE
        List<float[]> vectors = new ArrayList<>(expected);
        Collections.addAll(vectors, ordered);
        return Collections.unmodifiableList(vectors);
    }

    private static DocumentIndexingException invalidResponse(String message) {
        return new DocumentIndexingException(KnowledgeIndexFailureCode.INVALID_EMBEDDING_RESPONSE, message);
    }
}
