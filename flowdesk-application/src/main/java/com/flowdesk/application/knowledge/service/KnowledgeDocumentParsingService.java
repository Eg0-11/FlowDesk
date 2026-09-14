package com.flowdesk.application.knowledge.service;

import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.application.knowledge.command.ParseKnowledgeDocumentCommand;
import com.flowdesk.application.knowledge.parse.DocumentChunkingException;
import com.flowdesk.application.knowledge.parse.DocumentParsingException;
import com.flowdesk.application.knowledge.port.in.ParseKnowledgeDocumentUseCase;
import com.flowdesk.application.knowledge.port.out.DocumentChunker;
import com.flowdesk.application.knowledge.port.out.DocumentTextParser;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentChunkStore;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentContentReader;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentRepository;
import com.flowdesk.application.knowledge.port.out.KnowledgeTimeProvider;
import com.flowdesk.application.knowledge.port.out.VersionedKnowledgeDocument;
import com.flowdesk.application.knowledge.view.ParsedDocumentView;
import com.flowdesk.domain.knowledge.KnowledgeDocument;
import com.flowdesk.domain.knowledge.KnowledgeDocumentChunk;
import com.flowdesk.domain.knowledge.KnowledgeDocumentId;
import com.flowdesk.domain.knowledge.KnowledgeDocumentStatus;
import com.flowdesk.domain.knowledge.KnowledgeDomainException;
import com.flowdesk.domain.knowledge.KnowledgeParseFailureCode;
import com.flowdesk.domain.knowledge.Sha256Digest;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * 文档解析用例服务：纯 Java、无状态、框架无关（FD-0009）。
 *
 * <h2>时序</h2>
 * <ol>
 *   <li><b>读取</b>文档；不存在 → {@code KNOWLEDGE_DOCUMENT_NOT_FOUND}（404）；</li>
 *   <li><b>版本比对</b>：与 {@code If-Match} 不一致 → {@code KNOWLEDGE_DOCUMENT_VERSION_CONFLICT}（412），
 *       此时还没有做任何写入；</li>
 *   <li><b>领取</b>：{@code markParsing}（领域校验转换合法性）→
 *       {@code repository.update(document, expectedVersion)} 做<b>原子 CAS</b>，
 *       版本加 1。并发下只有一个请求能成功，另一个拿到版本冲突；</li>
 *   <li><b>事务之外</b>：读原文 → 解析 → 切片。这一步可能很慢，因此不与任何数据库事务重叠；
 *       后续所有写入都用<b>领取后的版本</b>作为 CAS 期望值；</li>
 *   <li><b>完成</b>：{@code markParsed} → {@code chunkStore.completeParsing(...)}，
 *       由适配器在<b>一个事务</b>内删除旧切片、批量插入新切片、更新为 {@code PARSED} 并把版本加 1；</li>
 *   <li><b>失败补偿</b>：第 4~5 步的任意失败都会尝试把文档 CAS 成 {@code PARSE_FAILED}
 *       （同样用领取后的版本），然后抛出原始异常；补偿失败只作为 suppressed 附加，绝不覆盖根因。</li>
 * </ol>
 *
 * <h2>已知边界</h2>
 * <p>进程若在 {@code PARSING} 状态期间崩溃（进程被杀、断电），文档会停留在 {@code PARSING}
 * （悬挂状态）：既不会自动重试，也不能被再次领取。恢复扫描任务<b>不属于 FD-0009</b>，
 * 需要人工介入（把状态改回 {@code UPLOADED}/{@code PARSE_FAILED}）或由后续任务实现。
 * 与之相对，「解析失败」与「完成写入失败」都已经落成 {@code PARSE_FAILED}，可以重试。</p>
 */
public final class KnowledgeDocumentParsingService implements ParseKnowledgeDocumentUseCase {

    private final KnowledgeDocumentRepository documentRepository;

    private final KnowledgeDocumentContentReader contentReader;

    private final DocumentTextParser textParser;

    private final DocumentChunker chunker;

    private final KnowledgeDocumentChunkStore chunkStore;

    private final KnowledgeTimeProvider timeProvider;

