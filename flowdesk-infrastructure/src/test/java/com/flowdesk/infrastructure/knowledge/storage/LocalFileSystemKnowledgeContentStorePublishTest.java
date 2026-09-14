package com.flowdesk.infrastructure.knowledge.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.application.knowledge.port.out.StoredContent;
import com.flowdesk.domain.knowledge.KnowledgeDocumentId;
import com.flowdesk.infrastructure.knowledge.KnowledgeTestContent.ArrayContentSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 最终文件发布的<b>原子性与 create-if-absent</b> 测试（FD-0008-R1）。
 *
 * <p>要防住的危险链路：两个针对同一个文档标识的并发上传都通过了「目标不存在」检查，
 * 后一个覆盖前一个，随后失败方又去补偿删除那个共享目标 —— 结果是成功的那条元数据
 * 指向一个不存在的内容。因此发布必须做到：</p>
 * <ol>
 *   <li>完整内容一次性可见；</li>
 *   <li>目标已存在时<b>原子失败</b>；</li>
 *   <li><b>永不替换</b>已有目标；</li>
 *   <li>失败方不得删除或改变先成功方的对象。</li>
 * </ol>
 *
 * <p>两条发布路径都被覆盖：默认的硬链接路径（POSIX {@code link(2)} / Windows
 * {@code CreateHardLink}），以及不支持硬链接时的 {@code CREATE_NEW} 占位锁 + 原子移动退化路径。</p>
 */
class LocalFileSystemKnowledgeContentStorePublishTest {

    /** 并发轮数：每轮都是「两个线程抢同一个目标」，用于把竞态反复跑出来。 */
    private static final int ROUNDS = 20;

    private static final long TIMEOUT_SECONDS = 20L;

    @TempDir
    Path tempDirectory;

    private Path root;

    private LocalFileSystemKnowledgeContentStore store;

    @BeforeEach
    void setUp() {
        this.root = this.tempDirectory.resolve("knowledge-root");
        this.store = new LocalFileSystemKnowledgeContentStore(this.root);
    }

    // ---------- ① 并发发布（默认路径） ----------

    @Test
    void concurrentUploadsOfTheSameDocumentNeverOverwriteEachOther() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Path roundRoot = this.root.resolve("round-" + round);
            LocalFileSystemKnowledgeContentStore roundStore =
                    new LocalFileSystemKnowledgeContentStore(roundRoot);
            KnowledgeDocumentId documentId = KnowledgeDocumentId.of(UUID.randomUUID());
            byte[] firstContent = ("first-" + round).getBytes(StandardCharsets.UTF_8);
            byte[] secondContent = ("second-" + round).getBytes(StandardCharsets.UTF_8);

            Outcome outcome = raceTwoPublishes(roundStore, documentId, firstContent, secondContent);

            assertThat(outcome.successes()).as("第 %d 轮：只能有一个成功", round).isEqualTo(1);
            assertThat(outcome.failures()).as("第 %d 轮：另一个必须失败", round).isEqualTo(1);
            assertThat(outcome.failureCodes())
                    .as("第 %d 轮：失败方必须得到内容存储失败", round)
                    .containsOnly(KnowledgeApplicationErrorCode.CONTENT_STORAGE_FAILURE);

            Path target = roundRoot.resolve("documents").resolve("kdoc-" + documentId.value());
            byte[] published = Files.readAllBytes(target);
            assertThat(published).as("第 %d 轮：最终文件必须完整等于成功方内容", round)
                    .isEqualTo(outcome.winnerContent());
            assertThat(published).as("第 %d 轮：不得是失败方的内容", round)
                    .isNotEqualTo(outcome.loserContent());

