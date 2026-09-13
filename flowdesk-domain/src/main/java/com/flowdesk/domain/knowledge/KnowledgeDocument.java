package com.flowdesk.domain.knowledge;

import java.time.Instant;

/**
 * 知识文档聚合根。
 *
 * <p>本阶段的文档是<b>不可变</b>的：上传即定型，没有状态流转，也没有字段修改方法。
 * 因此本类没有 setter，也没有任何会改变自身状态的方法。</p>
 *
 * <h2>核心不变量</h2>
 * <ul>
 *   <li>{@code id}、{@code format}、{@code status}、{@code sha256}、{@code contentKey}
 *       与两个时间都不得为空；</li>
 *   <li>{@code title} 必须已经 strip，长度 1～{@link DocumentTitle#MAX_LENGTH}
 *       （由 {@link DocumentTitle} 保证）；</li>
 *   <li>{@code originalFilename} 只能是<b>文件名</b>：不得含 {@code /}、{@code \}、
 *       NUL 或任何控制字符（由 {@link OriginalFilename} 保证）；</li>
 *   <li>{@code sizeBytes} 必须大于 0（零字节内容在内容存储阶段就被拒绝，
 *       这里再挡一次，避免「空文档」从别的入口进来）；</li>
 *   <li>{@code sha256} 必须是 64 位小写十六进制（由 {@link Sha256Digest} 保证）；</li>
 *   <li>{@code createdAt <= updatedAt}；新建文档两者相等。</li>
 * </ul>
 *
 * <h2>为什么这些校验在领域层</h2>
 * <p>标题、文件名、大小、摘要最终都会写进数据库并对外暴露。
 * 把它们放在聚合里，任何绕过 HTTP 的入口（运维脚本、后续的批量导入）都会被同一套规则拦住；
 * 而且这些规则<b>只写一遍</b> —— 应用层用同样的值对象在打开文件之前就完成校验。</p>
 *
 * <h2>domain 层不含任何技术类型</h2>
 * <p>本类只使用 JDK 类型：没有 Spring、没有 {@code MultipartFile}、没有 {@code JdbcClient}、
 * 没有 {@code java.nio.file.Path}。磁盘与数据库长什么样，领域层不关心。</p>
 */
public final class KnowledgeDocument {

    /** 内容键最大长度。 */
    public static final int MAX_CONTENT_KEY_LENGTH = 255;

    /** 媒体类型最大长度。 */
    public static final int MAX_MEDIA_TYPE_LENGTH = 128;

    private final KnowledgeDocumentId id;

    private final DocumentTitle title;

    private final OriginalFilename originalFilename;

    private final DocumentFormat format;

    private final String mediaType;

    private final long sizeBytes;

    private final Sha256Digest sha256;

    private final String contentKey;

    private final KnowledgeDocumentStatus status;

    private final Instant createdAt;

    private final Instant updatedAt;

    private KnowledgeDocument(KnowledgeDocumentId id,
                              DocumentTitle title,
                              OriginalFilename originalFilename,
                              DocumentFormat format,
                              String mediaType,
                              long sizeBytes,
                              Sha256Digest sha256,
                              String contentKey,
                              KnowledgeDocumentStatus status,
                              Instant createdAt,
                              Instant updatedAt) {

        this.id = requireId(id);
        this.title = requireTitle(title);
        this.originalFilename = requireFilename(originalFilename);
        this.format = requireFormat(format);
        this.mediaType = requireMediaType(mediaType);
        this.sizeBytes = requireSize(sizeBytes);
        this.sha256 = requireDigest(sha256);
        this.contentKey = requireContentKey(contentKey);
        this.status = requireStatus(status);
        this.createdAt = requireInstant(createdAt, "创建时间");
        this.updatedAt = requireInstant(updatedAt, "更新时间");
        requireTimeline(this.createdAt, this.updatedAt);
    }