    /**
     * @param documentRepository 元数据仓储（含 CAS 更新）
     * @param contentReader      原始内容读取端口
     * @param textParser         文本提取端口
     * @param chunker            确定性切片端口
     * @param chunkStore         切片与完成状态的原子写入端口
     * @param timeProvider       时间端口
     */
    public KnowledgeDocumentParsingService(KnowledgeDocumentRepository documentRepository,
            KnowledgeDocumentContentReader contentReader,
            DocumentTextParser textParser,
            DocumentChunker chunker,
            KnowledgeDocumentChunkStore chunkStore,
            KnowledgeTimeProvider timeProvider) {

        this.documentRepository = Objects.requireNonNull(documentRepository, "documentRepository 不能为 null");
        this.contentReader = Objects.requireNonNull(contentReader, "contentReader 不能为 null");
        this.textParser = Objects.requireNonNull(textParser, "textParser 不能为 null");
        this.chunker = Objects.requireNonNull(chunker, "chunker 不能为 null");
        this.chunkStore = Objects.requireNonNull(chunkStore, "chunkStore 不能为 null");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider 不能为 null");
    }

    @Override
    public ParsedDocumentView parse(ParseKnowledgeDocumentCommand command) {
        if (command == null || command.documentId() == null) {
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.INVALID_PARSE_COMMAND,
                    "解析命令不能为空");
        }
        if (command.expectedVersion() < 0L) {
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.INVALID_PARSE_COMMAND,
                    "期望版本不能为负数");
        }

        KnowledgeDocumentId documentId = command.documentId();
        VersionedKnowledgeDocument current = this.documentRepository.findById(documentId)
                .orElseThrow(() -> new KnowledgeApplicationException(
                        KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_NOT_FOUND, "知识文档不存在"));

        // 版本先比一次：给出明确的 412，且不产生任何写入
        if (current.version() != command.expectedVersion()) {
            throw versionConflict();
        }

        KnowledgeDocument document = current.document();
        long claimedVersion = claim(document, current.version());

        // 领取成功：读取、解析与切片全程不持有数据库事务
        List<KnowledgeDocumentChunk> chunks;
        try {
            chunks = extractAndChunk(document);
        }
        catch (RuntimeException failure) {
            markFailed(document, claimedVersion, failure);
            throw failure;
        }

        try {
            document.markParsed(this.timeProvider.now());
            VersionedKnowledgeDocument saved = this.chunkStore.completeParsing(document, claimedVersion, chunks);
            return new ParsedDocumentView(saved.document().id().value(), saved.document().title().value(),
                    saved.document().status(), saved.version(), chunks.size(),
                    saved.document().parsedAt(), null);
        }
        catch (RuntimeException failure) {
            // 完成阶段失败：切片与 PARSED 已整体回滚（端口契约），这里把文档标记为可重试的失败态
            markFailed(document, claimedVersion, failure);
            if (failure instanceof KnowledgeApplicationException applicationFailure) {
                throw applicationFailure;
            }
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.METADATA_STORAGE_FAILURE,
                    "解析结果写入失败", failure);
        }
    }

    /**
     * 领取解析：领域校验转换合法性，仓储用 CAS 落地。
     *
     * @param document        文档聚合（仍处于 {@code UPLOADED} 或 {@code PARSE_FAILED}）
     * @param expectedVersion 读取时看到的版本，CAS 的期望值
     * @return 领取后的版本（{@code expectedVersion + 1}）；后续写入都用它作为 CAS 期望值
     */
    private long claim(KnowledgeDocument document, long expectedVersion) {
        try {
            document.markParsing(this.timeProvider.now());
        }
        catch (KnowledgeDomainException ex) {
            throw new KnowledgeApplicationException(
                    KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_NOT_PARSABLE,
                    "当前状态不允许解析", ex);
        }
        // CAS：并发下只有一个请求能从 UPLOADED/PARSE_FAILED 进入 PARSING
        return this.documentRepository.update(document, expectedVersion).version();
    }

    private List<KnowledgeDocumentChunk> extractAndChunk(KnowledgeDocument document) {
        String extracted = readAndParse(document);
        if (extracted.isEmpty()) {
            throw new DocumentParsingException(KnowledgeParseFailureCode.EMPTY_EXTRACTED_TEXT,
                    "文档提取文本为空");
        }
        List<String> pieces = this.chunker.chunk(extracted);
        if (pieces.isEmpty()) {
            throw new DocumentParsingException(KnowledgeParseFailureCode.EMPTY_EXTRACTED_TEXT,
                    "文档提取文本为空");
        }
        Instant createdAt = this.timeProvider.now();
        List<KnowledgeDocumentChunk> chunks = new ArrayList<>(pieces.size());
        for (int index = 0; index < pieces.size(); index++) {
            String content = pieces.get(index);
            chunks.add(new KnowledgeDocumentChunk(document.id(), index, content,
                    content.codePointCount(0, content.length()), sha256(content), createdAt));
        }
        return chunks;
    }

    private String readAndParse(KnowledgeDocument document) {
        try (InputStream content = this.contentReader.openStream(document.contentKey())) {
            String text = this.textParser.parse(content, document.format());
            return text == null ? "" : text;
        }
        catch (IOException ex) {
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.DOCUMENT_CONTENT_UNREADABLE,
                    "原始内容不可读", ex);
        }
    }

    /**
     * 失败补偿：把文档 CAS 成 {@code PARSE_FAILED}。
     *
     * <p>完成阶段失败时聚合可能已经被 {@code markParsed} 推进到 {@code PARSED}，
     * 而此时数据库里的那次写入已经<b>整体回滚</b>（文档仍是 {@code PARSING}）。
     * 这种情况下不能直接对同一个聚合调用 {@code markParseFailed}（领域层会正确拒绝），
     * 而是用它的当前字段重建一个 {@code PARSING} 快照再记录失败 ——
     * 否则文档会永远停在 {@code PARSING} 这个谁也领不走的状态。</p>
     *
     * <p>补偿失败<b>不覆盖</b>原始异常，只作为 suppressed 附加；失败码来自稳定枚举，
     * 解析器异常文本与路径都不会进入数据库或响应。</p>
     */
    private void markFailed(KnowledgeDocument document, long claimedVersion, RuntimeException primaryFailure) {
        KnowledgeParseFailureCode failureCode = failureCodeOf(primaryFailure);
        try {
            KnowledgeDocument target = document.status() == KnowledgeDocumentStatus.PARSING
                    ? document
                    : parsingSnapshotOf(document);
            target.markParseFailed(failureCode, this.timeProvider.now());
            this.documentRepository.update(target, claimedVersion);
        }
        catch (RuntimeException compensationFailure) {
            if (compensationFailure != primaryFailure) {
                primaryFailure.addSuppressed(compensationFailure);
            }
        }
    }

    /**
     * 用当前字段重建一个仍处于 {@code PARSING} 的等价聚合（不改变原对象）。
     */
    private static KnowledgeDocument parsingSnapshotOf(KnowledgeDocument document) {
        return KnowledgeDocument.restore(document.id(), document.title().value(),
                document.originalFilename().value(), document.format(), document.mediaType(),
                document.sizeBytes(), document.sha256(), document.contentKey(),
                KnowledgeDocumentStatus.PARSING, document.createdAt(), document.updatedAt(),
                null, null, null);
    }

    private static KnowledgeParseFailureCode failureCodeOf(RuntimeException failure) {
        if (failure instanceof DocumentParsingException parsingFailure) {
            return parsingFailure.failureCode();
        }
        if (failure instanceof DocumentChunkingException chunkingFailure) {
            return chunkingFailure.failureCode();
        }
        return KnowledgeParseFailureCode.PARSER_FAILURE;
    }

    private static KnowledgeApplicationException versionConflict() {
        return new KnowledgeApplicationException(
                KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_VERSION_CONFLICT,
                "文档版本已变化，请重新读取后再试");
    }

    private static Sha256Digest sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Sha256Digest.of(HexFormat.of().formatHex(
                    digest.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("运行环境缺少 SHA-256 实现", ex);
        }
    }
}
