package com.flowdesk.application.knowledge;

import com.flowdesk.application.knowledge.index.DocumentIndexingException;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentChunkStore;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentEmbeddingStore;
import com.flowdesk.application.knowledge.port.out.KnowledgeEmbeddingPort;
import com.flowdesk.application.knowledge.port.out.VersionedKnowledgeDocument;
import com.flowdesk.domain.knowledge.EmbeddingDescriptor;
import com.flowdesk.domain.knowledge.KnowledgeDocument;
import com.flowdesk.domain.knowledge.KnowledgeDocumentChunk;
import com.flowdesk.domain.knowledge.KnowledgeDocumentChunkEmbedding;
import com.flowdesk.domain.knowledge.KnowledgeDocumentId;
import com.flowdesk.domain.knowledge.KnowledgeIndexFailureCode;
import com.flowdesk.domain.knowledge.Sha256Digest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 索引链路（FD-0010）测试替身：切片分页读取、向量生成、向量原子写入。
 *
 * <p>与其它替身一样刻意不使用 Mockito：这里要断言的既有「按什么顺序、分几批调用了什么」，
 * 也有「返回的向量与请求切片是否一一对应」这类客观事实。</p>
 */
final class RecordingIndexingPorts {

    private RecordingIndexingPorts() {
    }

    /**
     * 切片分页读取替身：可注入错序、断号、归属错误与「页大小不符」等异常。
     */
    static final class RecordingChunkPages implements KnowledgeDocumentChunkStore {

        private final List<KnowledgeDocumentChunk> chunks = new ArrayList<>();

        private final List<String> reads = new ArrayList<>();

        private boolean completeParsingCalled;

        private boolean shortPage;

        private boolean wrongOwner;

        private boolean reversePages;

        private boolean skipFirstChunk;

        void serve(List<KnowledgeDocumentChunk> seed) {
            this.chunks.clear();
            this.chunks.addAll(seed);
        }

        void failWithShortPage() {
            this.shortPage = true;
        }

        void failWithWrongOwner() {
            this.wrongOwner = true;
        }

        void failWithReversedOrder() {
            this.reversePages = true;
        }

        void failBySkippingTheFirstChunk() {
            this.skipFirstChunk = true;
        }

        @Override
        public long countChunks(KnowledgeDocumentId documentId) {
            return this.chunks.size();
        }

        @Override
        public List<KnowledgeDocumentChunk> findChunks(KnowledgeDocumentId documentId, int offset, int limit) {
            this.reads.add(offset + ":" + limit);
            if (this.skipFirstChunk && offset == 0) {
                return List.of();
            }
            int end = Math.min(offset + limit, this.chunks.size());
            if (this.shortPage) {
                end = Math.max(offset, end - 1);
            }
            List<KnowledgeDocumentChunk> page = new ArrayList<>(this.chunks.subList(offset, end));
            if (this.wrongOwner && !page.isEmpty()) {
                page.set(0, new KnowledgeDocumentChunk(KnowledgeTestSupport.randomDocumentId(),
                        page.get(0).chunkIndex(), page.get(0).content(), page.get(0).codePointCount(),
                        page.get(0).sha256(), page.get(0).createdAt()));
            }
            if (this.reversePages) {
                java.util.Collections.reverse(page);
            }
            return List.copyOf(page);
        }

        @Override
        public VersionedKnowledgeDocument completeParsing(KnowledgeDocument parsedDocument, long expectedVersion,
                List<KnowledgeDocumentChunk> newChunks) {

            this.completeParsingCalled = true;
            throw new IllegalStateException("索引链路不应调用 completeParsing");
        }

        List<String> reads() {
            return List.copyOf(this.reads);
        }

        boolean completeParsingCalled() {
            return this.completeParsingCalled;
        }
    }

    /**
     * 向量生成替身：记录每批文本，可按批返回指定的向量或抛错。
     *
     * <p>默认返回「第 i 个向量在第 i 维为 1」的合法向量，因此顺序断言是客观可验证的。</p>
     */
    static final class RecordingEmbeddingPort implements KnowledgeEmbeddingPort {

        private final List<List<String>> batches = new ArrayList<>();

        private final List<EmbeddingDescriptor> descriptors = new ArrayList<>();

        private final Deque<Object> scripted = new ArrayDeque<>();

        private RuntimeException stickyFailure;

        private int failAtBatch = -1;

        private boolean returnNullList;

        private boolean returnNullElement;

        private boolean returnTooFew;

        private boolean returnTooMany;

        private int vectorLength = EmbeddingDescriptor.REQUIRED_DIMENSIONS;

        private Float fillValue;

        /** 只影响第 N 批（从 0 开始）：让第 N 批返回预置结果或抛错。 */
        void failAtBatch(int batchIndex, RuntimeException failure) {
            this.failAtBatch = batchIndex;
            this.scripted.add(failure);
        }

        void failEveryCallWith(RuntimeException failure) {
            this.stickyFailure = failure;
        }

        void returnNullListForNextCall() {
            this.returnNullList = true;
        }

        void returnNullElementForNextCall() {
            this.returnNullElement = true;
        }

