package com.flowdesk.application.knowledge;

import com.flowdesk.application.knowledge.port.out.ContentSource;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentContentStore;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentIdGenerator;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentRepository;
import com.flowdesk.application.knowledge.port.out.KnowledgeTimeProvider;
import com.flowdesk.application.knowledge.port.out.StoredContent;
import com.flowdesk.application.knowledge.port.out.VersionedKnowledgeDocument;
import com.flowdesk.domain.knowledge.KnowledgeDocument;
import com.flowdesk.domain.knowledge.KnowledgeDocumentId;
import com.flowdesk.domain.knowledge.KnowledgeDocumentStatus;
import com.flowdesk.domain.knowledge.Sha256Digest;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 应用层测试用的记账替身。
 *
 * <p>刻意<b>不是</b>用 Mockito：断言的是「按什么顺序调用了什么」这类客观事实，
 * 用显式实现读起来更清楚，也避免引入新依赖。</p>
 */
final class RecordingKnowledgePorts {

    private RecordingKnowledgePorts() {
    }

    /**
     * 记录调用顺序的内容存储替身。
     *
     * <p>它会<b>真的读完内容流</b>并计算 SHA-256 —— 这样应用层的限流与格式校验
     * （它们作用在流上）在单元测试里也会被真正执行，而不是被替身绕过。</p>
     */
    static final class RecordingContentStore implements KnowledgeDocumentContentStore {

        private final List<String> calls = new ArrayList<>();

        private final StringBuilder storedBytes = new StringBuilder();

        private String contentKey = "content-key-1";

        private RuntimeException storeFailure;

        private RuntimeException deleteFailure;

        private int storeCalls;

        private int deleteCalls;

        private String deletedKey;

        private KnowledgeDocumentId lastDocumentId;

        private long lastSizeBytes;

        @Override
        public StoredContent store(KnowledgeDocumentId documentId, ContentSource source) {
            this.calls.add("store");
            this.storeCalls++;
            this.lastDocumentId = documentId;
            if (this.storeFailure != null) {
                throw this.storeFailure;
            }

            long total = 0L;
            MessageDigest digest = sha256();
            try (InputStream input = source.openStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    digest.update(buffer, 0, read);
                    this.storedBytes.append(new String(buffer, 0, read, java.nio.charset.StandardCharsets.ISO_8859_1));
                    total += read;
                }
            }
            catch (IOException ex) {
                throw new KnowledgeApplicationException(
                        KnowledgeApplicationErrorCode.CONTENT_STORAGE_FAILURE, "内容存储失败", ex);
            }

