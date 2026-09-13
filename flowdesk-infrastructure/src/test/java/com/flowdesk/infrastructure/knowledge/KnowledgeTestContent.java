package com.flowdesk.infrastructure.knowledge;

import com.flowdesk.application.knowledge.port.out.ContentSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 基础设施层知识测试的共享夹具：SHA-256 计算与可控内容源。
 */
public final class KnowledgeTestContent {

    private KnowledgeTestContent() {
    }

    /**
     * @param content 内容字节
     * @return 64 位小写十六进制 SHA-256
     */
    public static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * 基于字节数组的内容源，支持模拟「读到一半失败」与「读取时抛出应用层异常」。
     */
    public static final class ArrayContentSource implements ContentSource {

        private final byte[] content;

        private final String fileName;

        private final int failAfterBytes;

        private final RuntimeException runtimeFailure;

        private ArrayContentSource(byte[] content, String fileName, int failAfterBytes,
                RuntimeException runtimeFailure) {

            this.content = content.clone();
            this.fileName = fileName;
            this.failAfterBytes = failAfterBytes;
            this.runtimeFailure = runtimeFailure;
        }

        public static ArrayContentSource of(byte[] content, String fileName) {
            return new ArrayContentSource(content, fileName, -1, null);
        }

        /**
         * 读到指定字节数之后抛出 {@link IOException}，用于验证临时文件被清理。
         */
        public static ArrayContentSource failingAfter(byte[] content, int failAfterBytes) {
            return new ArrayContentSource(content, "a.txt", failAfterBytes, null);
        }

        /**
         * 打开流之后立刻抛出给定异常（例如应用层守卫发现的超限或格式错误）。
         */
        public static ArrayContentSource failingWith(RuntimeException failure) {
            return new ArrayContentSource(new byte[] { 1, 2, 3 }, "a.txt", 0, failure);
        }

        @Override
        public String declaredFileName() {
            return this.fileName;
        }

        @Override
        public String declaredContentType() {
            return null;
        }

        @Override
        public long declaredSize() {
            return this.content.length;
        }

        @Override
        public InputStream openStream() throws IOException {
            if (this.runtimeFailure != null) {
                return new InputStream() {

                    @Override
                    public int read() {
                        throw this.failure();
                    }

                    @Override
                    public int read(byte[] buffer, int offset, int length) {
                        throw this.failure();
                    }

                    private RuntimeException failure() {
                        return ArrayContentSource.this.runtimeFailure;
                    }
                };
            }
            if (this.failAfterBytes < 0) {
                return new ByteArrayInputStream(this.content);
            }
            return new InputStream() {

                private int position;

                @Override
                public int read() throws IOException {
                    if (this.position >= ArrayContentSource.this.failAfterBytes) {
                        throw new IOException("模拟读取失败");
                    }
                    return this.position >= ArrayContentSource.this.content.length
                            ? -1 : ArrayContentSource.this.content[this.position++] & 0xFF;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    if (this.position >= ArrayContentSource.this.failAfterBytes) {
                        throw new IOException("模拟读取失败");
                    }
                    int count = Math.min(length, ArrayContentSource.this.failAfterBytes - this.position);
                    count = Math.min(count, ArrayContentSource.this.content.length - this.position);
                    if (count <= 0) {
                        return -1;
                    }
                    System.arraycopy(ArrayContentSource.this.content, this.position, buffer, offset, count);
                    this.position += count;
                    return count;
                }
            };
        }

        @Override
        public boolean opened() {
            return false;
        }

        @Override
        public void close() {
            // 无外部资源需要释放
        }
    }
}
