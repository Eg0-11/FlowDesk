package com.flowdesk.domain.knowledge;

import java.time.Instant;

/**
 * 知识文档聚合根。
 *
 * <p>文档的元数据（标识、格式、摘要、内容键等）在创建后<b>不可变</b>，
 * 唯一会变的是<b>解析状态机</b>相关的字段；而它们只能通过
 * {@link #markParsing(Instant)}、{@link #markParsed(Instant)}、{@link #markParseFailed}
 * 三个领域行为改变。本类没有任何 setter，上层也无法直接拼装状态。</p>
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
 *
 * <h2>状态机</h2>
 * <p>上传后的文档是可变的，但<b>只能通过领域行为转换状态</b>：</p>
 * <pre>
 * UPLOADED ──markParsing──▶ PARSING ──markParsed──────▶ PARSED
 * PARSE_FAILED ──markParsing──┘        └──markParseFailed──▶ PARSE_FAILED
 * </pre>
 * <p>Controller 与 JDBC 适配器<b>没有</b>直接拼装状态的入口：它们只能调用这三个方法，
 * 非法转换（重复领取、重复解析、对非 PARSING 状态完成）由聚合拒绝。
 * 每次转换都会把 {@code updatedAt} 前移，因此 {@code version} 递增与状态变化一一对应。</p>
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

    private KnowledgeDocumentStatus status;

    private final Instant createdAt;

    private Instant updatedAt;

    private Instant parsedAt;

    private Instant parseFailedAt;

    private KnowledgeParseFailureCode parseFailureCode;

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
                              Instant updatedAt,
                              Instant parsedAt,
                              Instant parseFailedAt,
                              KnowledgeParseFailureCode parseFailureCode) {

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
        this.parsedAt = parsedAt;
        this.parseFailedAt = parseFailedAt;
        this.parseFailureCode = parseFailureCode;
        requireTimeline();
        requireParseFieldsConsistent();
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
                contentKey, KnowledgeDocumentStatus.UPLOADED, uploadedAt, uploadedAt, null, null, null);
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
            Instant updatedAt,
            Instant parsedAt,
            Instant parseFailedAt,
            KnowledgeParseFailureCode parseFailureCode) {

        try {
            return new KnowledgeDocument(id, DocumentTitle.of(title), OriginalFilename.of(originalFilename),
                    format, mediaType, sizeBytes, sha256, contentKey, status, createdAt, updatedAt, parsedAt,
                    parseFailedAt, parseFailureCode);
        }
        catch (KnowledgeDomainException ex) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_RESTORED_STATE,
                    "持久化的知识文档快照不自洽：" + ex.errorCode());
        }
    }

    /**
     * 领取解析：{@code UPLOADED} 或 {@code PARSE_FAILED} → {@code PARSING}。
     *
     * <p>{@code PARSING} 不允许再次领取（否则会有两个请求同时解析同一文档、互相覆盖切片），
     * {@code PARSED} 不允许重复解析（内容已定型）。这两种情况都抛
     * {@link KnowledgeErrorCode#ILLEGAL_STATUS_TRANSITION}。</p>
     *
     * @param occurredAt 领取时间
     * @throws KnowledgeDomainException 状态不允许领取，或时间早于当前更新时间
     */
    public void markParsing(Instant occurredAt) {
        requireStatusIn("领取解析", KnowledgeDocumentStatus.UPLOADED,
                KnowledgeDocumentStatus.PARSE_FAILED);
        Instant time = requireTransitionTime(occurredAt);
        this.status = KnowledgeDocumentStatus.PARSING;
        // 重新领取时清空上一次的失败痕迹：PARSE_FAILED 与 PARSING 不允许同时携带失败信息
        this.parseFailedAt = null;
        this.parseFailureCode = null;
        this.updatedAt = time;
        requireParseFieldsConsistent();
    }

    /**
     * 解析完成：{@code PARSING} → {@code PARSED}，并记录 {@code parsedAt}。
     *
     * @param occurredAt 完成时间
     * @throws KnowledgeDomainException 非 {@code PARSING} 状态，或时间不合法
     */
    public void markParsed(Instant occurredAt) {
        requireStatusIn("完成解析", KnowledgeDocumentStatus.PARSING);
        Instant time = requireTransitionTime(occurredAt);
        this.status = KnowledgeDocumentStatus.PARSED;
        this.parsedAt = time;
        this.parseFailedAt = null;
        this.parseFailureCode = null;
        this.updatedAt = time;
        requireParseFieldsConsistent();
    }

    /**
     * 解析失败：{@code PARSING} → {@code PARSE_FAILED}，并记录稳定失败码。
     *
     * <p>只接受枚举错误码：解析器的异常消息、路径与堆栈绝不进入聚合，
     * 因此也不可能被持久化或返回给调用方。</p>
     *
     * @param failureCode 稳定的失败码
     * @param occurredAt  失败时间
     * @throws KnowledgeDomainException 非 {@code PARSING} 状态、失败码为空或时间不合法
     */
    public void markParseFailed(KnowledgeParseFailureCode failureCode, Instant occurredAt) {
        requireStatusIn("标记解析失败", KnowledgeDocumentStatus.PARSING);
        if (failureCode == null) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_PARSE_FAILURE_CODE,
                    "解析失败码不能为空");
        }
        Instant time = requireTransitionTime(occurredAt);
        this.status = KnowledgeDocumentStatus.PARSE_FAILED;
        this.parseFailedAt = time;
        this.parseFailureCode = failureCode;
        this.parsedAt = null;
        this.updatedAt = time;
        requireParseFieldsConsistent();
    }

    private void requireStatusIn(String operation, KnowledgeDocumentStatus... allowed) {
        for (KnowledgeDocumentStatus candidate : allowed) {
            if (this.status == candidate) {
                return;
            }
        }
        throw new KnowledgeDomainException(KnowledgeErrorCode.ILLEGAL_STATUS_TRANSITION,
                "当前状态不允许" + operation);
    }

    private Instant requireTransitionTime(Instant occurredAt) {
        if (occurredAt == null) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_TIMELINE, "转换时间不能为空");
        }
        if (occurredAt.isBefore(this.updatedAt)) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_TIMELINE,
                    "状态转换时间不能早于文档的最近更新时间");
        }
        return occurredAt;
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

    private void requireTimeline() {
        requireTimeline(this.createdAt, this.updatedAt);
        requireWithinDocumentWindow(this.parsedAt, "解析完成时间");
        requireWithinDocumentWindow(this.parseFailedAt, "解析失败时间");
    }

    private void requireWithinDocumentWindow(Instant value, String fieldName) {
        if (value == null) {
            return;
        }
        if (value.isBefore(this.createdAt)) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_TIMELINE,
                    fieldName + "不能早于创建时间");
        }
        if (value.isAfter(this.updatedAt)) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_TIMELINE,
                    fieldName + "不能晚于更新时间");
        }
    }

    /**
     * 解析字段必须与状态严格对应，不允许「状态与字段各说各话」的快照。
     */
    private void requireParseFieldsConsistent() {
        switch (this.status) {
            case PARSED -> {
                if (this.parsedAt == null || this.parseFailedAt != null || this.parseFailureCode != null) {
                    throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_RESTORED_STATE,
                            "PARSED 状态必须且只能带解析完成时间");
                }
            }
            case PARSE_FAILED -> {
                if (this.parseFailedAt == null || this.parseFailureCode == null || this.parsedAt != null) {
                    throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_RESTORED_STATE,
                            "PARSE_FAILED 状态必须且只能带解析失败时间与失败码");
                }
            }
            default -> {
                if (this.parsedAt != null || this.parseFailedAt != null || this.parseFailureCode != null) {
                    throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_RESTORED_STATE,
                            this.status + " 状态不应携带解析结果字段");
                }
            }
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

    /**
     * @return 解析完成时间；仅 {@code PARSED} 状态非空
     */
    public Instant parsedAt() {
        return this.parsedAt;
    }

    /**
     * @return 解析失败时间；仅 {@code PARSE_FAILED} 状态非空
     */
    public Instant parseFailedAt() {
        return this.parseFailedAt;
    }

    /**
     * @return 解析失败码；仅 {@code PARSE_FAILED} 状态非空（稳定枚举，不含解析器细节）
     */
    public KnowledgeParseFailureCode parseFailureCode() {
        return this.parseFailureCode;
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
