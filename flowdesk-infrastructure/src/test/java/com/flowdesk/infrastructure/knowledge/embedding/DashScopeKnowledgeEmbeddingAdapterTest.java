package com.flowdesk.infrastructure.knowledge.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import com.flowdesk.application.knowledge.index.DocumentIndexingException;
import com.flowdesk.domain.knowledge.EmbeddingDescriptor;
import com.flowdesk.domain.knowledge.KnowledgeIndexFailureCode;
import java.util.ArrayList;
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
 * DashScope 向量适配器与向量化配置测试（FD-0010 / FD-0010-R1）。
 *
 * <p>这里用<b>手写替身</b>实现 {@link EmbeddingModel}，把上游行为完全置于测试控制之下：
 * 请求里究竟带了什么参数、乱序结果如何归位、异常如何映射、日志里会不会出现正文，
 * 以及最关键的「<b>绝不调用 {@code dimensions()}</b>」。</p>
 */
class DashScopeKnowledgeEmbeddingAdapterTest {

    private static final EmbeddingDescriptor DESCRIPTOR =
            EmbeddingDescriptor.of("dashscope", "text-embedding-v4");

    // ---------- 请求参数 ----------

    @Test
    void everyRequestCarriesModelDimensionsAndDocumentTextType() {
        RecordingEmbeddingModel model = new RecordingEmbeddingModel();
        DashScopeKnowledgeEmbeddingAdapter adapter = new DashScopeKnowledgeEmbeddingAdapter(model);

        List<float[]> vectors = adapter.embedAll(List.of("第一段", "第二段"), DESCRIPTOR);

        assertThat(vectors).hasSize(2);
        assertThat(model.requests).hasSize(1);

        EmbeddingRequest request = model.requests.get(0);
        assertThat(request.getInstructions()).containsExactly("第一段", "第二段");
        assertThat(request.getOptions()).isInstanceOf(DashScopeEmbeddingOptions.class);

        DashScopeEmbeddingOptions options = (DashScopeEmbeddingOptions) request.getOptions();
        assertThat(options.getModel()).as("模型必须来自描述符，而不是 starter 的默认值")
                .isEqualTo("text-embedding-v4");
        assertThat(options.getDimensions()).isEqualTo(1024);
        assertThat(options.getTextType()).as("文档侧必须用 document 语义").isEqualTo("document");
    }

    // ---------- 按 index 归位（FD-0010-R1） ----------

    @Test
    void restoresTheRequestOrderFromTheDeclaredIndex() {
        // 上游按 [2, 0, 1] 的顺序返回，但每条结果都带着自己的 index
        RecordingEmbeddingModel model = new RecordingEmbeddingModel();
        model.returnedOrder = new int[] { 2, 0, 1 };
        DashScopeKnowledgeEmbeddingAdapter adapter = new DashScopeKnowledgeEmbeddingAdapter(model);

        List<float[]> vectors = adapter.embedAll(List.of("a", "b", "c"), DESCRIPTOR);

        // 每条向量都由替身按 index 打上「首值 = 100 + index」的标记：
        // 只有真正按 index 归位，结果才会是 [100, 101, 102]
        assertThat(vectors).hasSize(3);
        assertThat(vectors.get(0)[0]).as("第 0 条文本必须拿到 index=0 的向量").isEqualTo(100.0f);
        assertThat(vectors.get(1)[0]).as("第 1 条文本必须拿到 index=1 的向量").isEqualTo(101.0f);
        assertThat(vectors.get(2)[0]).as("第 2 条文本必须拿到 index=2 的向量").isEqualTo(102.0f);
    }

    @Test
    void restoresTheRequestOrderEvenForASingleText() {
        RecordingEmbeddingModel model = new RecordingEmbeddingModel();
        model.returnedOrder = new int[] { 0 };
        DashScopeKnowledgeEmbeddingAdapter adapter = new DashScopeKnowledgeEmbeddingAdapter(model);

        assertThat(adapter.embedAll(List.of("只有一段"), DESCRIPTOR).get(0)[0]).isEqualTo(100.0f);
    }

    @Test
    void rejectsResultsWhoseIndexIsDuplicated() {
        assertInvalidResponse("index 重复", model -> model.returnedOrder = new int[] { 0, 0, 1 });
    }

    @Test
    void rejectsResultsWhoseIndexIsOutOfRange() {
        assertInvalidResponse("index 越界（上界外）", model -> model.returnedOrder = new int[] { 0, 1, 3 });
        assertInvalidResponse("index 越界（负数）", model -> model.returnedOrder = new int[] { 0, 1, -1 });
    }

    @Test
    void rejectsResultsWithoutAnIndex() {
        assertInvalidResponse("index 为 null", model -> model.nullIndexAt = 0);
    }

    @Test
    void neverProbesDimensions() {
        RecordingEmbeddingModel model = new RecordingEmbeddingModel();
        DashScopeKnowledgeEmbeddingAdapter adapter = new DashScopeKnowledgeEmbeddingAdapter(model);

        adapter.embedAll(List.of("文本"), DESCRIPTOR);

        assertThat(model.dimensionsCalls)
                .as("dimensions() 的默认实现可能发起远端请求，适配器不得调用它")
                .isZero();
    }