            assertTempDirectoryEmpty(roundRoot);
            assertNoClaimFiles(roundRoot);
        }
    }

    @Test
    void concurrentUploadsKeepTheWinnersObjectIntactAfterTheLoserFails() throws Exception {
        KnowledgeDocumentId documentId = KnowledgeDocumentId.of(UUID.randomUUID());
        byte[] firstContent = "winner-or-loser-a".getBytes(StandardCharsets.UTF_8);
        byte[] secondContent = "winner-or-loser-bb".getBytes(StandardCharsets.UTF_8);

        Outcome outcome = raceTwoPublishes(this.store, documentId, firstContent, secondContent);
        StoredContent winner = outcome.winner();
        assertThat(winner).isNotNull();
        assertThat(winner.sizeBytes()).isEqualTo(outcome.winnerContent().length);

        Path target = this.root.resolve("documents").resolve(winner.contentKey());
        assertThat(Files.readAllBytes(target)).isEqualTo(outcome.winnerContent());

        // 只应有成功方那一个对象；失败方既没有覆盖它，也没有留下别的文件
        try (Stream<Path> files = Files.list(this.root.resolve("documents"))) {
            assertThat(files.toList()).containsExactly(target);
        }
        assertTempDirectoryEmpty(this.root);
    }

    // ---------- ② 退化路径（不支持硬链接时） ----------

    @Test
    void claimBasedPublishAlsoRefusesToOverwrite() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Path roundRoot = this.root.resolve("claim-round-" + round);
            LocalFileSystemKnowledgeContentStore roundStore =
                    new LocalFileSystemKnowledgeContentStore(roundRoot);
            Files.createDirectories(roundRoot.resolve("tmp"));
            Files.createDirectories(roundRoot.resolve("documents"));

            String contentKey = "kdoc-" + UUID.randomUUID();
            Path target = roundRoot.resolve("documents").resolve(contentKey);
            byte[] firstContent = ("claim-first-" + round).getBytes(StandardCharsets.UTF_8);
            byte[] secondContent = ("claim-second-" + round).getBytes(StandardCharsets.UTF_8);
            Path firstTemp = writeTemp(roundRoot, firstContent);
            Path secondTemp = writeTemp(roundRoot, secondContent);

            CyclicBarrier barrier = new CyclicBarrier(2);
            AtomicInteger successes = new AtomicInteger();
            AtomicInteger failures = new AtomicInteger();
            AtomicReference<byte[]> winnerContent = new AtomicReference<>();
            List<Throwable> unexpected = new CopyOnWriteArrayList<>();

            Thread first = new Thread(claimTask(roundStore, barrier, firstTemp, target, contentKey,
                    firstContent, successes, failures, winnerContent, unexpected), "claim-publisher-1");
            Thread second = new Thread(claimTask(roundStore, barrier, secondTemp, target, contentKey,
                    secondContent, successes, failures, winnerContent, unexpected), "claim-publisher-2");
            first.start();
            second.start();
            joinOrFail(first);
            joinOrFail(second);

            assertThat(unexpected).as("第 %d 轮：占位锁路径不得抛出预期之外的异常", round).isEmpty();
            assertThat(successes.get()).as("第 %d 轮：只能有一个成功", round).isEqualTo(1);
            assertThat(failures.get()).as("第 %d 轮：另一个必须拿到「目标已存在」", round).isEqualTo(1);
            assertThat(Files.readAllBytes(target)).as("第 %d 轮：最终文件必须完整等于成功方内容", round)
                    .isEqualTo(winnerContent.get());
            assertNoClaimFiles(roundRoot);

            Files.deleteIfExists(firstTemp);
            Files.deleteIfExists(secondTemp);
        }
    }

    @Test
    void claimBasedPublishFailsWhenTheTargetAlreadyExists() throws Exception {
        Files.createDirectories(this.root.resolve("tmp"));
        Files.createDirectories(this.root.resolve("documents"));
        String contentKey = "kdoc-" + UUID.randomUUID();
        Path target = this.root.resolve("documents").resolve(contentKey);
        byte[] existing = "existing".getBytes(StandardCharsets.UTF_8);
        Files.write(target, existing);

        Path tempFile = writeTemp(this.root, "replacement".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> this.store.publishWithClaim(tempFile, target, contentKey))
                .isInstanceOf(FileAlreadyExistsException.class);

        assertThat(Files.readAllBytes(target)).as("已存在的对象必须原样保留").isEqualTo(existing);
        assertNoClaimFiles(this.root);
    }

    // ---------- ③ 单线程语义 ----------

    @Test
    void storingTwiceForTheSameDocumentFailsWithoutTouchingTheFirstObject() throws Exception {
        byte[] first = "first".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second".getBytes(StandardCharsets.UTF_8);
        KnowledgeDocumentId documentId = KnowledgeDocumentId.of(UUID.randomUUID());

        StoredContent stored = this.store.store(documentId, ArrayContentSource.of(first, "a.txt"));

        assertThatThrownBy(() -> this.store.store(documentId, ArrayContentSource.of(second, "b.txt")))
                .isInstanceOf(KnowledgeApplicationException.class)
                .extracting(thrown -> ((KnowledgeApplicationException) thrown).errorCode())
                .isEqualTo(KnowledgeApplicationErrorCode.CONTENT_STORAGE_FAILURE);

        Path target = this.root.resolve("documents").resolve(stored.contentKey());
        assertThat(Files.readAllBytes(target)).as("先成功的对象不得被改动").isEqualTo(first);
        assertTempDirectoryEmpty(this.root);
        assertNoClaimFiles(this.root);
    }

    @Test
    void deletingContentOnlyRemovesItsOwnObject() throws Exception {
        StoredContent first = this.store.store(KnowledgeDocumentId.of(UUID.randomUUID()),
                ArrayContentSource.of("first".getBytes(StandardCharsets.UTF_8), "a.txt"));
        StoredContent second = this.store.store(KnowledgeDocumentId.of(UUID.randomUUID()),
                ArrayContentSource.of("second".getBytes(StandardCharsets.UTF_8), "b.txt"));

        assertThat(this.store.delete(first.contentKey())).isTrue();

        assertThat(Files.exists(this.root.resolve("documents").resolve(first.contentKey()))).isFalse();
        assertThat(Files.exists(this.root.resolve("documents").resolve(second.contentKey())))
                .as("删除必须只命中自己的内容键").isTrue();
    }

    // ---------- 辅助 ----------

    /**
     * 一次并发发布的结果。
     *
     * @param successes      成功次数
     * @param failures       失败次数
     * @param failureCodes   失败方得到的错误码
     * @param winner         成功方拿到的存储结果
     * @param winnerContent  成功方写入的内容
     * @param loserContent   失败方尝试写入的内容
     */
    private record Outcome(int successes, int failures, List<KnowledgeApplicationErrorCode> failureCodes,
            StoredContent winner, byte[] winnerContent, byte[] loserContent) {
    }

    private Outcome raceTwoPublishes(LocalFileSystemKnowledgeContentStore targetStore,
            KnowledgeDocumentId documentId, byte[] firstContent, byte[] secondContent) throws Exception {

        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        List<KnowledgeApplicationErrorCode> failureCodes = new CopyOnWriteArrayList<>();
        AtomicReference<StoredContent> winner = new AtomicReference<>();
        AtomicReference<byte[]> winnerContent = new AtomicReference<>();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();

        Thread first = new Thread(storeTask(targetStore, barrier, documentId, firstContent, successes, failures,
                failureCodes, winner, winnerContent, unexpected), "publisher-1");
        Thread second = new Thread(storeTask(targetStore, barrier, documentId, secondContent, successes, failures,
                failureCodes, winner, winnerContent, unexpected), "publisher-2");
        first.start();
        second.start();
        joinOrFail(first);
        joinOrFail(second);

        assertThat(unexpected).as("并发线程不得抛出预期之外的异常").isEmpty();
        byte[] winnerBytes = winnerContent.get() == null ? new byte[0] : winnerContent.get();
        byte[] loserBytes = winnerBytes == firstContent ? secondContent : firstContent;
        return new Outcome(successes.get(), failures.get(), failureCodes, winner.get(), winnerBytes, loserBytes);
    }

    private Runnable storeTask(LocalFileSystemKnowledgeContentStore targetStore, CyclicBarrier barrier,
            KnowledgeDocumentId documentId, byte[] content, AtomicInteger successes, AtomicInteger failures,
            List<KnowledgeApplicationErrorCode> failureCodes, AtomicReference<StoredContent> winner,
            AtomicReference<byte[]> winnerContent, List<Throwable> unexpected) {

        return () -> {
            try {
                barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                StoredContent stored = targetStore.store(documentId, ArrayContentSource.of(content, "a.txt"));
                successes.incrementAndGet();
                winner.compareAndSet(null, stored);
                winnerContent.compareAndSet(null, content);
            }
            catch (KnowledgeApplicationException ex) {
                failures.incrementAndGet();
                failureCodes.add(ex.errorCode());
            }
            catch (Throwable throwable) {
                unexpected.add(throwable);
            }
        };
    }

    private Runnable claimTask(LocalFileSystemKnowledgeContentStore targetStore, CyclicBarrier barrier,
            Path tempFile, Path target, String contentKey, byte[] content, AtomicInteger successes,
            AtomicInteger failures, AtomicReference<byte[]> winnerContent, List<Throwable> unexpected) {

        return () -> {
            try {
                barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                targetStore.publishWithClaim(tempFile, target, contentKey);
                successes.incrementAndGet();
                winnerContent.compareAndSet(null, content);
            }
            catch (FileAlreadyExistsException ex) {
                failures.incrementAndGet();
            }
            catch (Throwable throwable) {
                unexpected.add(throwable);
            }
        };
    }

    private static Path writeTemp(Path root, byte[] content) throws IOException {
        Files.createDirectories(root.resolve("tmp"));
        Path tempFile = Files.createTempFile(root.resolve("tmp"), "upload-", ".part");
        return Files.write(tempFile, content);
    }

    private static void joinOrFail(Thread thread) throws InterruptedException {
        thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS * 2));
        assertThat(thread.isAlive()).as("并发线程必须已结束").isFalse();
    }

    private static void assertTempDirectoryEmpty(Path root) throws IOException {
        Path tempRoot = root.resolve("tmp");
        if (!Files.isDirectory(tempRoot)) {
            return;
        }
        try (Stream<Path> files = Files.list(tempRoot)) {
            assertThat(files.toList()).as("临时目录必须为空").isEmpty();
        }
    }

    private static void assertNoClaimFiles(Path root) throws IOException {
        Path tempRoot = root.resolve("tmp");
        if (!Files.isDirectory(tempRoot)) {
            return;
        }
        try (Stream<Path> files = Files.list(tempRoot)) {
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                    .as("占位锁不得残留").noneMatch(name -> name.endsWith(".claim"));
        }
    }
}
