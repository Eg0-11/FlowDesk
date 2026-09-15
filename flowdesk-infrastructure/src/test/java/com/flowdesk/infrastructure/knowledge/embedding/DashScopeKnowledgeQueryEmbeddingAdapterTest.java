package com.flowdesk.infrastructure.knowledge.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.domain.knowledge.EmbeddingDescriptor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * DashScope 查询向量适配器测试（RAG 4/6）。
 *
 * <p>手写 {@link EmbeddingModel} 替身，把上游行为完全置于测试控制之下：
 * 请求里究竟带了什么参数（尤其是 {@code textType=query}）、响应结构如何被严格校验、
 * 异常如何映射，以及最关键的「<b>query 绝不进日志</b>」。</p>
 */
class DashScopeKnowledgeQueryEmbeddingAdapterTest {

    private static final EmbeddingDescriptor DESCRIPTOR =
            EmbeddingDescriptor.of("dashscope", "text-embedding-v4");

    private static final String QUERY = "VPN 无法连接应该如何处理？";

    @Test
    void everyRequestCarriesModelDimensionsAndQueryTextType() {
        RecordingEmbeddingModel model = new RecordingEmbeddingModel();
        DashScopeKnowledgeQueryEmbeddingAdapter adapter = new DashScopeKnowledgeQueryEmbeddingAdapter(model);

        float[] vector = adapter.embedQuery(QUERY, DESCRIPTOR);

        assertThat(vector).hasSize(EmbeddingDescriptor.REQUIRED_DIMENSIONS);
        assertThat(model.requests).hasSize(1);

        EmbeddingRequest request = model.requests.get(0);
        assertThat(request.getInstructions())
                .as("一次请求只包含规范化后的那一个 query")
                .containsExactly(QUERY);
        assertThat(request.getOptions()).isInstanceOf(DashScopeEmbeddingOptions.class);

        DashScopeEmbeddingOptions options = (DashScopeEmbeddingOptions) request.getOptions();
        assertThat(options.getModel()).isEqualTo("text-embedding-v4");
        assertThat(options.getDimensions()).isEqualTo(1024);
        assertThat(options.getTextType())
                .as("查询侧必须显式使用 query 语义（文档侧是 document）")
                .isEqualTo("query");
    }

    @Test
    void neverProbesDimensions() {
        RecordingEmbeddingModel model = new RecordingEmbeddingModel();

        new DashScopeKnowledgeQueryEmbeddingAdapter(model).embedQuery(QUERY, DESCRIPTOR);

        assertThat(model.dimensionsCalls)
                .as("dimensions() 的默认实现可能发起远端请求，适配器不得调用它")
                .isZero();
    }

    @Test
    void rejectsAnEmptyQueryWithoutCallingTheModel() {
        RecordingEmbeddingModel model = new RecordingEmbeddingModel();

        assertThatThrownBy(() -> new DashScopeKnowledgeQueryEmbeddingAdapter(model)
                .embedQuery("", DESCRIPTOR))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DashScopeKnowledgeQueryEmbeddingAdapter(model)
                .embedQuery(null, DESCRIPTOR))
                .isInstanceOf(NullPointerException.class);