    @Test
    void rejectsAnEmptyRequestWithoutCallingTheModel() {
        RecordingEmbeddingModel model = new RecordingEmbeddingModel();
        DashScopeKnowledgeEmbeddingAdapter adapter = new DashScopeKnowledgeEmbeddingAdapter(model);

        assertThatThrownBy(() -> adapter.embedAll(List.of(), DESCRIPTOR))
                .isInstanceOf(DocumentIndexingException.class)
                .extracting(thrown -> ((DocumentIndexingException) thrown).failureCode())
                .isEqualTo(KnowledgeIndexFailureCode.INVALID_EMBEDDING_RESPONSE);
        assertThat(model.requests).isEmpty();
    }

    // ---------- 失败映射 ----------

    @Test
    void upstreamFailuresBecomeProviderFailuresWithTheCauseKept() {
        RecordingEmbeddingModel model = new RecordingEmbeddingModel();
        model.failure = new IllegalStateException("HTTP 429 Too Many Requests: quota exceeded");
        DashScopeKnowledgeEmbeddingAdapter adapter = new DashScopeKnowledgeEmbeddingAdapter(model);

        DocumentIndexingException thrown = (DocumentIndexingException) org.assertj.core.api.Assertions
                .catchThrowable(() -> adapter.embedAll(List.of("文本"), DESCRIPTOR));

        assertThat(thrown.failureCode()).isEqualTo(KnowledgeIndexFailureCode.EMBEDDING_PROVIDER_FAILURE);
        assertThat(thrown).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(thrown.getMessage())
                .as("上游响应体不得进入消息")
                .doesNotContain("429").doesNotContain("quota").doesNotContain("HTTP");
    }

    @Test
    void ourOwnFailureLogKeepsOnlyTheFailureCodeAndExceptionClassName() {
        String sentinel = "SENTINEL-CHUNK-BODY-正文-绝不进日志";
        RecordingEmbeddingModel model = new RecordingEmbeddingModel();
        model.failure = new IllegalStateException("upstream rejected: " + sentinel);

        Logger adapterLogger = (Logger) LoggerFactory.getLogger(DashScopeKnowledgeEmbeddingAdapter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        adapterLogger.addAppender(appender);
        try {
            assertThatThrownBy(() -> new DashScopeKnowledgeEmbeddingAdapter(model)
                    .embedAll(List.of(sentinel), DESCRIPTOR))
                    .isInstanceOf(DocumentIndexingException.class);
        }
        finally {
            adapterLogger.detachAppender(appender);
        }

        List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).isNotEmpty();
        assertThat(messages).allSatisfy(message -> {
            assertThat(message).contains(KnowledgeIndexFailureCode.EMBEDDING_PROVIDER_FAILURE.name());
            assertThat(message).as("自有日志只记异常类名").contains(IllegalStateException.class.getName());
            assertThat(message).as("上游 message 可能回显请求内容，绝不入日志").doesNotContain(sentinel);
            assertThat(message).doesNotContain("upstream rejected");
        });
        assertThat(appender.list).allSatisfy(event ->
                assertThat(event.getThrowableProxy()).as("不得打印堆栈（首行就是 message）").isNull());
    }

    @Test
    void malformedResponsesAreReportedAsInvalidEmbeddingResponse() {
        assertInvalidResponse("null 列表", model -> model.returnNullList = true);
        assertInvalidResponse("少一条", model -> model.dropOneResult = true);
        assertInvalidResponse("多一条", model -> model.extraResult = true);
        assertInvalidResponse("null 向量对象", model -> model.returnNullEmbeddingObject = true);
    }

    @Test
    void aNullVectorValueIsPassedThroughForTheApplicationLayerToReject() {
        // 适配器只搬运「数量、对象与归位」，维度/有限性/非零性由应用层统一校验：
        // 这里明确记录这条分工，避免将来有人误以为适配器会兜住所有畸形响应
        RecordingEmbeddingModel model = new RecordingEmbeddingModel();
        model.returnNullVector = true;

        List<float[]> vectors = new DashScopeKnowledgeEmbeddingAdapter(model)
                .embedAll(List.of("文本"), DESCRIPTOR);

        assertThat(vectors).hasSize(1);
        assertThat(vectors.get(0)).isNull();
    }

    // ---------- 配置属性 ----------

    @Test
    void defaultsAreDisabledAndMatchTheDatabaseContract() {
        KnowledgeEmbeddingProperties properties = new KnowledgeEmbeddingProperties();

        assertThat(properties.isEnabled()).as("默认必须关闭：默认环境不创建 EmbeddingModel").isFalse();
        assertThat(properties.getProvider()).isEqualTo("dashscope");
        assertThat(properties.getModel()).isEqualTo("text-embedding-v4");
        assertThat(properties.getDimensions()).isEqualTo(1024);
        assertThat(properties.getBatchSize()).isEqualTo(10);
        assertThat(properties.descriptor()).isEqualTo(EmbeddingDescriptor.of("dashscope", "text-embedding-v4"));
        assertThat(KnowledgeEmbeddingProperties.MAX_BATCH_SIZE).isEqualTo(10);
    }

