package com.flowdesk.application.knowledge.service;

import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.application.knowledge.command.UploadKnowledgeDocumentCommand;
import com.flowdesk.application.knowledge.content.DocumentMediaTypes;
import com.flowdesk.application.knowledge.content.GuardedContentSource;
import com.flowdesk.application.knowledge.content.OriginalFilenames;
import com.flowdesk.application.knowledge.port.in.KnowledgeDocumentQueryUseCase;
import com.flowdesk.application.knowledge.port.in.UploadKnowledgeDocumentUseCase;
import com.flowdesk.application.knowledge.port.out.ContentSource;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentContentStore;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentIdGenerator;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentRepository;
import com.flowdesk.application.knowledge.port.out.KnowledgeTimeProvider;
import com.flowdesk.application.knowledge.port.out.StoredContent;
import com.flowdesk.application.knowledge.port.out.VersionedKnowledgeDocument;
import com.flowdesk.application.knowledge.query.GetKnowledgeDocumentQuery;
import com.flowdesk.application.knowledge.view.KnowledgeDocumentView;
import com.flowdesk.domain.knowledge.DocumentFormat;
import com.flowdesk.domain.knowledge.DocumentTitle;
import com.flowdesk.domain.knowledge.KnowledgeDocument;
import com.flowdesk.domain.knowledge.KnowledgeDocumentId;
import com.flowdesk.domain.knowledge.KnowledgeDomainException;
import com.flowdesk.domain.knowledge.OriginalFilename;
import java.time.Instant;
import java.util.Objects;

/**
 * 知识文档用例服务：纯 Java、无状态、框架无关。
 *
 * <p>依赖全部经构造器注入，因此同一份输入永远得到同一份输出，可直接单元测试。
 * 本类不认识 {@code MultipartFile}、不认识磁盘与数据库，只认识四个输出端口。</p>
 *
 * <h2>上传时序（固定）</h2>
 * <ol>
 *   <li>校验命令与文件元数据（标题、文件名、扩展名、声明类型）—— <b>此时还没有打开文件，
 *       也还没有碰过任何存储</b>；</li>
 *   <li>按客户端声明的大小做一次早期拒绝（声明不可信，只用来省掉明显过大的读取）；</li>
 *   <li>生成文档标识、取当前时间；</li>
 *   <li>把内容源包成「限流 + 格式校验」，交给内容存储：流式写入临时文件并计算 SHA-256，
 *       校验全部通过后<b>原子发布</b>；</li>
 *   <li>用存储返回的真实大小与摘要创建聚合，插入元数据；</li>
 *   <li>插入失败则<b>补偿删除</b>刚发布的内容。</li>
 * </ol>
 *
 * <h2>内容源的生命周期</h2>
 * <p>只要命令与内容源都非 {@code null}，<b>之后的所有路径</b>（包括前置校验失败、标识/时间端口失败、
 * 存储失败）都在 try-with-resources 覆盖范围内，因此内容源<b>恰好关闭一次</b>。
 * 前置校验失败时仍然不会打开内容流（流由内容存储打开）。</p>
 *
 * <h2>三个阶段与补偿边界</h2>
 * <table border="1">
 *   <caption>补偿状态机</caption>
 *   <tr><th>阶段</th><th>含义</th><th>失败时的处理</th></tr>
 *   <tr><td>A</td><td>内容尚未成功发布</td><td>由内容存储适配器清理临时文件；<b>不删除</b>任何已发布对象</td></tr>
 *   <tr><td>B</td><td>内容已发布，元数据尚未确认写入</td><td>对任意运行时失败都<b>尝试一次</b>
 *       {@code delete(contentKey)}，然后按安全错误码向上抛</td></tr>
 *   <tr><td>C</td><td>元数据已成功写入</td><td>后续失败（例如视图转换）<b>绝不删除内容</b> ——
 *       否则会留下指向不存在内容的数据库记录</td></tr>
 * </table>
 *
 * <p><b>文件复制期间不持有数据库事务</b>：第 4 步与数据库无关，元数据只有第 5 步一条语句。</p>
 *
 * <h2>失败语义</h2>
 * <ul>
 *   <li>调用方输入问题 → {@code INVALID_UPLOAD_COMMAND} / {@code EMPTY_DOCUMENT_CONTENT}
 *       / {@code UNSUPPORTED_DOCUMENT_FORMAT} / {@code DOCUMENT_TOO_LARGE}，
 *       并且<b>不调用</b>内容存储与元数据存储；</li>
 *   <li>存储端口自己抛出的应用层异常（含存储失败、内容为空、格式不符）原样向上传递；</li>
 *   <li><b>端口返回值违反领域不变量</b>（大小、摘要、内容键不合法）→ {@code KNOWLEDGE_INTERNAL_ERROR}，
 *       即服务端错误，<b>不会</b>落进领域异常的「调用方输入」映射；</li>
 *   <li>标识/时间端口抛异常或返回 {@code null} → 同样映射为 {@code KNOWLEDGE_INTERNAL_ERROR}，
 *       对外不出现 NPE 与内部文案；</li>
 *   <li>补偿删除失败<b>不覆盖</b>原始异常，只作为 suppressed 附加，对外仍是固定的服务端错误。</li>
 * </ul>
 */