    /**
     * 创建新上传的文档。
     *
     * <p>只接受一个时间点，因此 {@code createdAt} 必然等于 {@code updatedAt} ——
     * 「新上传时两个时间必须相等」是结构上成立的，而不是靠调用方自觉。</p>
     *
     * @param id               文档标识
     * @param title            标题值对象（已 strip）
     * @param originalFilename 文件名值对象（纯文件名）
     * @param format           文档格式
     * @param mediaType        实际使用的媒体类型
     * @param sizeBytes        实际内容字节数，必须大于 0
     * @param sha256           内容摘要
     * @param contentKey       内容存储键（由存储适配器生成的不透明值）
     * @param uploadedAt       上传时间，同时作为创建时间与更新时间
     * @return 新文档
     * @throws KnowledgeDomainException 任一不变量不成立
     */
    public static KnowledgeDocument create(KnowledgeDocumentId id,
            DocumentTitle title,
            OriginalFilename originalFilename,
            DocumentFormat format,
            String mediaType,
            long sizeBytes,
            Sha256Digest sha256,
            String contentKey,
            Instant uploadedAt) {

        return new KnowledgeDocument(id, title, originalFilename, format, mediaType, sizeBytes, sha256,
                contentKey, KnowledgeDocumentStatus.UPLOADED, uploadedAt, uploadedAt);
    }