        void returnTooFewForNextCall() {
            this.returnTooFew = true;
        }

        void returnTooManyForNextCall() {
            this.returnTooMany = true;
        }

        void useVectorLength(int length) {
            this.vectorLength = length;
        }

        void useFillValue(Float value) {
            this.fillValue = value;
        }

        @Override
        public List<float[]> embedAll(List<String> texts, EmbeddingDescriptor descriptor) {
            int batchIndex = this.batches.size();
            this.batches.add(List.copyOf(texts));
            this.descriptors.add(descriptor);

            if (this.stickyFailure != null) {
                throw this.stickyFailure;
            }
            if (this.failAtBatch == batchIndex && !this.scripted.isEmpty()) {
                Object scriptedItem = this.scripted.poll();
                if (scriptedItem instanceof RuntimeException failure) {
                    throw failure;
                }
            }
            if (this.returnNullList) {
                this.returnNullList = false;
                return null;
            }

            List<float[]> vectors = new ArrayList<>(texts.size());
            for (int index = 0; index < texts.size(); index++) {
                vectors.add(vectorFor(index));
            }
            if (this.returnTooFew) {
                this.returnTooFew = false;
                vectors.remove(vectors.size() - 1);
            }
            if (this.returnTooMany) {
                this.returnTooMany = false;
                vectors.add(vectorFor(999));
            }
            if (this.returnNullElement) {
                this.returnNullElement = false;
                vectors.set(0, null);
            }
            // 刻意用允许 null 元素的包装：List.copyOf 会拒绝 null，
            // 那样「上游返回了 null 向量」这个反例就在替身内部炸掉，测不到服务端的校验
            return java.util.Collections.unmodifiableList(new ArrayList<>(vectors));
        }

        private float[] vectorFor(int index) {
            if (this.fillValue != null) {
                float[] vector = new float[this.vectorLength];
                java.util.Arrays.fill(vector, this.fillValue);
                return vector;
            }
            float[] vector = new float[this.vectorLength];
            if (this.vectorLength > 0) {
                vector[index % this.vectorLength] = 1.0f;
            }
            return vector;
        }

        List<List<String>> batches() {
            return List.copyOf(this.batches);
        }

        List<EmbeddingDescriptor> descriptors() {
            return List.copyOf(this.descriptors);
        }

        int batchCount() {
            return this.batches.size();
        }

        /** 所有请求文本，按批次顺序拼接。 */
        List<String> allRequestedTexts() {
            List<String> all = new ArrayList<>();
            this.batches.forEach(all::addAll);
            return List.copyOf(all);
        }
    }

    /**
     * 向量原子写入替身：记录调用参数，可注入失败。
     */
    static final class RecordingEmbeddingStore implements KnowledgeDocumentEmbeddingStore {

        private final List<KnowledgeDocumentChunkEmbedding> received = new ArrayList<>();

        private final List<Long> expectedVersions = new ArrayList<>();

        private RuntimeException failure;

        private int calls;

        private long versionToReturn = -1L;

        @Override
        public VersionedKnowledgeDocument completeIndexing(KnowledgeDocument indexedDocument,
                long expectedVersion, List<KnowledgeDocumentChunkEmbedding> embeddings) {

            this.calls++;
            this.expectedVersions.add(expectedVersion);
            this.received.clear();
            this.received.addAll(embeddings);
            if (this.failure != null) {
                throw this.failure;
            }
            long version = this.versionToReturn >= 0 ? this.versionToReturn : expectedVersion + 1L;
            return new VersionedKnowledgeDocument(indexedDocument, version);
        }

        void failWith(RuntimeException failure) {
            this.failure = failure;
        }

        void willReturnVersion(long version) {
            this.versionToReturn = version;
        }

        int calls() {
            return this.calls;
        }

        List<Long> expectedVersions() {
            return List.copyOf(this.expectedVersions);
        }

        List<KnowledgeDocumentChunkEmbedding> received() {
            return List.copyOf(this.received);
        }
    }

    /**
     * 构造一段切片：内容为 {@code chunk-<index>}，摘要随内容确定。
     *
     * @param documentId 文档标识
     * @param count      切片数量
     * @return 切片列表
     */
    static List<KnowledgeDocumentChunk> chunks(KnowledgeDocumentId documentId, int count) {
        List<KnowledgeDocumentChunk> chunks = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            String content = "chunk-" + index;
            chunks.add(new KnowledgeDocumentChunk(documentId, index, content,
                    content.codePointCount(0, content.length()),
                    Sha256Digest.of(sha256Hex(content)), Instant.parse("2026-05-01T10:00:04Z")));
        }
        return List.copyOf(chunks);
    }

    static String sha256Hex(String content) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        }
        catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * 向量服务失败的常见形态：超时 / 限流 / 5xx。
     *
     * @return 携带稳定失败码的异常
     */
    static DocumentIndexingException providerFailure() {
        return new DocumentIndexingException(KnowledgeIndexFailureCode.EMBEDDING_PROVIDER_FAILURE,
                "向量服务调用失败");
    }
}
