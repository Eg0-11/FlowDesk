package com.flowdesk.application.knowledge;

import com.flowdesk.application.knowledge.port.out.DocumentChunker;
import com.flowdesk.application.knowledge.port.out.DocumentTextParser;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentChunkStore;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentContentReader;
import com.flowdesk.application.knowledge.port.out.VersionedKnowledgeDocument;
import com.flowdesk.domain.knowledge.DocumentFormat;
import com.flowdesk.domain.knowledge.KnowledgeDocument;
import com.flowdesk.domain.knowledge.KnowledgeDocumentChunk;
import com.flowdesk.domain.knowledge.KnowledgeDocumentId;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 解析链路（FD-0009）测试替身：内容读取、文本提取、切片、切片存储。
 *
 * <p>与 {@link RecordingKnowledgePorts} 一样刻意不使用 Mockito：
 * 这里要断言的既有「调用顺序」，也有「流是否被真正读完/关闭」这类客观事实。</p>
 *
 * <p>所有替身都<b>记录调用顺序</b>，因此测试可以断言「解析与切片不在任何数据库事务里」——
 * 在应用层这表现为：{@code update(领取)} → 读取 → 解析 → 切片 → {@code completeParsing}，
 * 中间不存在任何仓储写操作。</p>
 */
final class RecordingParsingPorts {

    private RecordingParsingPorts() {
    }

    /**
     * 内容读取替身：可注入字节内容或失败，并记录流是否被关闭。
     */
    static final class RecordingContentReader implements KnowledgeDocumentContentReader {

        private byte[] content = "知识库文档内容".getBytes(StandardCharsets.UTF_8);

        private RuntimeException failure;

        private final List<String> openedKeys = new ArrayList<>();

        private boolean streamOpened;

        private boolean streamClosed;

        @Override
        public InputStream openStream(String contentKey) {
            this.openedKeys.add(contentKey);
            if (this.failure != null) {
                throw this.failure;
            }
            this.streamOpened = true;
            return new TrackingInputStream(this.content.clone(), () -> this.streamClosed = true);
        }

        void willReturn(byte[] bytes) {
            this.content = bytes.clone();
        }

        void willReturn(String text) {
            this.content = text.getBytes(StandardCharsets.UTF_8);
        }

        void failWith(RuntimeException failure) {
            this.failure = failure;
        }

        List<String> openedKeys() {
            return List.copyOf(this.openedKeys);
        }

        boolean streamOpened() {
            return this.streamOpened;
        }

        /** 调用方（应用服务）必须关闭流：这里断言的是客观事实，而不是「契约写在注释里」。 */
        boolean streamClosed() {
            return this.streamClosed;
        }
    }

    /**
     * 文本提取替身：录下收到的格式与流状态，可返回固定文本或抛错。
     */
    static final class RecordingTextParser implements DocumentTextParser {

        private String text = "解析出来的文本";

        private RuntimeException failure;

        private final List<DocumentFormat> formats = new ArrayList<>();

        private boolean streamClosedWhenCalled;

        private String seenBody;

        @Override
        public String parse(InputStream content, DocumentFormat format) {
            this.formats.add(format);
            try {
                this.seenBody = new String(content.readAllBytes(), StandardCharsets.UTF_8);
                this.streamClosedWhenCalled = false;
            }
            catch (IOException ex) {
                throw new IllegalStateException("替身读取失败", ex);
            }
            if (this.failure != null) {
                throw this.failure;
            }
            return this.text;
        }

        void willReturn(String text) {
            this.text = text;
        }

        void failWith(RuntimeException failure) {
            this.failure = failure;
        }

        List<DocumentFormat> formats() {
            return List.copyOf(this.formats);
        }

        String seenBody() {
            return this.seenBody;
        }

        boolean streamClosedWhenCalled() {
            return this.streamClosedWhenCalled;
        }
    }