    /**
     * 从持久化快照恢复。
     *
     * <p><b>不信任数据库内容</b>：与 {@link #create} 走完全相同的校验路径 ——
     * 标题、文件名、大小、摘要、媒体类型、内容键、时间都要重新验一遍。
     * 因此从库里读出来的非法快照会在恢复时立刻暴露，而不是被当作正常数据继续使用。</p>
     *
     * <p>恢复路径上的任何字段问题都统一归为 {@link KnowledgeErrorCode#INVALID_RESTORED_STATE}
     * （服务端内部错误），避免把「数据损坏」误报成「调用方输入错误」。</p>
     *
     * @param id               文档标识
     * @param title            标题原始文本
     * @param originalFilename 原始文件名原始文本
     * @param format           文档格式
     * @param mediaType        媒体类型
     * @param sizeBytes        内容字节数
     * @param sha256           内容摘要
     * @param contentKey       内容存储键
     * @param status           状态
     * @param createdAt        创建时间
     * @param updatedAt        更新时间
     * @return 恢复出的文档
     * @throws KnowledgeDomainException 快照不自洽
     */
    public static KnowledgeDocument restore(KnowledgeDocumentId id,
            String title,
            String originalFilename,
            DocumentFormat format,
            String mediaType,
            long sizeBytes,
            Sha256Digest sha256,
            String contentKey,
            KnowledgeDocumentStatus status,
            Instant createdAt,
            Instant updatedAt) {

        try {
            return new KnowledgeDocument(id, DocumentTitle.of(title), OriginalFilename.of(originalFilename),
                    format, mediaType, sizeBytes, sha256, contentKey, status, createdAt, updatedAt);
        }
        catch (KnowledgeDomainException ex) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_RESTORED_STATE,
                    "持久化的知识文档快照不自洽：" + ex.errorCode());
        }
    }

    private static KnowledgeDocumentId requireId(KnowledgeDocumentId value) {
        if (value == null) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_DOCUMENT_ID, "文档标识不能为空");
        }
        return value;
    }

    private static DocumentTitle requireTitle(DocumentTitle value) {
        if (value == null) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_TITLE, "标题不能为空");
        }
        return value;
    }

    private static OriginalFilename requireFilename(OriginalFilename value) {
        if (value == null) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_ORIGINAL_FILENAME,
                    "原始文件名不能为空");
        }
        return value;
    }

    private static DocumentFormat requireFormat(DocumentFormat value) {
        if (value == null) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_FORMAT, "文档格式不能为空");
        }
        return value;
    }

    private static String requireMediaType(String value) {
        if (value == null) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_MEDIA_TYPE, "媒体类型不能为空");
        }
        if (value.isBlank()) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_MEDIA_TYPE, "媒体类型不能为空白");
        }
        if (value.length() > MAX_MEDIA_TYPE_LENGTH) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_MEDIA_TYPE,
                    "媒体类型长度不能超过 " + MAX_MEDIA_TYPE_LENGTH + " 个字符");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isISOControl(character) || Character.isWhitespace(character)) {
                throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_MEDIA_TYPE,
                        "媒体类型不能包含空白或控制字符");
            }
        }
        return value;
    }

    private static long requireSize(long value) {
        if (value <= 0L) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_SIZE, "内容大小必须大于 0");
        }
        return value;
    }

    private static Sha256Digest requireDigest(Sha256Digest value) {
        if (value == null) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_DIGEST, "内容摘要不能为空");
        }
        return value;
    }

    /**
     * 内容键：由存储适配器生成的不透明值，这里只保证它「不像路径」。
     */
    private static String requireContentKey(String value) {
        if (value == null) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_CONTENT_KEY, "内容键不能为空");
        }
        if (value.isBlank()) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_CONTENT_KEY, "内容键不能为空白");
        }
        if (value.length() > MAX_CONTENT_KEY_LENGTH) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_CONTENT_KEY,
                    "内容键长度不能超过 " + MAX_CONTENT_KEY_LENGTH + " 个字符");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '/' || character == '\\' || character == '\0'
                    || Character.isISOControl(character)) {
                throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_CONTENT_KEY,
                        "内容键不能包含路径分隔符或控制字符");
            }
        }
        return value;
    }

    private static KnowledgeDocumentStatus requireStatus(KnowledgeDocumentStatus value) {
        if (value == null) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_STATUS, "状态不能为空");
        }
        return value;
    }

    private static Instant requireInstant(Instant value, String fieldName) {
        if (value == null) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_TIMELINE, fieldName + "不能为空");
        }
        return value;
    }

    private static void requireTimeline(Instant createdAt, Instant updatedAt) {
        if (createdAt.isAfter(updatedAt)) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_TIMELINE,
                    "创建时间不能晚于更新时间");
        }
    }

    /**
     * @return 文档标识
     */
    public KnowledgeDocumentId id() {
        return this.id;
    }

    /**
     * @return 标题值对象
     */
    public DocumentTitle title() {
        return this.title;
    }

    /**
     * @return 原始文件名值对象（仅元数据，绝不参与存储路径）
     */
    public OriginalFilename originalFilename() {
        return this.originalFilename;
    }

    /**
     * @return 文档格式
     */
    public DocumentFormat format() {
        return this.format;
    }

    /**
     * @return 媒体类型
     */
    public String mediaType() {
        return this.mediaType;
    }

    /**
     * @return 内容字节数
     */
    public long sizeBytes() {
        return this.sizeBytes;
    }

    /**
     * @return 内容摘要
     */
    public Sha256Digest sha256() {
        return this.sha256;
    }

    /**
     * @return 内容存储键；<b>仅供内部与存储适配器使用，不得出现在对外响应中</b>
     */
    public String contentKey() {
        return this.contentKey;
    }

    /**
     * @return 状态
     */
    public KnowledgeDocumentStatus status() {
        return this.status;
    }

    /**
     * @return 创建时间
     */
    public Instant createdAt() {
        return this.createdAt;
    }

    /**
     * @return 最近更新时间；新上传时等于创建时间
     */
    public Instant updatedAt() {
        return this.updatedAt;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof KnowledgeDocument document && this.id.equals(document.id);
    }

    @Override
    public int hashCode() {
        return this.id.hashCode();
    }

    /**
     * 只输出标识、格式、大小与状态：标题、文件名与内容键都不进日志字符串。
     */
    @Override
    public String toString() {
        return "KnowledgeDocument[" + this.id + ", " + this.format + ", " + this.sizeBytes + "B, "
                + this.status + "]";
    }
}
