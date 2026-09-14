package com.flowdesk.application.knowledge;

import static com.flowdesk.application.knowledge.KnowledgeTestSupport.DOCX_CONTENT;
import static com.flowdesk.application.knowledge.KnowledgeTestSupport.PDF_CONTENT;
import static com.flowdesk.application.knowledge.KnowledgeTestSupport.TEXT_CONTENT;
import static com.flowdesk.application.knowledge.KnowledgeTestSupport.assertApplicationError;
import static org.assertj.core.api.Assertions.assertThat;

import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.application.knowledge.KnowledgeTestSupport;
import com.flowdesk.application.knowledge.content.DocumentMediaTypes;
import com.flowdesk.application.knowledge.content.GuardedContentSource;
import com.flowdesk.application.knowledge.content.OriginalFilenames;
import com.flowdesk.application.knowledge.content.SignatureValidatingInputStream;
import com.flowdesk.application.knowledge.content.SizeLimitedInputStream;
import com.flowdesk.application.knowledge.content.Utf8ValidatingInputStream;
import com.flowdesk.application.knowledge.port.out.ContentSource;
import com.flowdesk.domain.knowledge.DocumentFormat;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * 上传内容守卫测试：文件名规范化、媒体类型一致性、流式限流、文件头与 UTF-8 校验。
 *
 * <p>这些是「安全上传链路」最内层的规则，因此测试直接作用在流上，逐字节验证行为。</p>
 */
class KnowledgeContentGuardsTest {

    // ---------- 文件名规范化 ----------

    @Test
    void takesTheLastPathSegmentForAnySeparator() {
        assertThat(OriginalFilenames.basename("C:\\fakepath\\report.pdf")).isEqualTo("report.pdf");
        assertThat(OriginalFilenames.basename("docs/sub/report.pdf")).isEqualTo("report.pdf");
        assertThat(OriginalFilenames.basename("..\\..\\windows\\system32\\config.sys"))
                .isEqualTo("config.sys");
        assertThat(OriginalFilenames.basename("../../etc/passwd")).isEqualTo("passwd");
        assertThat(OriginalFilenames.basename("mixed/dir\\file.txt")).isEqualTo("file.txt");
    }

    @Test
    void keepsPlainNamesAndStripsSurroundingWhitespace() {
        assertThat(OriginalFilenames.basename("report.pdf")).isEqualTo("report.pdf");
        assertThat(OriginalFilenames.basename("  report.pdf  ")).isEqualTo("report.pdf");
        assertThat(OriginalFilenames.basename("\u3000报告.MD\u3000")).isEqualTo("报告.MD");
    }

    @Test
    void returnsNullWhenNoUsableNameRemains() {
        assertThat(OriginalFilenames.basename(null)).isNull();
        assertThat(OriginalFilenames.basename("")).isNull();
        assertThat(OriginalFilenames.basename("   ")).isNull();
        assertThat(OriginalFilenames.basename("dir/")).isNull();
        assertThat(OriginalFilenames.basename("dir\\")).isNull();
    }

    // ---------- 媒体类型一致性 ----------

    @Test
    void normalizesDeclaredContentType() {
        assertThat(DocumentMediaTypes.normalize("  TEXT/Plain ; charset=UTF-8 ")).isEqualTo("text/plain");
        assertThat(DocumentMediaTypes.normalize("application/pdf")).isEqualTo("application/pdf");
        assertThat(DocumentMediaTypes.normalize(null)).isNull();
        assertThat(DocumentMediaTypes.normalize("   ")).isNull();
        assertThat(DocumentMediaTypes.normalize(";charset=utf-8")).isNull();
    }

    @Test
    void treatsMissingOrGenericContentTypeAsNoDeclaration() {
        for (DocumentFormat format : DocumentFormat.values()) {
            assertThat(DocumentMediaTypes.isCompatible(format, null)).isTrue();
            assertThat(DocumentMediaTypes.isCompatible(format, "")).isTrue();
            assertThat(DocumentMediaTypes.isCompatible(format, "application/octet-stream")).isTrue();
        }
    }

