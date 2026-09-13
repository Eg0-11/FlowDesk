package com.flowdesk.infrastructure.knowledge.storage;

import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.application.knowledge.port.out.ContentSource;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentContentStore;
import com.flowdesk.application.knowledge.port.out.StoredContent;
import com.flowdesk.domain.knowledge.KnowledgeDocumentId;
import com.flowdesk.domain.knowledge.Sha256Digest;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 本地文件系统内容存储适配器。
 *
 * <p>元数据进数据库、原始文件进文件系统，两者由不同的端口表达（见 ADR 0005）。
 * 本适配器是<b>当前</b>的实现，将来可以整体替换为对象存储适配器，
 * 而应用层与领域层完全不受影响 —— 这也是为什么内容键被定义成<b>不透明</b>值。</p>
 *
 * <h2>安全与一致性要点</h2>
 * <ul>
 *   <li><b>路径只由文档标识决定。</b>内容键 = 文档标识的规范 UUID 文本；
 *       原始文件名永远不会参与路径拼接，因此「上传一个叫 {@code ../../etc/passwd} 的文件」
 *       在存储层没有任何着力点；</li>
 *   <li><b>内容键仍要校验。</b>内容键会从数据库读回来（补偿删除时），
 *       因此解析路径前先检查字符集，再对解析结果做「必须仍在存储根目录内」的包含性检查 ——
 *       双重防护，避免将来某次改动把不透明键变成可用路径；</li>
 *   <li><b>先临时再原子。</b>内容先写到存储根目录下的临时文件，全部读取完成、大小与摘要都算出来
 *       之后才移动到最终位置。因此最终位置出现对象 = 内容已完整写全；</li>
 *   <li><b>不覆盖已有对象。</b>目标已存在直接失败，移动时不带 {@code REPLACE_EXISTING}；</li>
 *   <li><b>任何失败都清理临时文件</b>（{@code finally} 保证），不留半截文件；</li>
 *   <li><b>流式处理。</b>固定大小缓冲区边读边算 SHA-256，绝不把文件整体载入内存。</li>
 * </ul>
 */
public final class LocalFileSystemKnowledgeContentStore implements KnowledgeDocumentContentStore {

    /** 内容对象所在子目录。 */
    private static final String DOCUMENTS_DIRECTORY = "documents";

    /** 临时文件所在子目录（与内容同根，保证原子移动在同一文件系统内）。 */
    private static final String TEMP_DIRECTORY = "tmp";

    /** 复制缓冲区大小。 */
    private static final int BUFFER_SIZE = 16 * 1024;

    /** 内容键前缀：由文档标识派生，但不与公共标识逐字相同。 */
    private static final String CONTENT_KEY_PREFIX = "kdoc-";

