package com.flowdesk.application.knowledge.content;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * 只读取开头若干字节做<b>文件头签名</b>校验的输入流。
 *
 * <p>二进制格式（PDF、DOCX）不可能靠扩展名信任：把 {@code .pdf} 改个名就能绕过。
 * 这里在<b>第一次读取时</b>取够签名长度做校验，然后把已读出的字节先喂给调用方，
 * 再继续从底层流读 —— 因此既没有把整个文件载入内存，也不会因为「偷看」而丢字节。</p>
 */
public final class SignatureValidatingInputStream extends FilterInputStream {

    private final byte[] expectedPrefix;

    private final SignatureMismatchCallback onMismatch;

    private byte[] header = new byte[0];

    private int headerOffset;

    private boolean validated;

    /**
     * @param delegate       被包装的流
     * @param expectedPrefix 期望的前缀字节（例如 {@code %PDF-}）
     * @param onMismatch     不匹配时的回调
     */
    public SignatureValidatingInputStream(InputStream delegate, byte[] expectedPrefix,
            SignatureMismatchCallback onMismatch) {

        super(delegate);
        this.expectedPrefix = expectedPrefix.clone();
        this.onMismatch = onMismatch;
    }

    @Override
    public int read() throws IOException {
        ensureValidated();
        if (this.headerOffset < this.header.length) {
            return this.header[this.headerOffset++] & 0xFF;
        }
        return this.in.read();
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        ensureValidated();
        if (this.headerOffset < this.header.length) {
            int available = Math.min(length, this.header.length - this.headerOffset);
            System.arraycopy(this.header, this.headerOffset, buffer, offset, available);
            this.headerOffset += available;
            return available;
        }
        return this.in.read(buffer, offset, length);
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    /**
     * 跳过必须经过本类的 {@code read}，否则调用方可以 skip 掉整个文件来绕过文件头校验。
     */
    @Override
    public long skip(long count) throws IOException {
        return ValidatedSkips.skip(this, count);
    }

    private void ensureValidated() throws IOException {
        if (this.validated) {
            return;
        }
        this.validated = true;
        // 注意：这里对 delegate 调用 readNBytes，不会递归回本类的 read
        byte[] readHeader = this.in.readNBytes(this.expectedPrefix.length);
        if (readHeader.length < this.expectedPrefix.length
                || !Arrays.equals(readHeader, this.expectedPrefix)) {
            this.onMismatch.onSignatureMismatch();
        }
        // 已读出的字节必须原样交回调用方：这里的「偷看」不能导致内容缺头
        this.header = readHeader;
        this.headerOffset = 0;
    }

    /**
     * 签名不匹配回调。
     */
    @FunctionalInterface
    public interface SignatureMismatchCallback {

        /**
         * 签名与声明格式不一致。
         */
        void onSignatureMismatch();
    }
}