    @Test
    void acceptsCompatibleDeclarations() {
        assertThat(DocumentMediaTypes.isCompatible(DocumentFormat.PDF, "application/pdf")).isTrue();
        assertThat(DocumentMediaTypes.isCompatible(DocumentFormat.PDF, "APPLICATION/PDF")).isTrue();
        assertThat(DocumentMediaTypes.isCompatible(DocumentFormat.DOCX,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document")).isTrue();
        assertThat(DocumentMediaTypes.isCompatible(DocumentFormat.DOCX, "application/zip")).isTrue();
        assertThat(DocumentMediaTypes.isCompatible(DocumentFormat.MARKDOWN, "text/markdown")).isTrue();
        assertThat(DocumentMediaTypes.isCompatible(DocumentFormat.MARKDOWN, "text/plain")).isTrue();
        assertThat(DocumentMediaTypes.isCompatible(DocumentFormat.TEXT, "text/plain; charset=utf-8")).isTrue();
    }

    @Test
    void rejectsIncompatibleDeclarations() {
        assertThat(DocumentMediaTypes.isCompatible(DocumentFormat.PDF, "image/png")).isFalse();
        assertThat(DocumentMediaTypes.isCompatible(DocumentFormat.PDF, "text/plain")).isFalse();
        assertThat(DocumentMediaTypes.isCompatible(DocumentFormat.TEXT, "application/pdf")).isFalse();
        assertThat(DocumentMediaTypes.isCompatible(DocumentFormat.TEXT, "image/jpeg")).isFalse();
        assertThat(DocumentMediaTypes.isCompatible(DocumentFormat.MARKDOWN, "application/json")).isFalse();
        // 老版 .doc 的媒体类型不能当成 docx
        assertThat(DocumentMediaTypes.isCompatible(DocumentFormat.DOCX, "application/msword")).isFalse();
    }

    // ---------- 限流 ----------

    @Test
    void limitsReadsByActualByteCount() throws IOException {
        byte[] content = new byte[100];
        Arrays.fill(content, (byte) 'x');
        long[] observedLimit = { -1L };

        try (InputStream limited = new SizeLimitedInputStream(new java.io.ByteArrayInputStream(content), 100L,
                limit -> observedLimit[0] = limit)) {
            assertThat(readAll(limited)).hasSize(100);
        }
        assertThat(observedLimit[0]).as("恰好等于上限不算超限").isEqualTo(-1L);

        try (InputStream limited = new SizeLimitedInputStream(new java.io.ByteArrayInputStream(content), 99L,
                limit -> {
                    throw new KnowledgeApplicationException(
                            KnowledgeApplicationErrorCode.DOCUMENT_TOO_LARGE, "超限");
                })) {
            assertApplicationError(() -> readAll(limited), KnowledgeApplicationErrorCode.DOCUMENT_TOO_LARGE);
        }
    }

    @Test
    void countsSingleByteReadsAsWell() throws IOException {
        byte[] content = new byte[10];
        try (SizeLimitedInputStream limited = new SizeLimitedInputStream(
                new java.io.ByteArrayInputStream(content), 5L, limit -> {
                    throw new KnowledgeApplicationException(
                            KnowledgeApplicationErrorCode.DOCUMENT_TOO_LARGE, "超限");
                })) {
            for (int index = 0; index < 5; index++) {
                assertThat(limited.read()).isNotNegative();
            }
            assertThat(limited.bytesRead()).isEqualTo(5L);
            assertApplicationError(limited::read, KnowledgeApplicationErrorCode.DOCUMENT_TOO_LARGE);
        }
    }

    @Test
    void rejectsNonPositiveLimit() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> new SizeLimitedInputStream(
                new java.io.ByteArrayInputStream(new byte[1]), 0L, limit -> { })))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- 限流：最多只多读一个字节（FD-0008-R1） ----------

    @Test
    void bulkReadConsumesAtMostOneByteBeyondTheLimit() {
        // 复现验收场景：上限 5 字节，调用方一次请求 1024 字节
        ConsumingInputStream delegate = new ConsumingInputStream(100);
        SizeLimitedInputStream limited = new SizeLimitedInputStream(delegate, 5L, limit -> {
            throw new KnowledgeApplicationException(
                    KnowledgeApplicationErrorCode.DOCUMENT_TOO_LARGE, "超限");
        });

        byte[] buffer = new byte[1024];
        assertApplicationError(() -> limited.read(buffer, 0, 1024),
                KnowledgeApplicationErrorCode.DOCUMENT_TOO_LARGE);

        assertThat(delegate.consumed())
                .as("上限 5、单次请求 1024：底层累计消费必须 ≤ 上限 + 1")
                .isEqualTo(6L);
    }