public final class KnowledgeDocumentApplicationService
        implements UploadKnowledgeDocumentUseCase, KnowledgeDocumentQueryUseCase {

    private final KnowledgeDocumentRepository documentRepository;

    private final KnowledgeDocumentContentStore contentStore;

    private final KnowledgeDocumentIdGenerator idGenerator;

    private final KnowledgeTimeProvider timeProvider;

    private final long maxUploadBytes;

    /**
     * @param documentRepository 元数据存储端口
     * @param contentStore       原始内容存储端口
     * @param idGenerator        标识生成端口
     * @param timeProvider       时间端口
     * @param maxUploadBytes     允许的最大上传字节数，必须大于 0
     */
    public KnowledgeDocumentApplicationService(KnowledgeDocumentRepository documentRepository,
            KnowledgeDocumentContentStore contentStore,
            KnowledgeDocumentIdGenerator idGenerator,
            KnowledgeTimeProvider timeProvider,
            long maxUploadBytes) {

        this.documentRepository = Objects.requireNonNull(documentRepository, "documentRepository 不能为 null");
        this.contentStore = Objects.requireNonNull(contentStore, "contentStore 不能为 null");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator 不能为 null");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider 不能为 null");
        if (maxUploadBytes <= 0L) {
            throw new IllegalArgumentException("maxUploadBytes 必须大于 0");
        }
        this.maxUploadBytes = maxUploadBytes;
    }

    @Override
    public KnowledgeDocumentView upload(UploadKnowledgeDocumentCommand command) {
        if (command == null) {
            throw invalidUpload("上传命令不能为空");
        }
        ContentSource source = command.contentSource();
        if (source == null) {
            throw invalidUpload("上传内容不能为空");
        }

        // 从这里开始，无论成功还是失败，内容源都会被关闭恰好一次
        try (ContentSource contentSource = source) {
            DocumentTitle title = DocumentTitle.of(stripToNull(command.title()));
            OriginalFilename fileName = OriginalFilename.of(
                    OriginalFilenames.basename(contentSource.declaredFileName()));
            DocumentFormat format = resolveFormat(fileName, contentSource.declaredContentType());
            requireDeclaredSizeWithinLimit(contentSource);

            KnowledgeDocumentId documentId = requireDocumentId();
            Instant uploadedAt = requireUploadTime();
            ContentSource guarded = GuardedContentSource.guard(contentSource, format, this.maxUploadBytes);

            return storeThenPersist(guarded, title, fileName, format, documentId, uploadedAt);
        }
    }

    @Override
    public KnowledgeDocumentView get(GetKnowledgeDocumentQuery query) {
        if (query == null || query.documentId() == null) {
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.INVALID_QUERY,
                    "查询条件不能为空");
        }

        return this.documentRepository.findById(query.documentId())
                .map(KnowledgeDocumentApplicationService::toView)
                .orElseThrow(() -> new KnowledgeApplicationException(
                        KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_NOT_FOUND, "知识文档不存在"));
    }

    /**
     * 阶段 B：内容发布之后、元数据确认写入之前。
     *
     * <p>视图转换刻意放在 {@code try} <b>之外</b>：元数据已经写成功之后，任何失败都不允许再删除内容，
     * 否则数据库里就会留下一条指向不存在内容的记录。</p>
     */
    private KnowledgeDocumentView storeThenPersist(ContentSource guarded, DocumentTitle title,
            OriginalFilename fileName, DocumentFormat format, KnowledgeDocumentId documentId,
            Instant uploadedAt) {

        StoredContent stored = null;
        VersionedKnowledgeDocument saved;
        try {
            stored = this.contentStore.store(documentId, guarded);
            saved = this.documentRepository.insert(
                    toDocument(stored, title, fileName, format, documentId, uploadedAt));
        }
        catch (RuntimeException failure) {
            if (stored != null) {
                compensate(stored.contentKey(), failure);
            }
            throw toSafeFailure(failure);
        }

        // 阶段 C：元数据已确认写入
        return toView(saved);
    }

    /**
     * 用存储返回的真实大小与摘要构造聚合。
     *
     * <p>标题与文件名在前置校验阶段已经通过领域校验，因此这里若仍失败，
     * 只可能来自端口返回值（大小、摘要、内容键）—— 那是服务端问题，由调用方映射为 500。</p>
     */
    private static KnowledgeDocument toDocument(StoredContent stored, DocumentTitle title,
            OriginalFilename fileName, DocumentFormat format, KnowledgeDocumentId documentId,
            Instant uploadedAt) {

        return KnowledgeDocument.create(documentId, title, fileName, format,
                DocumentMediaTypes.canonical(format), stored.sizeBytes(), stored.sha256(),
                stored.contentKey(), uploadedAt);
    }

    private static DocumentFormat resolveFormat(OriginalFilename fileName, String declaredContentType) {
        DocumentFormat format = DocumentFormat.fromFileName(fileName.value())
                .orElseThrow(() -> new KnowledgeApplicationException(
                        KnowledgeApplicationErrorCode.UNSUPPORTED_DOCUMENT_FORMAT,
                        "不支持的文档扩展名，本阶段仅支持：" + DocumentFormat.SUPPORTED_EXTENSIONS));
        if (!DocumentMediaTypes.isCompatible(format, declaredContentType)) {
            throw new KnowledgeApplicationException(
                    KnowledgeApplicationErrorCode.UNSUPPORTED_DOCUMENT_FORMAT,
                    "声明的媒体类型与文档格式不一致");
        }
        return format;
    }

    private void requireDeclaredSizeWithinLimit(ContentSource source) {
        if (source.declaredSize() > this.maxUploadBytes) {
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.DOCUMENT_TOO_LARGE,
                    "上传内容超过允许的最大大小");
        }
    }

    /**
     * 读取标识端口的返回值：抛异常或返回 {@code null} 都归为内部错误，绝不把 NPE 暴露出去。
     */
    private KnowledgeDocumentId requireDocumentId() {
        KnowledgeDocumentId documentId;
        try {
            documentId = this.idGenerator.nextId();
        }
        catch (RuntimeException ex) {
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.KNOWLEDGE_INTERNAL_ERROR,
                    "文档标识生成失败", ex);
        }
        if (documentId == null) {
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.KNOWLEDGE_INTERNAL_ERROR,
                    "文档标识生成失败");
        }
        return documentId;
    }

    /**
     * 读取时间端口的返回值：同上。
     */
    private Instant requireUploadTime() {
        Instant uploadedAt;
        try {
            uploadedAt = this.timeProvider.now();
        }
        catch (RuntimeException ex) {
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.KNOWLEDGE_INTERNAL_ERROR,
                    "上传时间读取失败", ex);
        }
        if (uploadedAt == null) {
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.KNOWLEDGE_INTERNAL_ERROR,
                    "上传时间读取失败");
        }
        return uploadedAt;
    }

    /**
     * 补偿删除：只尝试一次，且<b>不覆盖</b>原始异常（作为 suppressed 附加，用于服务端诊断）。
     *
     * <p>内容键基于唯一文档标识生成，因此补偿只会命中本次上传发布的对象，
     * 不可能误删其它文档的内容。</p>
     */
    private void compensate(String contentKey, RuntimeException primaryFailure) {
        try {
            this.contentStore.delete(contentKey);
        }
        catch (RuntimeException compensationFailure) {
            if (compensationFailure != primaryFailure) {
                primaryFailure.addSuppressed(compensationFailure);
            }
        }
    }

    /**
     * 把阶段 B 的失败映射为对外安全的错误码。
     *
     * <p>关键点：端口返回值造成的领域异常必须变成 {@code KNOWLEDGE_INTERNAL_ERROR}（500），
     * 否则它会顺着领域异常的映射变成 400，把「服务端自己的问题」报成「调用方输入有问题」。</p>
     */
    private static KnowledgeApplicationException toSafeFailure(RuntimeException failure) {
        if (failure instanceof KnowledgeApplicationException applicationFailure) {
            return applicationFailure;
        }
        if (failure instanceof KnowledgeDomainException domainFailure) {
            return wrapWithSuppressed(KnowledgeApplicationErrorCode.KNOWLEDGE_INTERNAL_ERROR,
                    "内容存储返回了不合法的结果", domainFailure);
        }
        return wrapWithSuppressed(KnowledgeApplicationErrorCode.METADATA_STORAGE_FAILURE,
                "元数据写入失败", failure);
    }

    /**
     * 包装原始失败，并把它的 suppressed 异常（补偿删除的失败）一起带过来。
     *
     * <p>这样抛给上层的异常同时保留「原始 cause」与「补偿失败」，排障时不会丢信息，
     * 而对外的文案与错误码仍是固定的安全值。</p>
     */
    private static KnowledgeApplicationException wrapWithSuppressed(KnowledgeApplicationErrorCode errorCode,
            String message, RuntimeException failure) {

        KnowledgeApplicationException wrapper = new KnowledgeApplicationException(errorCode, message, failure);
        for (Throwable suppressed : failure.getSuppressed()) {
            wrapper.addSuppressed(suppressed);
        }
        return wrapper;
    }

    private static KnowledgeDocumentView toView(VersionedKnowledgeDocument versioned) {
        return KnowledgeDocumentView.from(versioned.document(), versioned.version());
    }

    private static KnowledgeApplicationException invalidUpload(String message) {
        return new KnowledgeApplicationException(KnowledgeApplicationErrorCode.INVALID_UPLOAD_COMMAND, message);
    }

    private static String stripToNull(String raw) {
        return raw == null ? null : OriginalFilenames.stripToNull(raw);
    }
}
