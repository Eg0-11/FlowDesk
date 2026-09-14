package com.flowdesk.infrastructure.knowledge.storage;

import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.application.knowledge.port.out.ContentSource;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentContentReader;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentContentStore;
import com.flowdesk.application.knowledge.port.out.StoredContent;
import com.flowdesk.domain.knowledge.KnowledgeDocumentId;
import com.flowdesk.domain.knowledge.Sha256Digest;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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
public final class LocalFileSystemKnowledgeContentStore
        implements KnowledgeDocumentContentStore, KnowledgeDocumentContentReader {

    /** 内容对象所在子目录。 */
    private static final String DOCUMENTS_DIRECTORY = "documents";

    /** 临时文件所在子目录（与内容同根，保证原子移动在同一文件系统内）。 */
    private static final String TEMP_DIRECTORY = "tmp";

    /** 复制缓冲区大小。 */
    private static final int BUFFER_SIZE = 16 * 1024;

    /** 内容键前缀：由文档标识派生，但不与公共标识逐字相同。 */
    private static final String CONTENT_KEY_PREFIX = "kdoc-";

    /** 退化发布路径使用的占位锁文件后缀。 */
    private static final String CLAIM_SUFFIX = ".claim";

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

        try {
            Files.createDirectories(this.documentsRoot);
            Files.createDirectories(this.tempRoot);

            tempFile = Files.createTempFile(this.tempRoot, "upload-", ".part");
            CopyResult copied = copy(source, tempFile);
            if (copied.sizeBytes() == 0L) {
                // 空内容在发布之前就拒绝：否则会先落一个空对象，再回头删
                throw new KnowledgeApplicationException(
                        KnowledgeApplicationErrorCode.EMPTY_DOCUMENT_CONTENT, "上传内容为空");
            }

            publish(tempFile, target, contentKey);

            // 从这里开始，目标对象确定由本次调用发布
            try {
                return new StoredContent(contentKey, copied.sizeBytes(), Sha256Digest.of(copied.hexDigest()));
            }
            catch (RuntimeException ex) {
                // 已发布但结果无法构造：删掉本次刚发布的对象，避免留下无引用的孤立文件。
                // 这里的删除范围严格限定在「本次调用刚发布的对象」上，不会碰到别人的对象
                deleteQuietly(target);
                throw ex;
            }
        }
        catch (KnowledgeApplicationException ex) {
            throw ex;
        }
        catch (FileAlreadyExistsException ex) {
            // 目标已存在（并发的另一个发布者先到，或历史遗留对象）：拒绝覆盖，且绝不动它
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.CONTENT_STORAGE_FAILURE,
                    "目标内容已存在，拒绝覆盖", ex);
        }
        catch (IOException ex) {
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.CONTENT_STORAGE_FAILURE,
                    "内容存储失败", ex);
        }
        finally {
            // 临时文件绝不留在磁盘上：发布成功时它已被链接/移动走（删除是幂等的），
            // 发布失败时它就是需要清理的半截文件
            deleteQuietly(tempFile);
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
     * 按内容键打开原始内容流（FD-0009 的读取端口）。
     *
     * <p>读写两个端口由同一个适配器实现是<b>有意</b>的：它们共享同一套路径安全解析
     * （字符集白名单 + 必须仍在存储根目录内），拆成两个类只会把这段安全逻辑复制两份。</p>
     *
     * <p>只读取<b>普通文件</b>且<b>不跟随符号链接</b>：{@code isRegularFile(..., NOFOLLOW_LINKS)}
     * 对符号链接返回 {@code false}，因此指向根目录之外（甚至指向 {@code /etc/passwd}）的链接
     * 在这里就被拒绝，而不是等到读出来才发现。异常只携带稳定错误码，
     * <b>不</b>包含真实路径。</p>
     *
     * @param contentKey 服务端生成的内容键
     * @return 内容输入流，由调用方关闭
     */
    @Override
    public InputStream openStream(String contentKey) {
        Path target;
        try {
            target = resolveWithinRoot(this.documentsRoot, contentKey);
        }
        catch (KnowledgeApplicationException ex) {
            // 读取路径上的「内容键非法 / 路径越界」与「对象不存在」对应用层是同一件事：
            // 原始内容不可读。写路径（store/delete）保持 CONTENT_STORAGE_FAILURE 语义。
            throw new KnowledgeApplicationException(
                    KnowledgeApplicationErrorCode.DOCUMENT_CONTENT_UNREADABLE, "原始内容不可读", ex);
        }
        try {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new KnowledgeApplicationException(
                        KnowledgeApplicationErrorCode.DOCUMENT_CONTENT_UNREADABLE, "原始内容不可读");
            }
            return Files.newInputStream(target, StandardOpenOption.READ);
        }
        catch (IOException ex) {
            throw new KnowledgeApplicationException(KnowledgeApplicationErrorCode.DOCUMENT_CONTENT_UNREADABLE,
                    "原始内容不可读", ex);
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
     * 把临时文件发布到最终位置：<b>原子、且「目标存在即失败」</b>。
     *
     * <p>不能只靠「先 exists 再 move」：两个针对同一个文档标识的并发上传可能同时看到目标不存在，
     * 然后后一个把前一个覆盖掉 —— 前一个的元数据就会指向被替换过的内容（甚至被失败方的补偿删除掉）。</p>
     *
     * <h3>首选：硬链接（原子 create-if-absent，且内容一次性可见）</h3>
     * <p>{@link Files#createLink(Path, Path)} 对应 POSIX 的 {@code link(2)} 与 Windows 的
     * {@code CreateHardLink}：它<b>在目标已存在时原子地失败</b>，<b>永不替换</b>已有目标，
     * 并且目标一出现就指向完整的临时文件内容 —— 三个要求一次满足。
     * 临时文件与目标必须位于同一文件系统（本适配器把临时目录放在存储根目录下，正是为此）。</p>
     *
     * <h3>退化：CREATE_NEW 占位锁 + ATOMIC_MOVE</h3>
     * <p>某些文件系统（FAT/exFAT、部分网络文件系统、权限受限环境）不支持硬链接。
     * 此时退化为「先原子占位、再检查、再原子移动」：{@link Files#createFile(Path, FileAttribute[])}
     * 使用 {@code CREATE_NEW} 语义（POSIX 的 {@code O_CREAT|O_EXCL}、Windows 的 {@code CREATE_NEW}），
     * 在几乎所有文件系统上都是原子的，因此它能把「检查目标是否存在 + 移动」变成一个
     * <b>互斥临界区</b>：同一存储根目录下的任何两个发布者都不可能同时进入。</p>
     *
     * <p>剩余边界：如果<b>本服务之外</b>的进程往存储根目录里写同名对象，退化路径无法察觉。
     * 存储根目录是服务私有的、内容键由服务端生成，因此这属于部署边界而非代码缺陷，
     * 已在 ADR 0005 中记录。</p>
     *
     * @param tempFile 已写满且校验通过的临时文件
     * @param target   最终对象路径
     * @param contentKey 内容键（用于生成占位锁文件名）
     * @throws IOException 目标已存在或发布失败
     */
    private void publish(Path tempFile, Path target, String contentKey) throws IOException {
        try {
            Files.createLink(target, tempFile);
            deleteQuietly(tempFile);
            return;
        }
        catch (FileAlreadyExistsException ex) {
            // 目标已存在：直接失败，绝不覆盖，也绝不动别人的对象
            throw ex;
        }
        catch (UnsupportedOperationException ex) {
            publishWithClaim(tempFile, target, contentKey);
        }
        catch (FileSystemException ex) {
            // 例如「操作不受支持」「跨设备」：退化到占位锁路径；若那里也失败，异常会照常向上抛
            publishWithClaim(tempFile, target, contentKey);
        }
    }

    /**
     * 退化发布路径：{@code CREATE_NEW} 占位锁保证互斥，临界区内检查目标并原子移动。
     *
     * <p>包级可见是<b>有意</b>的：集成测试需要在不支持硬链接的假设下单独验证这条路径同样是
     * 「存在即失败、永不覆盖」。</p>
     *
     * @param tempFile   已写满的临时文件
     * @param target     最终对象路径
     * @param contentKey 内容键
     * @throws IOException 目标已存在（含占位锁被他人持有）或移动失败
     */
    void publishWithClaim(Path tempFile, Path target, String contentKey) throws IOException {
        Path claim = this.tempRoot.resolve(contentKey + CLAIM_SUFFIX);
        boolean claimed = false;
        try {
            Files.createFile(claim);
            claimed = true;
        }
        catch (FileAlreadyExistsException ex) {
            // 另一个发布者正在发布同一个目标：本次必然失败，且不得触碰它
            throw new FileAlreadyExistsException(target.toString(), null,
                    "另一个发布者正在发布同一个内容键，拒绝并发覆盖");
        }

        try {
            if (Files.exists(target)) {
                throw new FileAlreadyExistsException(target.toString(), null,
                        "目标内容已存在，拒绝覆盖");
            }
            Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE);
        }
        finally {
            if (claimed) {
                deleteQuietly(claim);
            }
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