    @Test
    void exactlyTheLimitIsStillAccepted() throws IOException {
        ConsumingInputStream delegate = new ConsumingInputStream(10);
        SizeLimitedInputStream limited = new SizeLimitedInputStream(delegate, 10L, limit -> {
            throw new KnowledgeApplicationException(
                    KnowledgeApplicationErrorCode.DOCUMENT_TOO_LARGE, "超限");
        });

        assertThat(readAll(limited)).as("恰好等于上限必须合法（不得弱化该规则）").hasSize(10);
        assertThat(delegate.consumed()).isEqualTo(10L);
        assertThat(limited.bytesRead()).isEqualTo(10L);
    }

    @Test
    void oneByteBeyondTheLimitConsumesExactlyLimitPlusOne() {
        ConsumingInputStream delegate = new ConsumingInputStream(100);
        SizeLimitedInputStream limited = new SizeLimitedInputStream(delegate, 5L, limit -> {
            throw new KnowledgeApplicationException(
                    KnowledgeApplicationErrorCode.DOCUMENT_TOO_LARGE, "超限");
        });

        assertApplicationError(() -> readAll(limited), KnowledgeApplicationErrorCode.DOCUMENT_TOO_LARGE);

        assertThat(delegate.consumed()).as("max + 1 字节时不得再往前拖数据").isEqualTo(6L);
    }

    @Test
    void repeatedBulkReadsStayBounded() {
        ConsumingInputStream delegate = new ConsumingInputStream(10_000);
        SizeLimitedInputStream limited = new SizeLimitedInputStream(delegate, 5L, limit -> {
            throw new KnowledgeApplicationException(
                    KnowledgeApplicationErrorCode.DOCUMENT_TOO_LARGE, "超限");
        });
        byte[] buffer = new byte[4096];

        // 第一次批量读就会被拦截；即便调用方捕获异常后继续读，也不得再消费底层数据
        for (int attempt = 0; attempt < 5; attempt++) {
            assertApplicationError(() -> limited.read(buffer, 0, 4096),
                    KnowledgeApplicationErrorCode.DOCUMENT_TOO_LARGE);
        }

        assertThat(delegate.consumed()).isEqualTo(6L);
    }

    @Test
    void skipCannotBypassTheLimit() {
        ConsumingInputStream delegate = new ConsumingInputStream(10_000);
        SizeLimitedInputStream limited = new SizeLimitedInputStream(delegate, 5L, limit -> {
            throw new KnowledgeApplicationException(
                    KnowledgeApplicationErrorCode.DOCUMENT_TOO_LARGE, "超限");
        });

        assertApplicationError(() -> limited.skip(9000L), KnowledgeApplicationErrorCode.DOCUMENT_TOO_LARGE);

        assertThat(delegate.consumed()).as("skip 同样受 remaining + 1 约束").isEqualTo(6L);
    }