            if (total == 0L) {
                // 真实适配器在移动之前就拒绝空内容，替身保持同样的契约
                throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.EMPTY_DOCUMENT_CONTENT,
                        "上传内容为空");
            }
            this.lastSizeBytes = total;
            return new StoredContent(this.contentKey, total, Sha256Digest.of(
                    HexFormat.of().formatHex(digest.digest())));
        }

        @Override
        public boolean delete(String contentKey) {
            this.calls.add("delete");
            this.deleteCalls++;
            this.deletedKey = contentKey;
            if (this.deleteFailure != null) {
                throw this.deleteFailure;
            }
            return true;
        }

        private static MessageDigest sha256() {
            try {
                return MessageDigest.getInstance("SHA-256");
            }
            catch (NoSuchAlgorithmException ex) {
                throw new IllegalStateException(ex);
            }
        }

        void failStoreWith(RuntimeException failure) {
            this.storeFailure = failure;
        }

        void failDeleteWith(RuntimeException failure) {
            this.deleteFailure = failure;
        }

        void useContentKey(String key) {
            this.contentKey = key;
        }

        List<String> calls() {
            return List.copyOf(this.calls);
        }

        int storeCalls() {
            return this.storeCalls;
        }

        int deleteCalls() {
            return this.deleteCalls;
        }

        String deletedKey() {
            return this.deletedKey;
        }

        String contentKey() {
            return this.contentKey;
        }

        KnowledgeDocumentId lastDocumentId() {
            return this.lastDocumentId;
        }

        long lastSizeBytes() {
            return this.lastSizeBytes;
        }

        /** 已写入内容的字节（按 ISO-8859-1 逐字节还原），用于校验流被完整读取。 */
        byte[] storedBytes() {
            return this.storedBytes.toString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        }
    }

    /**
     * 记录插入、读取与 CAS 更新的元数据仓储替身。
     *
     * <p>{@link #update} 实现了端口的 CAS 语义（版本不匹配即冲突、成功则版本加 1），
     * 因此应用层的「领取」与「失败补偿」在单测里也会走真实的并发检查逻辑。</p>
     */
    static final class RecordingDocumentRepository implements KnowledgeDocumentRepository {

        private final List<String> calls = new ArrayList<>();

        private final List<Long> updatedExpectedVersions = new ArrayList<>();

        private RuntimeException insertFailure;

        private RuntimeException stickyUpdateFailure;

        private RuntimeException nextUpdateFailure;

        private KnowledgeDocumentStatus failingUpdateStatus;

        private RuntimeException statusScopedUpdateFailure;

        private int insertCalls;

        private int findCalls;

        private int updateCalls;

        private KnowledgeDocument lastInserted;

        private KnowledgeDocument lastUpdated;

        private VersionedKnowledgeDocument found;

        private long version;

        @Override
        public VersionedKnowledgeDocument insert(KnowledgeDocument document) {
            this.calls.add("insert");
            this.insertCalls++;
            this.lastInserted = document;
            if (this.insertFailure != null) {
                throw this.insertFailure;
            }
            return new VersionedKnowledgeDocument(document, 0L);
        }

        @Override
        public Optional<VersionedKnowledgeDocument> findById(KnowledgeDocumentId documentId) {
            this.calls.add("findById");
            this.findCalls++;
            if (this.found != null && this.found.document().id().equals(documentId)) {
                return Optional.of(this.found);
            }
            return Optional.empty();
        }

        @Override
        public VersionedKnowledgeDocument update(KnowledgeDocument document, long expectedVersion) {
            this.calls.add("update");
            this.updateCalls++;
            this.lastUpdated = document;
            this.updatedExpectedVersions.add(expectedVersion);
            if (this.stickyUpdateFailure != null) {
                throw this.stickyUpdateFailure;
            }
            if (this.failingUpdateStatus != null && document.status() == this.failingUpdateStatus) {
                throw this.statusScopedUpdateFailure;
            }
            if (this.nextUpdateFailure != null) {
                RuntimeException failure = this.nextUpdateFailure;
                this.nextUpdateFailure = null;
                throw failure;
            }
            if (this.found == null) {
                throw new KnowledgeApplicationException(
                        KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_NOT_FOUND, "知识文档不存在");
            }
            if (this.version != expectedVersion) {
                throw new KnowledgeApplicationException(
                        KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_VERSION_CONFLICT,
                        "文档版本已变化");
            }
            this.version = expectedVersion + 1L;
            this.found = new VersionedKnowledgeDocument(document, this.version);
            return this.found;
        }

        void failInsertWith(RuntimeException failure) {
            this.insertFailure = failure;
        }

        /** 之后每一次 update 都失败（用于「领取阶段」失败）。 */
        void failEveryUpdateWith(RuntimeException failure) {
            this.stickyUpdateFailure = failure;
        }

        /** 只让下一次 update 失败（用于「失败补偿本身失败」）。 */
        void failNextUpdateWith(RuntimeException failure) {
            this.nextUpdateFailure = failure;
        }

        /**
         * 只让「写入处于指定状态的文档」失败。
         *
         * <p>用于精确模拟「领取成功、但失败补偿写库被拒」：领取写的是 {@code PARSING}，
         * 补偿写的是 {@code PARSE_FAILED}，两者可以分别注入。</p>
         */
        void failUpdateForStatus(KnowledgeDocumentStatus status, RuntimeException failure) {
            this.failingUpdateStatus = status;
            this.statusScopedUpdateFailure = failure;
        }

        void willFind(KnowledgeDocument document, long version) {
            this.found = new VersionedKnowledgeDocument(document, version);
            this.version = version;
        }

        List<String> calls() {
            return List.copyOf(this.calls);
        }

        List<Long> updatedExpectedVersions() {
            return List.copyOf(this.updatedExpectedVersions);
        }

        int insertCalls() {
            return this.insertCalls;
        }

        int findCalls() {
            return this.findCalls;
        }

        int updateCalls() {
            return this.updateCalls;
        }

        KnowledgeDocument lastInserted() {
            return this.lastInserted;
        }

        KnowledgeDocument lastUpdated() {
            return this.lastUpdated;
        }

        /**
         * @return 仓储当前持有的文档快照（含版本），从未写入过时返回 {@code null}
         */
        VersionedKnowledgeDocument found() {
            return this.found;
        }
    }

    /**
     * 记录调用次数的标识生成器。
     */
    static final class RecordingIdGenerator implements KnowledgeDocumentIdGenerator {

        private KnowledgeDocumentId next = KnowledgeTestSupport.DOCUMENT_ID;

        private int calls;

        @Override
        public KnowledgeDocumentId nextId() {
            this.calls++;
            return this.next;
        }

        void willReturn(KnowledgeDocumentId id) {
            this.next = id;
        }

        int calls() {
            return this.calls;
        }
    }

    /**
     * 记录调用次数的时间端口，可指定固定时间与步长。
     */
    static final class RecordingTimeProvider implements KnowledgeTimeProvider {

        private Instant current;

        private final java.time.Duration step;

        private int calls;

        RecordingTimeProvider(Instant initial) {
            this(initial, java.time.Duration.ZERO);
        }

        RecordingTimeProvider(Instant initial, java.time.Duration step) {
            this.current = initial;
            this.step = step;
        }

        @Override
        public Instant now() {
            this.calls++;
            Instant value = this.current;
            this.current = this.current.plus(this.step);
            return value;
        }

        int calls() {
            return this.calls;
        }
    }
}
