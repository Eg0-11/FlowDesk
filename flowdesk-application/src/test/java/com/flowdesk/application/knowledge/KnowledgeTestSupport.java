package com.flowdesk.application.knowledge;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowdesk.application.knowledge.port.out.ContentSource;
import com.flowdesk.domain.knowledge.DocumentFormat;
import com.flowdesk.domain.knowledge.KnowledgeDocumentId;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

/**
 * 知识文档应用层测试共享夹具与替身。
 *
 * <p>纯 JUnit 5 + AssertJ，不启动 Spring。</p>
 */
final class KnowledgeTestSupport {

    static final KnowledgeDocumentId DOCUMENT_ID =
            KnowledgeDocumentId.of(UUID.fromString("11111111-2222-3333-4444-555555555555"));

    static final Instant UPLOADED_AT = Instant.parse("2026-05-01T10:00:00Z");

    /**
     * @return 一个随机的合法文档标识（用于「切片归属错误」这类反例）
     */
    static KnowledgeDocumentId randomDocumentId() {
        return KnowledgeDocumentId.of(UUID.randomUUID());
    }

    /** 一份合法的 UTF-8 文本内容。 */
    static final byte[] TEXT_CONTENT = "知识库文档内容\n".getBytes(StandardCharsets.UTF_8);

    /** 一份合法的 PDF 内容（只需要文件头正确，本任务不解压、不解析）。 */
    static final byte[] PDF_CONTENT = "%PDF-1.7\nbody".getBytes(StandardCharsets.US_ASCII);

    /** 一份合法的 DOCX 内容（ZIP 本地文件头 + 任意字节）。 */
    static final byte[] DOCX_CONTENT = { 0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00 };

    private KnowledgeTestSupport() {
    }

    /** 断言抛出应用层异常，且错误码为期望值（不解析文案）。 */
    static void assertApplicationError(ThrowingCallable callable, KnowledgeApplicationErrorCode expected) {
        assertThatThrownBy(callable)
                .isInstanceOf(KnowledgeApplicationException.class)
                .extracting(thrown -> ((KnowledgeApplicationException) thrown).errorCode())
                .isEqualTo(expected);
    }

    /** 断言抛出领域异常（输入规范化的最终裁决在领域层）。 */
    static void assertDomainError(ThrowingCallable callable,
            com.flowdesk.domain.knowledge.KnowledgeErrorCode expected) {

        assertThatThrownBy(callable)
                .isInstanceOf(com.flowdesk.domain.knowledge.KnowledgeDomainException.class)
                .extracting(thrown -> ((com.flowdesk.domain.knowledge.KnowledgeDomainException) thrown)
                        .errorCode())
                .isEqualTo(expected);
    }

    /** @return 抛出的异常实例，便于进一步断言文案与 cause */
    static RuntimeException capture(ThrowingCallable callable) {
        try {
            callable.call();
        }
        catch (RuntimeException ex) {
            return ex;
        }
        catch (Throwable ex) {
            throw new AssertionError("期望 RuntimeException，实际为 " + ex.getClass().getName(), ex);
        }
        throw new AssertionError("期望抛出异常，但调用正常返回");
    }

    /**
     * 字节数组内容源：记录打开与关闭次数，并强制「只能打开一次」。
     */
    static final class ByteArrayContentSource implements ContentSource {

        private final byte[] content;

        private final String fileName;

        private final String contentType;

        private final long declaredSize;

        private int opens;

        private int closes;

        ByteArrayContentSource(byte[] content, String fileName, String contentType, long declaredSize) {
            this.content = content.clone();
            this.fileName = fileName;
            this.contentType = contentType;
            this.declaredSize = declaredSize;
        }

        /** 声明大小与真实大小一致的常规构造。 */
        static ByteArrayContentSource of(byte[] content, String fileName, String contentType) {
            return new ByteArrayContentSource(content, fileName, contentType, content.length);
        }

        /** 声明大小与真实大小不一致的构造：用来验证「只信实际读取量」。 */
        static ByteArrayContentSource lyingAboutSize(byte[] content, String fileName, String contentType,
                long declaredSize) {

            return new ByteArrayContentSource(content, fileName, contentType, declaredSize);
        }

        /** 常见的文本上传：声明 text/plain。 */
        static ByteArrayContentSource text(byte[] content, String fileName) {
            return of(content, fileName, "text/plain");
        }

        @Override
        public String declaredFileName() {
            return this.fileName;
        }

        @Override
        public String declaredContentType() {
            return this.contentType;
        }

        @Override
        public long declaredSize() {
            return this.declaredSize;
        }

        @Override
        public InputStream openStream() throws IOException {
            if (this.opens > 0) {
                throw new IllegalStateException("上传内容只能被读取一次");
            }
            this.opens++;
            return new ByteArrayInputStream(this.content);
        }

        @Override
        public boolean opened() {
            return this.opens > 0;
        }

        @Override
        public void close() {
            this.closes++;
        }

        int opens() {
            return this.opens;
        }

        int closes() {
            return this.closes;
        }
    }

    /**
     * 便捷构造：给定格式对应的合法内容。
     */
    static byte[] validContentFor(DocumentFormat format) {
        return switch (format) {
            case PDF -> PDF_CONTENT;
            case DOCX -> DOCX_CONTENT;
            case MARKDOWN, TEXT -> TEXT_CONTENT;
        };
    }

    /**
     * 计算内容的小写十六进制 SHA-256，用于与实现产出的摘要对照。
     *
     * @param content 内容字节
     * @return 64 位小写十六进制摘要
     */
    static String sha256Hex(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(content));
        }
        catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
