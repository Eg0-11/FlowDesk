package com.flowdesk.application.knowledge.service;

import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.application.knowledge.command.IndexKnowledgeDocumentCommand;
import com.flowdesk.application.knowledge.index.DocumentIndexingException;
import com.flowdesk.application.knowledge.port.in.IndexKnowledgeDocumentUseCase;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentChunkStore;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentEmbeddingStore;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentRepository;
import com.flowdesk.application.knowledge.port.out.KnowledgeEmbeddingPort;
import com.flowdesk.application.knowledge.port.out.KnowledgeTimeProvider;
import com.flowdesk.application.knowledge.port.out.VersionedKnowledgeDocument;
import com.flowdesk.application.knowledge.view.IndexedDocumentView;
import com.flowdesk.domain.knowledge.EmbeddingDescriptor;
import com.flowdesk.domain.knowledge.KnowledgeDocument;
import com.flowdesk.domain.knowledge.KnowledgeDocumentChunk;
import com.flowdesk.domain.knowledge.KnowledgeDocumentChunkEmbedding;
import com.flowdesk.domain.knowledge.KnowledgeDocumentId;
import com.flowdesk.domain.knowledge.KnowledgeDocumentStatus;
import com.flowdesk.domain.knowledge.KnowledgeDomainException;
import com.flowdesk.domain.knowledge.KnowledgeIndexFailureCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 文档索引用例服务：纯 Java、无状态、框架无关（FD-0010）。
 *
 * <h2>时序</h2>
 * <ol>
 *   <li><b>校验命令</b>：非法输入在<b>任何</b>仓储/模型调用之前就被拒绝；</li>
 *   <li><b>开关检查</b>：未启用 Embedding 时立刻返回
 *       {@code KNOWLEDGE_EMBEDDING_DISABLED}，<b>不读仓储、不改状态、不调用模型</b>；</li>
 *   <li><b>读取</b>文档；不存在 → 404；版本与 {@code If-Match} 不符 → 412（尚无任何写入）；</li>
 *   <li><b>状态检查</b>：只允许 {@code PARSED}/{@code INDEX_FAILED} → 否则 409；</li>
 *   <li><b>领取</b>：{@code markIndexing} → {@code repository.update(document, expectedVersion)} 做原子 CAS，版本 +1；</li>
 *   <li><b>事务之外</b>：按 {@code chunkIndex} 升序<b>分页</b>读取切片，每批最多 {@code batchSize} 条
 *       调用 {@link KnowledgeEmbeddingPort}，并逐批校验响应；</li>
 *   <li><b>完成</b>：{@code markIndexed} → {@link KnowledgeDocumentEmbeddingStore}，
 *       由适配器在<b>一个短事务</b>里校验切片一致性、替换向量、把文档置为 {@code INDEXED} 并版本 +1；</li>
 *   <li><b>失败补偿</b>：把文档从 {@code INDEXING} CAS 成 {@code INDEX_FAILED}（带稳定失败码）。
 *       补偿失败只作为 suppressed 附加，绝不覆盖根因；版本冲突与「状态不允许索引」不写成失败态。</li>
 * </ol>
 *
 * <h2>内存占用</h2>
 * <p>切片正文<b>分批</b>读取（每批最多 {@code batchSize} 条），不会一次性把整篇文档的切片文本加载进来；
 * 内存里只累积「已经校验过的 1024 维向量」。</p>
 *
 * <h2>已知边界</h2>
 * <p>索引是<b>同步</b>完成的：请求会一直等到全部批次完成并落库。超大文档可能触及客户端或反向代理超时；
 * 异步化方向见 ADR 0007。进程在 {@code INDEXING} 期间崩溃会留下悬挂状态（与解析阶段同样的边界）。</p>
 */
public final class KnowledgeDocumentIndexingService implements IndexKnowledgeDocumentUseCase {

    /** 单批最多允许的切片数：上游 Embedding 接口对一次请求的输入条数有上限。 */
    public static final int MAX_BATCH_SIZE = 10;

    private final KnowledgeDocumentRepository documentRepository;

    private final KnowledgeDocumentChunkStore chunkStore;

    private final KnowledgeEmbeddingPort embeddingPort;

    private final KnowledgeDocumentEmbeddingStore embeddingStore;

    private final KnowledgeTimeProvider timeProvider;

    private final boolean embeddingEnabled;

    private final EmbeddingDescriptor descriptor;

    private final int batchSize;