    @Test
    void signatureValidationCannotBeSkipped() {
        byte[] expected = "%PDF-".getBytes(StandardCharsets.US_ASCII);

        try (SignatureValidatingInputStream stream = new SignatureValidatingInputStream(
                new java.io.ByteArrayInputStream(TEXT_CONTENT), expected, () -> {
                    throw new KnowledgeApplicationException(
                            KnowledgeApplicationErrorCode.UNSUPPORTED_DOCUMENT_FORMAT, "签名不匹配");
                })) {
            assertApplicationError(() -> stream.skip(TEXT_CONTENT.length),
                    KnowledgeApplicationErrorCode.UNSUPPORTED_DOCUMENT_FORMAT);
        }
        catch (IOException ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    void utf8ValidationCannotBeSkipped() {
        byte[] invalid = { (byte) 0xC3, (byte) 0x28, 0x41, 0x42 };

        try (Utf8ValidatingInputStream stream = new Utf8ValidatingInputStream(
                new java.io.ByteArrayInputStream(invalid), reason -> {
                    throw new KnowledgeApplicationException(
                            KnowledgeApplicationErrorCode.UNSUPPORTED_DOCUMENT_FORMAT, reason);
                })) {
            assertApplicationError(() -> stream.skip(invalid.length),
                    KnowledgeApplicationErrorCode.UNSUPPORTED_DOCUMENT_FORMAT);
        }
        catch (IOException ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    void nulCheckCannotBeSkipped() {
        byte[] withNul = { 'a', 0x00, 'b', 'c' };

        try (Utf8ValidatingInputStream stream = new Utf8ValidatingInputStream(
                new java.io.ByteArrayInputStream(withNul), reason -> {
                    throw new KnowledgeApplicationException(
                            KnowledgeApplicationErrorCode.UNSUPPORTED_DOCUMENT_FORMAT, reason);
                })) {
            assertApplicationError(() -> stream.skip(withNul.length),
                    KnowledgeApplicationErrorCode.UNSUPPORTED_DOCUMENT_FORMAT);
        }
        catch (IOException ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    void validatedSkipStillReturnsTheSkippedCountOnValidContent() throws IOException {
        byte[] content = "0123456789".getBytes(StandardCharsets.UTF_8);

        try (SignatureValidatingInputStream stream = new SignatureValidatingInputStream(
                new java.io.ByteArrayInputStream(content), "012".getBytes(StandardCharsets.US_ASCII),
                () -> {
                    throw new IllegalStateException("不应触发");
                })) {
            assertThat(stream.skip(4L)).isEqualTo(4L);
            assertThat(readAll(stream)).containsExactly('4', '5', '6', '7', '8', '9');
        }
    }

    // ---------- 文件头 ----------

    @Test
    void acceptsValidSignatureAndPreservesEveryByte() throws IOException {
        byte[] expected = "%PDF-".getBytes(StandardCharsets.US_ASCII);

        try (SignatureValidatingInputStream stream = new SignatureValidatingInputStream(
                new java.io.ByteArrayInputStream(PDF_CONTENT), expected, () -> {
                    throw new IllegalStateException("不应触发");
                })) {
            assertThat(readAll(stream)).as("偷看文件头不能丢字节").isEqualTo(PDF_CONTENT);
        }
    }

    @Test
    void signatureCheckWorksAcrossMultipleReads() throws IOException {
        byte[] expected = "%PDF-".getBytes(StandardCharsets.US_ASCII);

        try (SignatureValidatingInputStream stream = new SignatureValidatingInputStream(
                new java.io.ByteArrayInputStream(PDF_CONTENT), expected, () -> {
                    throw new IllegalStateException("不应触发");
                })) {
            ByteArrayOutputStream collected = new ByteArrayOutputStream();
            int value;
            while ((value = stream.read()) >= 0) {
                collected.write(value);
            }
            assertThat(collected.toByteArray()).isEqualTo(PDF_CONTENT);
        }
    }

    @Test
    void rejectsWrongOrTruncatedSignature() {
        byte[] expected = { 0x50, 0x4B, 0x03, 0x04 };

        try (SignatureValidatingInputStream stream = new SignatureValidatingInputStream(
                new java.io.ByteArrayInputStream(PDF_CONTENT), expected, () -> {
                    throw new KnowledgeApplicationException(
                            KnowledgeApplicationErrorCode.UNSUPPORTED_DOCUMENT_FORMAT, "签名不匹配");
                })) {
            assertApplicationError(() -> readAll(stream),
                    KnowledgeApplicationErrorCode.UNSUPPORTED_DOCUMENT_FORMAT);
        }
        catch (IOException ex) {
            throw new AssertionError(ex);
        }

        try (SignatureValidatingInputStream stream = new SignatureValidatingInputStream(
                new java.io.ByteArrayInputStream(new byte[] { 0x50, 0x4B }), expected, () -> {
                    throw new KnowledgeApplicationException(
                            KnowledgeApplicationErrorCode.UNSUPPORTED_DOCUMENT_FORMAT, "签名不匹配");
                })) {
            assertApplicationError(() -> readAll(stream),
                    KnowledgeApplicationErrorCode.UNSUPPORTED_DOCUMENT_FORMAT);
        }
        catch (IOException ex) {
            throw new AssertionError(ex);
        }
    }

    // ---------- UTF-8 ----------

    @Test
    void acceptsValidUtf8IncludingMultibyteCharacters() throws IOException {
        byte[] content = "中文内容 with ASCII\n".getBytes(StandardCharsets.UTF_8);

        try (Utf8ValidatingInputStream stream = new Utf8ValidatingInputStream(
                new java.io.ByteArrayInputStream(content), reason -> {
                    throw new IllegalStateException("不应触发：" + reason);
                })) {
            assertThat(readAll(stream)).isEqualTo(content);
        }
    }

    @Test
    void acceptsMultibyteCharacterSplitAcrossChunkBoundary() throws IOException {
        byte[] chinese = "中文".getBytes(StandardCharsets.UTF_8);
        // 用 1 字节一块的「底层流」把多字节字符切碎：解码器必须靠自身状态处理
        byte[] content = new byte[chinese.length];
        System.arraycopy(chinese, 0, content, 0, chinese.length);

        try (Utf8ValidatingInputStream stream = new Utf8ValidatingInputStream(
                new ChunkedInputStream(content, 1), reason -> {
                    throw new IllegalStateException("不应触发：" + reason);
                })) {
            assertThat(readAll(stream)).isEqualTo(content);
        }
    }

    @Test
    void rejectsInvalidUtf8() throws IOException {
        byte[] invalid = { (byte) 0xC3, (byte) 0x28 };

        try (Utf8ValidatingInputStream stream = new Utf8ValidatingInputStream(
                new java.io.ByteArrayInputStream(invalid), reason -> {
                    throw new KnowledgeApplicationException(
                            KnowledgeApplicationErrorCode.UNSUPPORTED_DOCUMENT_FORMAT, reason);
                })) {
            assertApplicationError(() -> readAll(stream),
                    KnowledgeApplicationErrorCode.UNSUPPORTED_DOCUMENT_FORMAT);
        }
    }

    @Test
    void rejectsTruncatedMultibyteSequenceAtEndOfInput() throws IOException {
        byte[] truncated = { (byte) 0xE4, (byte) 0xB8 };

        try (Utf8ValidatingInputStream stream = new Utf8ValidatingInputStream(
                new java.io.ByteArrayInputStream(truncated), reason -> {
                    throw new KnowledgeApplicationException(
                            KnowledgeApplicationErrorCode.UNSUPPORTED_DOCUMENT_FORMAT, reason);
                })) {
            assertApplicationError(() -> readAll(stream),
                    KnowledgeApplicationErrorCode.UNSUPPORTED_DOCUMENT_FORMAT);
        }
    }

    @Test
    void rejectsNulEvenThoughItIsTechnicallyValidUtf8() throws IOException {
        byte[] withNul = { 'a', 0x00, 'b' };

        try (Utf8ValidatingInputStream stream = new Utf8ValidatingInputStream(
                new java.io.ByteArrayInputStream(withNul), reason -> {
                    throw new KnowledgeApplicationException(
                            KnowledgeApplicationErrorCode.UNSUPPORTED_DOCUMENT_FORMAT, reason);
                })) {
            assertApplicationError(() -> readAll(stream),
                    KnowledgeApplicationErrorCode.UNSUPPORTED_DOCUMENT_FORMAT);
        }
    }

    // ---------- 组合：GuardedContentSource ----------

    @Test
    void guardedSourceDelegatesMetadataAndClosesDelegate() {
        KnowledgeTestSupport.ByteArrayContentSource delegate = KnowledgeTestSupport.ByteArrayContentSource.of(
                TEXT_CONTENT, "a.txt", "text/plain");

        ContentSource guarded = GuardedContentSource.guard(delegate, DocumentFormat.TEXT, 1024L);

        assertThat(guarded.declaredFileName()).isEqualTo("a.txt");
        assertThat(guarded.declaredContentType()).isEqualTo("text/plain");
        assertThat(guarded.declaredSize()).isEqualTo(TEXT_CONTENT.length);
        assertThat(guarded.opened()).isFalse();

        guarded.close();

        assertThat(delegate.closes()).isEqualTo(1);
        assertThat(guarded.opened()).isFalse();
    }

    @Test
    void guardedSourceAppliesLimitAndSignatureTogether() throws IOException {
        // 限流在最内层：签名校验通过之后，超限仍然会被发现
        byte[] largePdf = new byte[4096];
        System.arraycopy(PDF_CONTENT, 0, largePdf, 0, PDF_CONTENT.length);
        ContentSource guarded = GuardedContentSource.guard(
                KnowledgeTestSupport.ByteArrayContentSource.of(largePdf, "a.pdf", "application/pdf"),
                DocumentFormat.PDF, 100L);

        assertApplicationError(() -> readAll(guarded.openStream()),
                KnowledgeApplicationErrorCode.DOCUMENT_TOO_LARGE);
    }

    @Test
    void guardedSourceRejectsWrongSignature() throws IOException {
        ContentSource guarded = GuardedContentSource.guard(
                KnowledgeTestSupport.ByteArrayContentSource.of(TEXT_CONTENT, "a.pdf", "application/pdf"),
                DocumentFormat.PDF, 1024L);

        assertApplicationError(() -> readAll(guarded.openStream()),
                KnowledgeApplicationErrorCode.UNSUPPORTED_DOCUMENT_FORMAT);
    }

    @Test
    void guardedSourceAcceptsDocxZipHeader() throws IOException {
        ContentSource guarded = GuardedContentSource.guard(
                KnowledgeTestSupport.ByteArrayContentSource.of(DOCX_CONTENT, "a.docx", null),
                DocumentFormat.DOCX, 1024L);

        assertThat(readAll(guarded.openStream())).isEqualTo(DOCX_CONTENT);
    }

    @Test
    void guardRejectsNullArguments() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> GuardedContentSource.guard(
                null, DocumentFormat.PDF, 1L))).isInstanceOf(IllegalArgumentException.class);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> GuardedContentSource.guard(
                KnowledgeTestSupport.ByteArrayContentSource.of(TEXT_CONTENT, "a.txt", null), null, 1L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- 辅助 ----------

    private static byte[] readAll(InputStream stream) throws IOException {
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        byte[] buffer = new byte[7];
        int read;
        while ((read = stream.read(buffer)) >= 0) {
            collected.write(buffer, 0, read);
        }
        return collected.toByteArray();
    }

    /**
     * 记录<b>底层实际被消费字节数</b>的输入流。
     *
     * <p>限流测试不能只断言「抛了异常」：真正的缺陷是「异常抛出之前已经从底层拖走了多少数据」，
     * 因此必须能观察到消费量。</p>
     */
    private static final class ConsumingInputStream extends InputStream {

        private final long available;

        private long consumed;

        ConsumingInputStream(long available) {
            this.available = available;
        }

        long consumed() {
            return this.consumed;
        }

        @Override
        public int read() {
            if (this.consumed >= this.available) {
                return -1;
            }
            this.consumed++;
            return 'x';
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (length == 0) {
                return 0;
            }
            long remaining = this.available - this.consumed;
            if (remaining <= 0L) {
                return -1;
            }
            int count = (int) Math.min(length, remaining);
            Arrays.fill(buffer, offset, offset + count, (byte) 'x');
            this.consumed += count;
            return count;
        }

        @Override
        public long skip(long count) {
            long skipped = Math.min(count, this.available - this.consumed);
            this.consumed += Math.max(skipped, 0L);
            return Math.max(skipped, 0L);
        }
    }

    /**
     * 固定块大小的底层流：用来模拟「多字节字符被读取边界切开」。
     */
    private static final class ChunkedInputStream extends InputStream {

        private final byte[] content;

        private final int chunkSize;

        private int position;

        ChunkedInputStream(byte[] content, int chunkSize) {
            this.content = content.clone();
            this.chunkSize = chunkSize;
        }

        @Override
        public int read() {
            return this.position >= this.content.length ? -1 : this.content[this.position++] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (this.position >= this.content.length) {
                return -1;
            }
            int count = Math.min(Math.min(length, this.chunkSize), this.content.length - this.position);
            System.arraycopy(this.content, this.position, buffer, offset, count);
            this.position += count;
            return count;
        }
    }
}
