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
 * <h2>失败码契约（FD-0010-R1 / FD-0010-R2 修正）</h2>
 * <p>失败有四种归宿。<b>只有在「成功领取了索引且失败补偿 CAS 成功」这一种情况下，
 * 数据库里的 {@code index_failure_code} 才与抛出的
 * {@link com.flowdesk.application.knowledge.index.DocumentIndexingException#failureCode()} 一致</b>
 * （HTTP 层再把它映射成同名的 {@code failureCode}）。</p>
 * <table border="1">
 *   <caption>失败映射</caption>
 *   <tr><th>失败</th><th>补偿</th><th>数据库</th><th>对外</th></tr>
 *   <tr><td>版本冲突 / 状态不允许索引 / 文档不存在</td><td>不写失败态</td>
 *       <td>保持原状态（如仍是 {@code INDEXING}）</td>
 *       <td>412 / 409 / 404（原样上抛）</td></tr>
 *   <tr><td>领取之前（读文档 / 领取 CAS）的读取或存储失败</td><td>不写失败态</td>
 *       <td>没有任何 {@code INDEX_FAILED} 是安全可写的（文档还没被领取）</td>
 *       <td>项目自己的错误码（如 500，<b>没有</b> {@code failureCode}）</td></tr>
 *   <tr><td>领取之后的失败，补偿 CAS <b>成功</b></td><td>{@code INDEX_FAILED} + 失败码</td>
 *       <td>{@code index_failure_code} == 对外 {@code failureCode}</td>
 *       <td>{@code DocumentIndexingException}（已有失败码的原样保留，不重新包装）</td></tr>
 *   <tr><td>领取之后的失败，补偿 CAS <b>失败</b></td>
 *       <td>补偿异常只作为 suppressed 附加</td>
 *       <td>可能仍是 {@code INDEXING}，也可能已被并发请求改动</td>
 *       <td>根异常的失败码保持不变（补偿失败不影响对外结论）</td></tr>
 * </table>
 * <p>「不重新包装」是刻意的：FD-0010 曾经在这里把完成阶段的异常统一包成
 * {@code METADATA_STORAGE_FAILURE}，结果真实的向量写入失败在响应里丢掉了 {@code failureCode}。</p>
 * <p>「补偿失败时数据库不一定与响应一致」也是刻意的表述：补偿本身就是一次可能失败的写操作，
 * 宣称「永远一致」会把一个未被证明的结论写进文档。</p>
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
            // 向量生成阶段的兜底失败码是「上游调用失败」：只有在本层拿不到更精确语义时才用它
            throw failIndexing(document, claimedVersion, failure,
                    KnowledgeIndexFailureCode.EMBEDDING_PROVIDER_FAILURE);
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
            // 完成阶段的兜底失败码是「向量写入失败」：pgvector/JDBC/批处理/提交失败都归到它
            throw failIndexing(document, claimedVersion, failure,
                    KnowledgeIndexFailureCode.VECTOR_STORAGE_FAILURE);
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
     * 索引失败的统一处理（FD-0010-R1 / FD-0010-R2 修正措辞）：先决定「要不要写失败态」，
     * 再决定「对外抛出什么」。
     *
     * <p><b>准确的契约</b>（不宣称绝对的「永远一致」）：</p>
     * <ul>
     *   <li>补偿 CAS <b>成功</b>时，数据库里的 {@code index_failure_code} 与本方法返回的
     *       {@link DocumentIndexingException#failureCode()} 是同一个值（HTTP 层再映射成同名
     *       {@code failureCode}）；</li>
     *   <li>补偿 CAS <b>失败</b>时，返回的根异常及其失败码<b>保持不变</b>，补偿异常只作为
     *       {@code suppressed} 附加；此时数据库可能仍停在 {@code INDEXING}，
     *       也可能已被并发请求改动 —— 这一条不保证数据库与响应一致；</li>
     *   <li>版本冲突 / 状态不允许索引 / 文档不存在：不写失败态（412 / 409 / 404）。</li>
     * </ul>
     * <p>领取之前的读取或存储失败根本不进入本方法：那时文档还没被领取，
     * 不存在可以安全持久化的 {@code INDEX_FAILED} 状态。</p>
     *
     * <h2>三类处理</h2>
     * <ol>
     *   <li><b>不写失败态</b>（版本冲突、状态不允许索引、文档不存在）：当前请求没有资格给文档
     *       盖失败戳，异常原样上抛（412 / 409 / 404）；</li>
     *   <li><b>已经是 {@link DocumentIndexingException}</b>：它自带稳定失败码，
     *       <b>原样保留</b>，绝不重新包装（包装会让响应丢掉 {@code failureCode}）；</li>
     *   <li><b>其它运行时异常</b>：用兜底失败码包装成 {@link DocumentIndexingException}
     *       （原始异常只作为 cause 保留在服务端）。</li>
     * </ol>
     *
     * @param document       文档聚合
     * @param claimedVersion 领取后的版本（数据库当前版本）
     * @param failure        原始失败
     * @param fallback       兜底失败码（本层拿不到更精确语义时使用）
     * @return 要向上抛出的异常
     */
    private RuntimeException failIndexing(KnowledgeDocument document, long claimedVersion,
            RuntimeException failure, KnowledgeIndexFailureCode fallback) {

        KnowledgeIndexFailureCode failureCode = failureCodeOf(failure, fallback);
        if (failureCode == null) {
            return failure;
        }
        // 根因决定对外异常：已经是稳定失败码的异常原样保留，其它用兜底码包装
        RuntimeException rootCause = failure instanceof DocumentIndexingException ? failure
                : new DocumentIndexingException(failureCode, messageOf(failureCode), failure);

        RuntimeException compensationFailure = markFailed(document, claimedVersion, failureCode);
        if (compensationFailure != null && compensationFailure != failure) {
            rootCause.addSuppressed(compensationFailure);
        }
        return rootCause;
    }

    /**
     * 失败补偿：把文档 CAS 成 {@code INDEX_FAILED}。
     *
     * <p>完成阶段失败时聚合可能已经被 {@code markIndexed} 推进到 {@code INDEXED}，
     * 而数据库写入已经整体回滚；这时用当前字段重建一个 {@code INDEXING} 快照再记录失败，
     * 否则文档会永远停在 {@code INDEXING} 这个谁也领不走的状态。</p>
     *
     * @param document       文档聚合
     * @param claimedVersion 领取后的版本（数据库当前版本）
     * @param failureCode    要持久化的稳定失败码
     * @return 补偿自身的失败；{@code null} 表示补偿成功
     */
    private RuntimeException markFailed(KnowledgeDocument document, long claimedVersion,
            KnowledgeIndexFailureCode failureCode) {

        try {
            KnowledgeDocument target = document.status() == KnowledgeDocumentStatus.INDEXING
                    ? document
                    : indexingSnapshotOf(document);
            target.markIndexFailed(failureCode, this.timeProvider.now());
            this.documentRepository.update(target, claimedVersion);
            return null;
        }
        catch (RuntimeException compensationFailure) {
            // 补偿失败只作为 suppressed 附加在根因上，绝不覆盖根因
            return compensationFailure;
        }
    }

    /**
     * @param failure  原始失败
     * @param fallback 兜底失败码
     * @return 要记录的稳定失败码；{@code null} 表示「不应该写失败态」
     */
    private static KnowledgeIndexFailureCode failureCodeOf(RuntimeException failure,
            KnowledgeIndexFailureCode fallback) {

        if (failure instanceof DocumentIndexingException indexingFailure) {
            return indexingFailure.failureCode();
        }
        if (failure instanceof KnowledgeApplicationException applicationFailure) {
            return switch (applicationFailure.errorCode()) {
                case KNOWLEDGE_DOCUMENT_VERSION_CONFLICT, KNOWLEDGE_DOCUMENT_NOT_INDEXABLE,
                        KNOWLEDGE_DOCUMENT_NOT_FOUND -> null;
                // 存储类/内部一致性类失败：对索引而言都是「写入没成功」
                default -> KnowledgeIndexFailureCode.VECTOR_STORAGE_FAILURE;
            };
        }
        if (failure instanceof KnowledgeDomainException) {
            // 领域层拒绝通常意味着切片或状态数据不自洽
            return KnowledgeIndexFailureCode.CHUNK_DATA_INVALID;
        }
        return fallback;
    }

    /**
     * @param failureCode 稳定失败码
     * @return 服务端诊断用的固定文案（绝不包含上游文本、SQL 或连接信息）
     */
    private static String messageOf(KnowledgeIndexFailureCode failureCode) {
        return switch (failureCode) {
            case EMBEDDING_PROVIDER_FAILURE -> "向量服务调用失败";
            case INVALID_EMBEDDING_RESPONSE -> "向量服务返回的数据不合法";
            case VECTOR_STORAGE_FAILURE -> "索引结果写入失败";
            case CHUNK_DATA_INVALID -> "切片数据不自洽";
        };
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