    /**
     * 切片替身：录下收到的文本，可返回固定切片或抛错。
     */
    static final class RecordingChunker implements DocumentChunker {

        private List<String> pieces = List.of("解析出来的文本");

        private RuntimeException failure;

        private final List<String> seenTexts = new ArrayList<>();

        @Override
        public List<String> chunk(String extractedText) {
            this.seenTexts.add(extractedText);
            if (this.failure != null) {
                throw this.failure;
            }
            return List.copyOf(this.pieces);
        }

        void willReturn(List<String> pieces) {
            this.pieces = List.copyOf(pieces);
        }

        void failWith(RuntimeException failure) {
            this.failure = failure;
        }

        List<String> seenTexts() {
            return List.copyOf(this.seenTexts);
        }

        int calls() {
            return this.seenTexts.size();
        }
    }

    /**
     * 切片存储替身：记录 {@code completeParsing} 的入参快照，可抛错模拟写入失败。
     *
     * <p>它在被调用时<b>立即深拷贝</b>文档状态：这样即使应用层之后又改了同一个聚合对象
     * （例如失败补偿），测试断言到的仍然是「当时真正传进来的那一刻」的状态。</p>
     */
    static final class RecordingChunkStore implements KnowledgeDocumentChunkStore {

        private RuntimeException failure;

        private int completeParsingCalls;

        private long completeParsingExpectedVersion = -1L;

        private List<KnowledgeDocumentChunk> receivedChunks = List.of();

        private com.flowdesk.domain.knowledge.KnowledgeDocumentStatus statusAtCall;

        private java.time.Instant updatedAtAtCall;

        private java.time.Instant parsedAtAtCall;

        private long versionToReturn = 1L;

        @Override
        public VersionedKnowledgeDocument completeParsing(KnowledgeDocument parsedDocument, long expectedVersion,
                List<KnowledgeDocumentChunk> chunks) {

            this.completeParsingCalls++;
            this.completeParsingExpectedVersion = expectedVersion;
            this.receivedChunks = List.copyOf(chunks);
            this.statusAtCall = parsedDocument.status();
            this.updatedAtAtCall = parsedDocument.updatedAt();
            this.parsedAtAtCall = parsedDocument.parsedAt();
            if (this.failure != null) {
                throw this.failure;
            }
            return new VersionedKnowledgeDocument(parsedDocument, this.versionToReturn);
        }

        @Override
        public long countChunks(KnowledgeDocumentId documentId) {
            return this.receivedChunks.size();
        }

        @Override
        public List<KnowledgeDocumentChunk> findChunks(KnowledgeDocumentId documentId, int offset, int limit) {
            return this.receivedChunks.stream().skip(offset).limit(limit).toList();
        }

        void failWith(RuntimeException failure) {
            this.failure = failure;
        }

        void willReturnVersion(long version) {
            this.versionToReturn = version;
        }

        int completeParsingCalls() {
            return this.completeParsingCalls;
        }

        long completeParsingExpectedVersion() {
            return this.completeParsingExpectedVersion;
        }

        List<KnowledgeDocumentChunk> receivedChunks() {
            return this.receivedChunks;
        }

        com.flowdesk.domain.knowledge.KnowledgeDocumentStatus statusAtCall() {
            return this.statusAtCall;
        }

        java.time.Instant updatedAtAtCall() {
            return this.updatedAtAtCall;
        }

        java.time.Instant parsedAtAtCall() {
            return this.parsedAtAtCall;
        }
    }

    /**
     * 读取端口返回的流：关闭状态可被外部观察。
     */
    private static final class TrackingInputStream extends ByteArrayInputStream {

        private final Runnable onClose;

        private boolean closed;

        TrackingInputStream(byte[] content, Runnable onClose) {
            super(content);
            this.onClose = onClose;
        }

        @Override
        public void close() throws IOException {
            if (!this.closed) {
                this.closed = true;
                this.onClose.run();
            }
            super.close();
        }
    }
}