    @Test
    void validateRejectsInconsistentConfiguration() {
        assertThatThrownBy(() -> properties(1023, 10).validate()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dimensions");
        assertThatThrownBy(() -> properties(1025, 10).validate()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> properties(1024, 0).validate()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("batch-size");
        assertThatThrownBy(() -> properties(1024, 11).validate()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> {
            KnowledgeEmbeddingProperties properties = properties(1024, 10);
            properties.setProvider(" ");
            properties.validate();
        }).isInstanceOf(IllegalStateException.class).hasMessageContaining("provider");
        assertThatThrownBy(() -> {
            KnowledgeEmbeddingProperties properties = properties(1024, 10);
            properties.setModel(null);
            properties.validate();
        }).isInstanceOf(IllegalStateException.class).hasMessageContaining("model");
    }

    @Test
    void validateAcceptsTheDocumentedConfiguration() {
        for (int batchSize : new int[] { 1, 5, 10 }) {
            properties(1024, batchSize).validate();
        }
    }

    private static KnowledgeEmbeddingProperties properties(int dimensions, int batchSize) {
        KnowledgeEmbeddingProperties properties = new KnowledgeEmbeddingProperties();
        properties.setDimensions(dimensions);
        properties.setBatchSize(batchSize);
        return properties;
    }

    private void assertInvalidResponse(String caseName,
            java.util.function.Consumer<RecordingEmbeddingModel> mutation) {

        RecordingEmbeddingModel model = new RecordingEmbeddingModel();
        mutation.accept(model);
        DashScopeKnowledgeEmbeddingAdapter adapter = new DashScopeKnowledgeEmbeddingAdapter(model);

        assertThatThrownBy(() -> adapter.embedAll(List.of("文本", "第二段", "第三段"), DESCRIPTOR)).as(caseName)
                .isInstanceOf(DocumentIndexingException.class)
                .extracting(thrown -> ((DocumentIndexingException) thrown).failureCode())
                .isEqualTo(KnowledgeIndexFailureCode.INVALID_EMBEDDING_RESPONSE);
    }

    /**
     * 手写 {@link EmbeddingModel} 替身：记录请求，并可注入各种畸形响应与乱序结果。
     *
     * <p>每条向量的首值固定为 {@code 100 + index}：这样「结果是否按 index 归位」可以直接由数值断言。</p>
     */
    private static final class RecordingEmbeddingModel implements EmbeddingModel {

        private final List<EmbeddingRequest> requests = new ArrayList<>();

        private RuntimeException failure;

        private boolean returnNullList;

        private boolean dropOneResult;

        private boolean extraResult;

        private boolean returnNullVector;

        private boolean returnNullEmbeddingObject;

        /** 上游返回结果的顺序（元素值是各结果的 index）；{@code null} 表示自然顺序。 */
        private int[] returnedOrder;

        /** 让该位置的结果 index 为 {@code null}。 */
        private int nullIndexAt = -1;

        private int dimensionsCalls;

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            this.requests.add(request);
            if (this.failure != null) {
                throw this.failure;
            }
            if (this.returnNullList) {
                return new EmbeddingResponse(null);
            }

            int count = request.getInstructions().size();
            int[] order = this.returnedOrder == null ? naturalOrder(count) : this.returnedOrder;

            List<Embedding> results = new ArrayList<>();
            for (int position = 0; position < order.length; position++) {
                int declaredIndex = order[position];
                Integer index = position == this.nullIndexAt ? null : declaredIndex;
                results.add(new Embedding(vector(declaredIndex), index));
            }
            if (this.returnNullVector && !results.isEmpty()) {
                results.set(0, new Embedding(null, 0));
            }
            if (this.returnNullEmbeddingObject && !results.isEmpty()) {
                results.set(0, null);
            }
            if (this.dropOneResult && !results.isEmpty()) {
                results.remove(results.size() - 1);
            }
            if (this.extraResult) {
                results.add(new Embedding(vector(count), count));
            }
            return new EmbeddingResponse(results);
        }

        private static int[] naturalOrder(int count) {
            int[] order = new int[count];
            for (int index = 0; index < count; index++) {
                order[index] = index;
            }
            return order;
        }

        @Override
        public float[] embed(Document document) {
            return vector(0);
        }

        @Override
        public int dimensions() {
            this.dimensionsCalls++;
            throw new IllegalStateException("适配器不得调用 dimensions()");
        }

        private float[] vector(int declaredIndex) {
            float[] vector = new float[EmbeddingDescriptor.REQUIRED_DIMENSIONS];
            java.util.Arrays.fill(vector, 100.0f + declaredIndex);
            return vector;
        }

        @Override
        public EmbeddingResponse embedForResponse(List<String> texts) {
            return call(new EmbeddingRequest(texts, (EmbeddingOptions) null));
        }
    }
}