    /**
     * @param documentRepository 元数据仓储（含 CAS 更新）
     * @param chunkStore         切片分页读取端口
     * @param embeddingPort      向量生成端口
     * @param embeddingStore     向量原子写入端口
     * @param timeProvider       时间端口
     * @param embeddingEnabled   是否启用真实索引（默认 false）
     * @param descriptor         向量描述符（provider/model/dimensions）
     * @param batchSize          单批最大切片数，必须在 {@code 1..MAX_BATCH_SIZE}
     */
    public KnowledgeDocumentIndexingService(KnowledgeDocumentRepository documentRepository,
            KnowledgeDocumentChunkStore chunkStore,
            KnowledgeEmbeddingPort embeddingPort,
            KnowledgeDocumentEmbeddingStore embeddingStore,
            KnowledgeTimeProvider timeProvider,
            boolean embeddingEnabled,
            EmbeddingDescriptor descriptor,
            int batchSize) {

        this.documentRepository = Objects.requireNonNull(documentRepository, "documentRepository 不能为 null");
        this.chunkStore = Objects.requireNonNull(chunkStore, "chunkStore 不能为 null");
        this.embeddingPort = Objects.requireNonNull(embeddingPort, "embeddingPort 不能为 null");
        this.embeddingStore = Objects.requireNonNull(embeddingStore, "embeddingStore 不能为 null");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider 不能为 null");
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor 不能为 null");
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("batchSize 必须在 1.." + MAX_BATCH_SIZE + " 之间");
        }
        this.embeddingEnabled = embeddingEnabled;
        this.batchSize = batchSize;
    }

    @Override
    public IndexedDocumentView index(IndexKnowledgeDocumentCommand command) {
        requireValidCommand(command);

        if (!this.embeddingEnabled) {
            // 关闭状态直接拒绝：不读仓储、不改状态、不调用模型
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.KNOWLEDGE_EMBEDDING_DISABLED,
                    "当前环境未启用文档向量化");
        }

        KnowledgeDocumentId documentId = command.documentId();
        VersionedKnowledgeDocument current = this.documentRepository.findById(documentId)
                .orElseThrow(() -> new KnowledgeApplicationException(
                        KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_NOT_FOUND, "知识文档不存在"));

        if (current.version() != command.expectedVersion()) {
            throw versionConflict();
        }

        KnowledgeDocument document = current.document();
        requireIndexable(document);
        long claimedVersion = claim(document, current.version());

        // 领取成功：读取切片与模型调用全程不持有数据库事务
        List<KnowledgeDocumentChunkEmbedding> embeddings;
        try {
            embeddings = embedAllChunks(document);
        }
        catch (RuntimeException failure) {
            markFailed(document, claimedVersion, failure);
            throw failure;
        }

        try {
            document.markIndexed(this.timeProvider.now());
            VersionedKnowledgeDocument saved = this.embeddingStore.completeIndexing(document, claimedVersion,
                    embeddings);
            return new IndexedDocumentView(saved.document().id().value(), saved.document().title().value(),
                    saved.document().status(), saved.version(), embeddings.size(),
                    saved.document().embeddingProvider(), saved.document().embeddingModel(),
                    saved.document().embeddingDimensions() == null ? 0 : saved.document().embeddingDimensions(),
                    saved.document().indexedAt());
        }
        catch (RuntimeException failure) {
            markFailed(document, claimedVersion, failure);
            if (failure instanceof KnowledgeApplicationException applicationFailure) {
                throw applicationFailure;
            }
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.METADATA_STORAGE_FAILURE,
                    "索引结果写入失败", failure);
        }
    }

    /**
     * 命令级校验：全部发生在任何端口调用之前。
     *
     * @param command 索引命令
     */
    private static void requireValidCommand(IndexKnowledgeDocumentCommand command) {
        if (command == null || command.documentId() == null) {
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.INVALID_INDEX_COMMAND,
                    "索引命令不能为空");
        }
        if (command.expectedVersion() < 0L) {
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.INVALID_INDEX_COMMAND,
                    "期望版本不能为负数");
        }
    }

    /**
     * 只有「已经解析出切片」的文档才能索引。
     *
     * @param document 文档聚合
     */
    private static void requireIndexable(KnowledgeDocument document) {
        if (document.status() != KnowledgeDocumentStatus.PARSED
                && document.status() != KnowledgeDocumentStatus.INDEX_FAILED) {
            throw notIndexable();
        }
    }

    /**
     * 领取索引：领域校验转换合法性，仓储用 CAS 落地。
     *
     * @param document        文档聚合（仍处于 {@code PARSED} 或 {@code INDEX_FAILED}）
     * @param expectedVersion 读取时看到的版本
     * @return 领取后的版本；后续写入都用它作为 CAS 期望值
     */
    private long claim(KnowledgeDocument document, long expectedVersion) {
        try {
            document.markIndexing(this.descriptor, this.timeProvider.now());
        }
        catch (KnowledgeDomainException ex) {
            throw notIndexable();
        }
        return this.documentRepository.update(document, expectedVersion).version();
    }

    /**
     * 分批读取切片并生成向量：**每一批都校验之后**才继续下一批。
     *
     * @param document 已领取（{@code INDEXING}）的文档
     * @return 全部切片向量，按 {@code chunkIndex} 升序
     */
    private List<KnowledgeDocumentChunkEmbedding> embedAllChunks(KnowledgeDocument document) {
        long total = this.chunkStore.countChunks(document.id());
        if (total <= 0L) {
            // 已解析却没有切片：数据不自洽，不能凭空「索引成功」
            throw new DocumentIndexingException(KnowledgeIndexFailureCode.CHUNK_DATA_INVALID,
                    "文档没有可索引的切片");
        }

        List<KnowledgeDocumentChunkEmbedding> embeddings = new ArrayList<>((int) Math.min(total, 4096));
        int offset = 0;
        while (offset < total) {
            int limit = (int) Math.min(this.batchSize, total - offset);
            List<KnowledgeDocumentChunk> page = this.chunkStore.findChunks(document.id(), offset, limit);
            requirePageIsConsistent(document, page, offset, limit);
            embeddings.addAll(embedPage(document, page));
            offset += page.size();
        }
        return List.copyOf(embeddings);
    }

    /**
     * 分页结果必须与请求一致：数量正确、归属正确、序号连续。
     *
     * @param document 文档
     * @param page     读到的一页切片
     * @param offset   起始序号
     * @param limit    期望条数
     */
    private static void requirePageIsConsistent(KnowledgeDocument document, List<KnowledgeDocumentChunk> page,
            int offset, int limit) {

        if (page == null || page.size() != limit) {
            throw new DocumentIndexingException(KnowledgeIndexFailureCode.CHUNK_DATA_INVALID,
                    "切片分页结果与请求不一致");
        }
        for (int index = 0; index < page.size(); index++) {
            KnowledgeDocumentChunk chunk = page.get(index);
            if (chunk == null) {
                throw new DocumentIndexingException(KnowledgeIndexFailureCode.CHUNK_DATA_INVALID,
                        "切片分页结果包含空元素");
            }
            if (!document.id().equals(chunk.documentId())) {
                throw new DocumentIndexingException(KnowledgeIndexFailureCode.CHUNK_DATA_INVALID,
                        "切片归属的文档与目标文档不一致");
            }
            if (chunk.chunkIndex() != offset + index) {
                throw new DocumentIndexingException(KnowledgeIndexFailureCode.CHUNK_DATA_INVALID,
                        "切片序号必须从 0 开始连续递增");
            }
        }
    }

    /**
     * 对一批切片生成向量并逐条校验。
     *
     * @param document 文档
     * @param page     一页切片
     * @return 校验通过的向量
     */
    private List<KnowledgeDocumentChunkEmbedding> embedPage(KnowledgeDocument document,
            List<KnowledgeDocumentChunk> page) {

        List<String> texts = new ArrayList<>(page.size());
        for (KnowledgeDocumentChunk chunk : page) {
            texts.add(chunk.content());
        }

        List<float[]> vectors = this.embeddingPort.embedAll(List.copyOf(texts), this.descriptor);
        if (vectors == null || vectors.size() != page.size()) {
            throw new DocumentIndexingException(KnowledgeIndexFailureCode.INVALID_EMBEDDING_RESPONSE,
                    "向量数量与请求切片数量不一致");
        }

        List<KnowledgeDocumentChunkEmbedding> embeddings = new ArrayList<>(page.size());
        for (int index = 0; index < page.size(); index++) {
            KnowledgeDocumentChunk chunk = page.get(index);
            float[] vector = vectors.get(index);
            requireValidVector(vector);
            try {
                embeddings.add(new KnowledgeDocumentChunkEmbedding(chunk.documentId(), chunk.chunkIndex(),
                        chunk.sha256(), this.descriptor, vector));
            }
            catch (KnowledgeDomainException ex) {
                // 领域不变量兜底：与上面的显式校验语义相同，只是最后一道闸门
                throw new DocumentIndexingException(KnowledgeIndexFailureCode.INVALID_EMBEDDING_RESPONSE,
                        "向量不满足领域不变量", ex);
            }
        }
        return List.copyOf(embeddings);
    }

    /**
     * 向量合法性：非空、维度恰好正确、全部有限、不得全零。
     *
     * @param vector 上游返回的向量
     */
    private void requireValidVector(float[] vector) {
        if (vector == null) {
            throw new DocumentIndexingException(KnowledgeIndexFailureCode.INVALID_EMBEDDING_RESPONSE,
                    "向量不能为空");
        }
        if (vector.length != this.descriptor.dimensions()) {
            throw new DocumentIndexingException(KnowledgeIndexFailureCode.INVALID_EMBEDDING_RESPONSE,
                    "向量维度与配置不一致");
        }
        boolean anyNonZero = false;
        for (float value : vector) {
            if (Float.isNaN(value) || Float.isInfinite(value)) {
                throw new DocumentIndexingException(KnowledgeIndexFailureCode.INVALID_EMBEDDING_RESPONSE,
                        "向量必须全部是有限数值");
            }
            if (value != 0.0f) {
                anyNonZero = true;
            }
        }
        if (!anyNonZero) {
            throw new DocumentIndexingException(KnowledgeIndexFailureCode.INVALID_EMBEDDING_RESPONSE,
                    "向量不能全为零");
        }
    }

    /**
     * 失败补偿：把文档 CAS 成 {@code INDEX_FAILED}。
     *
     * <p>以下两类失败<b>不</b>写失败态：</p>
     * <ul>
     *   <li>版本冲突：说明文档已经被别人改过，当前请求无权给它盖失败戳；</li>
     *   <li>状态不允许索引：说明领取之前状态就不对（或已被并发请求领取）。</li>
     * </ul>
     * <p>完成阶段失败时聚合可能已经被 {@code markIndexed} 推进到 {@code INDEXED}，
     * 而数据库写入已经整体回滚；这时用当前字段重建一个 {@code INDEXING} 快照再记录失败，
     * 否则文档会永远停在 {@code INDEXING} 这个谁也领不走的状态。</p>
     *
     * @param document        文档聚合
     * @param claimedVersion  领取后的版本（数据库当前版本）
     * @param primaryFailure  原始失败
     */
    private void markFailed(KnowledgeDocument document, long claimedVersion, RuntimeException primaryFailure) {
        KnowledgeIndexFailureCode failureCode = failureCodeOf(primaryFailure);
        if (failureCode == null) {
            return;
        }
        try {
            KnowledgeDocument target = document.status() == KnowledgeDocumentStatus.INDEXING
                    ? document
                    : indexingSnapshotOf(document);
            target.markIndexFailed(failureCode, this.timeProvider.now());
            this.documentRepository.update(target, claimedVersion);
        }
        catch (RuntimeException compensationFailure) {
            if (compensationFailure != primaryFailure) {
                primaryFailure.addSuppressed(compensationFailure);
            }
        }
    }

    /**
     * @param failure 原始失败
     * @return 要记录的稳定失败码；{@code null} 表示「不应该写失败态」
     */
    private static KnowledgeIndexFailureCode failureCodeOf(RuntimeException failure) {
        if (failure instanceof DocumentIndexingException indexingFailure) {
            return indexingFailure.failureCode();
        }
        if (failure instanceof KnowledgeApplicationException applicationFailure) {
            return switch (applicationFailure.errorCode()) {
                case KNOWLEDGE_DOCUMENT_VERSION_CONFLICT, KNOWLEDGE_DOCUMENT_NOT_INDEXABLE,
                        KNOWLEDGE_DOCUMENT_NOT_FOUND -> null;
                default -> KnowledgeIndexFailureCode.VECTOR_STORAGE_FAILURE;
            };
        }
        if (failure instanceof KnowledgeDomainException) {
            // 领域层拒绝通常意味着切片或状态数据不自洽
            return KnowledgeIndexFailureCode.CHUNK_DATA_INVALID;
        }
        return KnowledgeIndexFailureCode.VECTOR_STORAGE_FAILURE;
    }

    /**
     * 用当前字段重建一个仍处于 {@code INDEXING} 的等价聚合（不改变原对象）。
     *
     * @param document 文档聚合
     * @return {@code INDEXING} 快照
     */
    private static KnowledgeDocument indexingSnapshotOf(KnowledgeDocument document) {
        return KnowledgeDocument.restore(document.id(), document.title().value(),
                document.originalFilename().value(), document.format(), document.mediaType(),
                document.sizeBytes(), document.sha256(), document.contentKey(),
                KnowledgeDocumentStatus.INDEXING, document.createdAt(), document.updatedAt(),
                document.parsedAt(), null, null,
                document.indexStartedAt(), null, null, null, document.embedding());
    }

    private static KnowledgeApplicationException notIndexable() {
        return new KnowledgeApplicationException(
                KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_NOT_INDEXABLE,
                "当前状态不允许索引");
    }

    private static KnowledgeApplicationException versionConflict() {
        return new KnowledgeApplicationException(
                KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_VERSION_CONFLICT,
                "文档版本已变化，请重新读取后再试");
    }
}
