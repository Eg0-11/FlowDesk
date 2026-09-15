package com.flowdesk.application.knowledge;

import com.flowdesk.application.knowledge.port.out.KnowledgeQueryEmbeddingPort;
import com.flowdesk.application.knowledge.port.out.KnowledgeVectorMatch;
import com.flowdesk.application.knowledge.port.out.KnowledgeVectorSearchPort;
import com.flowdesk.domain.knowledge.EmbeddingDescriptor;
import com.flowdesk.domain.knowledge.KnowledgeDocumentId;
import com.flowdesk.domain.knowledge.KnowledgeQueryEmbedding;
import com.flowdesk.domain.knowledge.Sha256Digest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 检索用例的手写替身（RAG 4/6）。
 *
 * <p>与项目其它替身一样：只记录发生了什么、可注入失败，不使用 Mockito。
 * 记录的内容刻意包含「调用顺序」，因为「先模型后数据库」「非法输入零调用」这类契约
 * 只能通过顺序与计数证明。</p>
 */
final class RecordingRetrievalPorts {

    /** 全局调用顺序：{@code query} 表示查询向量端口，{@code search} 表示向量检索端口。 */
    private final List<String> callOrder = new ArrayList<>();

    RecordingRetrievalPorts() {
    }

    static String vector() {
        return "vector";
    }

    static String search() {
        return "search";
    }

    /**
     * 查询向量替身：记录 query 与描述符，可注入上游失败或畸形返回。
     */
    final class RecordingQueryEmbeddingPort implements KnowledgeQueryEmbeddingPort {

        private final List<String> queries = new ArrayList<>();

        private final List<EmbeddingDescriptor> descriptors = new ArrayList<>();

        private RuntimeException failure;

        private float[] nextVector;

        @Override
        public float[] embedQuery(String query, EmbeddingDescriptor descriptor) {
            callOrder.add("query");
            this.queries.add(query);
            this.descriptors.add(descriptor);
            if (this.failure != null) {
                throw this.failure;
            }
            // 默认返回 null：用于验证「端口返回 null 向量」被当成检索内部失败
            return this.nextVector == null ? null : this.nextVector.clone();
        }

        void failWith(RuntimeException failure) {
            this.failure = failure;
        }

        void willReturn(float[] vector) {
            this.nextVector = vector.clone();
        }

        /** 让端口返回 {@code null} 向量（违反契约），用于验证失败的映射。 */
        void willReturnNull() {
            this.nextVector = null;
        }

        List<String> queries() {
            return List.copyOf(this.queries);
        }

        List<EmbeddingDescriptor> descriptors() {
            return List.copyOf(this.descriptors);
        }

        int calls() {
            return this.queries.size();
        }
    }

    /**
     * 向量检索替身：记录查询向量、阈值与 topK，可按顺序返回命中或注入失败。
     */
    final class RecordingVectorSearchPort implements KnowledgeVectorSearchPort {

        private final List<KnowledgeQueryEmbedding> queries = new ArrayList<>();

        private final List<Double> minScores = new ArrayList<>();

        private final List<Integer> topKs = new ArrayList<>();

        private List<KnowledgeVectorMatch> matches = List.of();

        private RuntimeException failure;

        @Override
        public List<KnowledgeVectorMatch> search(KnowledgeQueryEmbedding queryEmbedding, double minScore, int topK) {
            callOrder.add("search");
            this.queries.add(queryEmbedding);
            this.minScores.add(minScore);
            this.topKs.add(topK);
            if (this.failure != null) {
                throw this.failure;
            }
            return this.matches;
        }

        void willReturn(List<KnowledgeVectorMatch> matches) {
            // 刻意用 ArrayList 而不是 List.copyOf：后者拒绝 null 元素，
            // 而「端口返回了 null 命中」正是需要被验证的违约情况之一
            this.matches = new ArrayList<>(matches);
        }

        /** 让端口返回 {@code null}（违反契约），用于验证失败的映射。 */
        void willReturnNull() {
            this.matches = null;
        }

        void failWith(RuntimeException failure) {
            this.failure = failure;
        }

        List<KnowledgeQueryEmbedding> queries() {
            return List.copyOf(this.queries);
        }

        List<Double> minScores() {
            return List.copyOf(this.minScores);
        }

        List<Integer> topKs() {
            return List.copyOf(this.topKs);
        }

        int calls() {
            return this.queries.size();
        }
    }

    /**
     * @return 调用顺序（{@code query} / {@code search}）
     */
    List<String> callOrder() {
        return List.copyOf(this.callOrder);
    }

    /**
     * 造一条命中。
     *
     * @param documentId 文档标识
     * @param chunkIndex 切片序号
     * @param score      相似度
     * @return 命中
     */
    static KnowledgeVectorMatch match(UUID documentId, int chunkIndex, double score) {
        return new KnowledgeVectorMatch(KnowledgeDocumentId.of(documentId), 4L, "VPN 故障处理手册",
                chunkIndex, Sha256Digest.of("0123456789abcdef".repeat(4)), "chunk-" + chunkIndex, score);
    }

    /**
     * 造一条命中（可指定标题与正文，用于 Unicode 场景）。
     *
     * @param documentId 文档标识
     * @param chunkIndex 切片序号
     * @param score      相似度
     * @param title      标题
     * @param content    正文
     * @return 命中
     */
    static KnowledgeVectorMatch match(UUID documentId, int chunkIndex, double score, String title,
            String content) {

        return new KnowledgeVectorMatch(KnowledgeDocumentId.of(documentId), 7L, title, chunkIndex,
                Sha256Digest.of("0123456789abcdef".repeat(4)), content, score);
    }

    /**
     * @param value 填充值
     * @return 1024 维向量
     */
    static float[] vector(float value) {
        float[] vector = new float[EmbeddingDescriptor.REQUIRED_DIMENSIONS];
        java.util.Arrays.fill(vector, value);
        return vector;
    }
}