    /** 内容键允许的字符集：足够表达 UUID，同时排除路径分隔符与点号开头的相对路径。 */
    private static final Pattern SAFE_CONTENT_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,254}");

    private final Path root;

    private final Path documentsRoot;

    private final Path tempRoot;

    /**
     * @param root 存储根目录；不存在时会在首次写入时创建
     */
    public LocalFileSystemKnowledgeContentStore(Path root) {
        Objects.requireNonNull(root, "root 不能为 null");
        this.root = root.toAbsolutePath().normalize();
        this.documentsRoot = this.root.resolve(DOCUMENTS_DIRECTORY);
        this.tempRoot = this.root.resolve(TEMP_DIRECTORY);
    }

    /**
     * @return 存储根目录（规范化后的绝对路径）
     */
    public Path root() {
        return this.root;
    }

    @Override
    public StoredContent store(KnowledgeDocumentId documentId, ContentSource source) {
        Objects.requireNonNull(documentId, "documentId 不能为 null");
        Objects.requireNonNull(source, "source 不能为 null");

        String contentKey = generateContentKey(documentId);
        Path target = resolveWithinRoot(this.documentsRoot, contentKey);
        Path tempFile = null;
        boolean moved = false;

        try {
            Files.createDirectories(this.documentsRoot);
            Files.createDirectories(this.tempRoot);
            if (Files.exists(target)) {
                throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.CONTENT_STORAGE_FAILURE,
                        "目标内容已存在，拒绝覆盖");
            }

            tempFile = Files.createTempFile(this.tempRoot, "upload-", ".part");
            CopyResult copied = copy(source, tempFile);
            if (copied.sizeBytes() == 0L) {
                // 空内容在移动之前就拒绝：否则会先落一个空对象，再回头删
                throw new KnowledgeApplicationException(
                        KnowledgeApplicationErrorCode.EMPTY_DOCUMENT_CONTENT, "上传内容为空");
            }

            moveIntoPlace(tempFile, target);
            moved = true;

            return new StoredContent(contentKey, copied.sizeBytes(), Sha256Digest.of(copied.hexDigest()));
        }
        catch (KnowledgeApplicationException ex) {
            throw ex;
        }
        catch (IOException ex) {
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.CONTENT_STORAGE_FAILURE,
                    "内容存储失败", ex);
        }
        finally {
            if (!moved) {
                deleteQuietly(tempFile);
            }
        }
    }

    @Override
    public boolean delete(String contentKey) {
        Path target = resolveWithinRoot(this.documentsRoot, contentKey);
        try {
            return Files.deleteIfExists(target);
        }
        catch (IOException ex) {
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.CONTENT_STORAGE_FAILURE,
                    "内容删除失败", ex);
        }
    }

    /**
     * 由文档标识生成内容键：服务端唯一决定，客户端无法影响。
     *
     * <p>键由标识<b>确定性派生</b>（因此补偿删除时无需再查库，也不会误删别人的内容），
     * 但额外加了前缀，使它不与对外暴露的 {@code id} 逐字相同 ——
     * 内容键是存储的内部坐标，没有必要也不应该和公共标识长得一样。</p>
     */
    private static String generateContentKey(KnowledgeDocumentId documentId) {
        return CONTENT_KEY_PREFIX + documentId.value();
    }

    /**
     * 解析目标路径，并保证结果仍位于给定根目录内。
     *
     * <p>先按字符集白名单拒绝，再做包含性检查：两道检查都通过才返回路径。
     * 因此即使内容键被外部改写成 {@code ../x}，也只能得到一个固定的服务端错误。</p>
     */
    private static Path resolveWithinRoot(Path root, String contentKey) {
        if (contentKey == null || !SAFE_CONTENT_KEY.matcher(contentKey).matches()) {
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.CONTENT_STORAGE_FAILURE,
                    "内容键不合法");
        }
        Path resolved = root.resolve(contentKey).normalize();
        if (!resolved.startsWith(root)) {
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.CONTENT_STORAGE_FAILURE,
                    "内容路径越界");
        }
        return resolved;
    }

    /**
     * 流式复制并计算摘要：固定缓冲区，绝不把内容整体读进内存。
     *
     * <p>输入流在这里关闭（本方法自己打开的），内容源本身由应用服务关闭。</p>
     */
    private static CopyResult copy(ContentSource source, Path tempFile) throws IOException {
        MessageDigest digest = sha256();
        long total = 0L;
        byte[] buffer = new byte[BUFFER_SIZE];

        try (InputStream input = source.openStream();
                OutputStream output = Files.newOutputStream(tempFile)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
                total += read;
            }
        }
        return new CopyResult(total, HexFormat.of().formatHex(digest.digest()));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException ex) {
            // JDK 必须提供 SHA-256；走到这里说明运行环境本身不完整
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.CONTENT_STORAGE_FAILURE,
                    "运行环境缺少 SHA-256 实现", ex);
        }
    }

    /**
     * 把临时文件移到最终位置。
     *
     * <p>优先 {@code ATOMIC_MOVE}；文件系统不支持时降级为同目录内的普通移动
     * （同一目录内的重命名在实践中同样是原子的），但<b>始终不覆盖</b>已有对象。</p>
     */
    private static void moveIntoPlace(Path tempFile, Path target) throws IOException {
        try {
            Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (AtomicMoveNotSupportedException ex) {
            Files.move(tempFile, target);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        }
        catch (IOException ignored) {
            // 清理失败只可能留下一个临时文件，不能掩盖真正的失败原因
        }
    }

    /**
     * 一次复制的结果。
     *
     * @param sizeBytes 实际写入的字节数
     * @param hexDigest 小写十六进制 SHA-256
     */
    private record CopyResult(long sizeBytes, String hexDigest) {
    }
}
