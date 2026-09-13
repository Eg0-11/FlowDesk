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
 *       校验全部通过后<b>原子移动</b>到最终位置；</li>
 *   <li>用存储返回的真实大小与摘要创建聚合，插入元数据；</li>
 *   <li>插入失败则<b>补偿删除</b>刚写入的内容，并对外只暴露固定的服务端错误。</li>
 * </ol>
 *
 * <p><b>文件复制期间不持有数据库事务</b>：整个第 4 步与数据库无关，元数据插入是第 5 步的
 * 单条语句。慢速上传因此不会占用连接、也不会长时间占着事务。</p>
 *
 * <h2>失败语义</h2>
 * <ul>
 *   <li>校验失败 → {@code INVALID_UPLOAD_COMMAND} / {@code EMPTY_DOCUMENT_CONTENT}
 *       / {@code UNSUPPORTED_DOCUMENT_FORMAT} / {@code DOCUMENT_TOO_LARGE}，
 *       并且<b>不调用</b>内容存储与元数据存储；</li>
 *   <li>内容存储失败 → 原样向上传递（适配器已用应用层错误码表达）；</li>
 *   <li>元数据失败 → 先补偿删除内容，再抛 {@code METADATA_STORAGE_FAILURE}；
 *       <b>补偿本身失败也只可能留下孤立文件</b>，绝不会留下指向不存在内容的元数据记录。</li>
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

        // ① 标题与文件名：先规范化（strip / 取最后一段），再由领域值对象裁决
        //    这一步在打开任何文件之前完成，因此非法输入不会触发任何 I/O
        ContentSource source = command.contentSource();
        if (source == null) {
            throw invalidUpload("上传内容不能为空");
        }
        DocumentTitle title = DocumentTitle.of(stripToNull(command.title()));
        OriginalFilename fileName = OriginalFilename.of(
                OriginalFilenames.basename(source.declaredFileName()));

        // ② 格式识别：扩展名决定候选格式，声明的 Content-Type 必须与之一致
        DocumentFormat format = DocumentFormat.fromFileName(fileName.value())
                .orElseThrow(() -> new KnowledgeApplicationException(
                        KnowledgeApplicationErrorCode.UNSUPPORTED_DOCUMENT_FORMAT,
                        "不支持的文档扩展名，本阶段仅支持：" + DocumentFormat.SUPPORTED_EXTENSIONS));
        if (!DocumentMediaTypes.isCompatible(format, source.declaredContentType())) {
            throw new KnowledgeApplicationException(
                    KnowledgeApplicationErrorCode.UNSUPPORTED_DOCUMENT_FORMAT,
                    "声明的媒体类型与文档格式不一致");
        }

        // ③ 早期拒绝：声明的大小明显超限就没必要开始读（实际限制仍在读取路径上）
        if (source.declaredSize() > this.maxUploadBytes) {
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.DOCUMENT_TOO_LARGE,
                    "上传内容超过允许的最大大小");
        }

        // ④ 标识与时间
        KnowledgeDocumentId documentId = this.idGenerator.nextId();
        Instant uploadedAt = this.timeProvider.now();

        // ⑤ 内容存储：流式写入 + 校验 + 原子移动；无论成败内容源都会被关闭
        try (ContentSource guarded = GuardedContentSource.guard(source, format, this.maxUploadBytes)) {
            StoredContent stored = this.contentStore.store(documentId, guarded);

            KnowledgeDocument document = KnowledgeDocument.create(documentId, title, fileName, format,
                    DocumentMediaTypes.canonical(format), stored.sizeBytes(), stored.sha256(),
                    stored.contentKey(), uploadedAt);

            return toView(insertOrCompensate(document, stored.contentKey()));
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
     * 插入元数据；失败时补偿删除已写入的内容。
     *
     * <p>补偿用内容键（由文档标识生成）精确定位本次上传的对象，因此不可能误删其它文档的内容。
     * 补偿自身失败只吞掉异常：此时可能的后果是「留下一个孤立文件」，
     * 而对外行为已经被固定为服务端错误，数据库里也不会出现指向不存在内容的记录。</p>
     */
    private VersionedKnowledgeDocument insertOrCompensate(KnowledgeDocument document, String contentKey) {
        try {
            return this.documentRepository.insert(document);
        }
        catch (RuntimeException ex) {
            compensate(contentKey);
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.METADATA_STORAGE_FAILURE,
                    "元数据写入失败", ex);
        }
    }

    private void compensate(String contentKey) {
        try {
            this.contentStore.delete(contentKey);
        }
        catch (RuntimeException ignored) {
            // 见方法注释：补偿失败只可能留下孤立文件，不能让更严重的后果发生
        }
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