        assertThat(model.requests).isEmpty();
    }

    // ---------- 响应校验 ----------

    @Test
    void rejectsANullResponseOrNullResults() {
        assertRetrievalFailure("null 响应", model -> model.returnNullResponse = true);
        assertRetrievalFailure("null 结果列表", model -> model.returnNullList = true);
    }

    @Test
    void rejectsAResultCountOtherThanExactlyOne() {
        assertRetrievalFailure("零条结果", model -> model.resultCount = 0);
        assertRetrievalFailure("两条结果", model -> model.resultCount = 2);
    }

    @Test
    void rejectsANullEmbeddingObjectOrNullOutput() {
        assertRetrievalFailure("null 向量对象", model -> model.returnNullEmbeddingObject = true);
        assertRetrievalFailure("null 向量数值", model -> model.returnNullOutput = true);
    }

    @Test
    void rejectsAnIndexThatIsNotNullZeroOrNegative() {
        assertRetrievalFailure("index 为 null", model -> model.nullIndex = true);
        assertRetrievalFailure("index 为负数", model -> model.index = -1);
        assertRetrievalFailure("index 非 0", model -> model.index = 3);
    }

    @Test
    void acceptsIndexZero() {
        RecordingEmbeddingModel model = new RecordingEmbeddingModel();

        assertThat(new DashScopeKnowledgeQueryEmbeddingAdapter(model).embedQuery(QUERY, DESCRIPTOR))
                .hasSize(EmbeddingDescriptor.REQUIRED_DIMENSIONS);
    }

    // ---------- 异常映射 ----------

    @Test
    void upstreamFailuresBecomeProviderErrorsWithTheCauseKept() {
        RecordingEmbeddingModel model = new RecordingEmbeddingModel();
        model.failure = new IllegalStateException("HTTP 429 Too Many Requests: quota exceeded");

        KnowledgeApplicationException thrown = (KnowledgeApplicationException) org.assertj.core.api.Assertions
                .catchThrowable(() -> new DashScopeKnowledgeQueryEmbeddingAdapter(model)
                        .embedQuery(QUERY, DESCRIPTOR));

        assertThat(thrown.errorCode()).isEqualTo(KnowledgeApplicationErrorCode.EMBEDDING_PROVIDER_ERROR);
        assertThat(thrown).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(thrown.getMessage())
                .as("上游响应体与 query 都不得进入消息")
                .doesNotContain("429").doesNotContain("quota").doesNotContain("HTTP")
                .doesNotContain(QUERY);
    }

    @Test
    void malformedResponsesAreReportedAsRetrievalFailures() {
        RecordingEmbeddingModel model = new RecordingEmbeddingModel();
        model.returnNullList = true;

        KnowledgeApplicationException thrown = (KnowledgeApplicationException) org.assertj.core.api.Assertions
                .catchThrowable(() -> new DashScopeKnowledgeQueryEmbeddingAdapter(model)
                        .embedQuery(QUERY, DESCRIPTOR));

        assertThat(thrown.errorCode()).isEqualTo(KnowledgeApplicationErrorCode.KNOWLEDGE_RETRIEVAL_FAILURE);
    }

    @Test
    void ourOwnFailureLogKeepsOnlyTheFailureCodeAndExceptionClassName() {
        String sentinel = "SENTINEL-QUERY-VPN-绝不进日志";
        RecordingEmbeddingModel model = new RecordingEmbeddingModel();
        model.failure = new IllegalStateException("upstream rejected: " + sentinel);

        Logger adapterLogger = (Logger) LoggerFactory.getLogger(DashScopeKnowledgeQueryEmbeddingAdapter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        adapterLogger.addAppender(appender);
        try {
            assertThatThrownBy(() -> new DashScopeKnowledgeQueryEmbeddingAdapter(model)
                    .embedQuery(sentinel, DESCRIPTOR))
                    .isInstanceOf(KnowledgeApplicationException.class);
        }
        finally {
            adapterLogger.detachAppender(appender);
        }

        List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).isNotEmpty();
        assertThat(messages).allSatisfy(message -> {
            assertThat(message).contains(KnowledgeApplicationErrorCode.EMBEDDING_PROVIDER_ERROR.name());
            assertThat(message).contains(IllegalStateException.class.getName());
            assertThat(message).as("query 是用户数据，绝不进日志").doesNotContain(sentinel);
            assertThat(message).doesNotContain("upstream rejected");
        });
        assertThat(appender.list).allSatisfy(event ->
                assertThat(event.getThrowableProxy()).as("不得打印堆栈（首行就是 message）").isNull());
    }

    // ---------- 辅助 ----------

    private void assertRetrievalFailure(String caseName,
            java.util.function.Consumer<RecordingEmbeddingModel> mutation) {

        RecordingEmbeddingModel model = new RecordingEmbeddingModel();
        mutation.accept(model);

        assertThatThrownBy(() -> new DashScopeKnowledgeQueryEmbeddingAdapter(model)
                .embedQuery(QUERY, DESCRIPTOR)).as(caseName)
                .isInstanceOf(KnowledgeApplicationException.class)
                .extracting(thrown -> ((KnowledgeApplicationException) thrown).errorCode())
                .isEqualTo(KnowledgeApplicationErrorCode.KNOWLEDGE_RETRIEVAL_FAILURE);
    }

    /**
     * 手写 {@link EmbeddingModel} 替身：记录请求，并可注入各种畸形响应。
     */
    private static final class RecordingEmbeddingModel implements EmbeddingModel {

        private final List<EmbeddingRequest> requests = new ArrayList<>();

        private RuntimeException failure;

        private boolean returnNullResponse;

        private boolean returnNullList;

        private boolean returnNullEmbeddingObject;

        private boolean returnNullOutput;

        private boolean nullIndex;

        private int index = 0;

        private Integer resultCount;

        private int dimensionsCalls;

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            this.requests.add(request);
            if (this.failure != null) {
                throw this.failure;
            }
            if (this.returnNullResponse) {
                return null;
            }
            if (this.returnNullList) {
                return new EmbeddingResponse(null);
            }

            int count = this.resultCount == null ? request.getInstructions().size() : this.resultCount;
            List<Embedding> results = new ArrayList<>(count);
            for (int position = 0; position < count; position++) {
                Embedding embedding = this.returnNullEmbeddingObject
                        ? null
                        : new Embedding(this.returnNullOutput ? null : vector(), this.nullIndex ? null : this.index);
                results.add(embedding);
            }
            return new EmbeddingResponse(results);
        }

        @Override
        public float[] embed(Document document) {
            return vector();
        }

        @Override
        public int dimensions() {
            this.dimensionsCalls++;
            throw new IllegalStateException("适配器不得调用 dimensions()");
        }

        private static float[] vector() {
            float[] vector = new float[EmbeddingDescriptor.REQUIRED_DIMENSIONS];
            Arrays.fill(vector, 0.5f);
            return vector;
        }

        @Override
        public EmbeddingResponse embedForResponse(List<String> texts) {
            return call(new EmbeddingRequest(texts, (EmbeddingOptions) null));
        }
    }
}
