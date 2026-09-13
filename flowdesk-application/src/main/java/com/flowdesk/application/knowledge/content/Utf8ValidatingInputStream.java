package com.flowdesk.application.knowledge.content;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * 文本格式的<b>流式 UTF-8 校验</b>输入流。
 *
 * <p>要求：{@code .md}/{@code .txt} 必须是合法 UTF-8 且不含 NUL。校验必须边读边做 ——
 * 把文件读进内存再判断等于放弃了流式处理的全部意义。</p>
 *
 * <h2>为什么要自己保存「残留字节」</h2>
 * <p>{@link CharsetDecoder} 的增量解码有一个容易踩的坑：当一块数据的<b>末尾</b>正好切在一个多字节
 * 字符中间时，解码会返回「输入不足」，而那几个字节<b>并没有被消费</b> —— 调用方必须把它们留到下一次
 * 连同新数据一起再喂进去。如果不留，末尾那个不完整序列就永远补不齐，
 * 最后在 EOF 时被判定为「畸形 UTF-8」，于是<b>完全合法的中文内容也会被拒绝</b>。
 * 本类把未消费的尾部字节缓存在 {@code pending} 里，在下一块或 EOF 时重新拼接后解码。</p>
 *
 * <p>NUL（{@code 0x00}）虽然技术上属于合法 UTF-8（U+0000），但文本文件里出现它几乎总是二进制
 * 内容走错了路，因此显式拒绝。</p>
 */
public final class Utf8ValidatingInputStream extends FilterInputStream {

    /** 未消费尾部字节的缓存上限（UTF-8 最长 4 字节，残留最多 3 字节）。 */
    private static final int PENDING_CAPACITY = 8;

    /** 解码出的字符缓冲区大小。 */
    private static final int CHAR_BUFFER_SIZE = 4096;

    private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);

    private final InvalidTextCallback onInvalidText;

    private final CharBuffer charBuffer = CharBuffer.allocate(CHAR_BUFFER_SIZE);

    private final ByteBuffer pending = ByteBuffer.allocate(PENDING_CAPACITY);

    private boolean hasPending;

    private boolean finished;

    /**
     * @param delegate      被包装的流
     * @param onInvalidText 文本非法（畸形 UTF-8 或含 NUL）时的回调
     */
    public Utf8ValidatingInputStream(InputStream delegate, InvalidTextCallback onInvalidText) {
        super(delegate);
        this.onInvalidText = onInvalidText;
    }

    @Override
    public int read() throws IOException {
        byte[] single = new byte[1];
        int read = read(single, 0, 1);
        return read <= 0 ? -1 : single[0] & 0xFF;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int read = this.in.read(buffer, offset, length);
        if (read > 0) {
            validate(buffer, offset, read);
        }
        else if (read < 0 && !this.finished) {
            this.finished = true;
            finishDecoding();
        }
        return read;
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    private void validate(byte[] buffer, int offset, int length) {
        for (int index = offset; index < offset + length; index++) {
            if (buffer[index] == 0x00) {
                this.onInvalidText.onInvalidText("内容包含 NUL 字符");
            }
        }

        ByteBuffer input;
        if (this.hasPending) {
            this.pending.flip();
            byte[] combined = new byte[this.pending.remaining() + length];
            this.pending.get(combined, 0, this.pending.remaining());
            System.arraycopy(buffer, offset, combined, combined.length - length, length);
            this.hasPending = false;
            this.pending.clear();
            input = ByteBuffer.wrap(combined);
        }
        else {
            input = ByteBuffer.wrap(buffer, offset, length);
        }

        decode(input, false);

        if (input.hasRemaining()) {
            // 尾部被切断的多字节序列：留到下一次（或 EOF）再解码
            this.pending.clear();
            this.pending.put(input);
            this.hasPending = true;
        }
    }

    private void finishDecoding() {
        ByteBuffer input;
        if (this.hasPending) {
            this.pending.flip();
            input = this.pending;
            this.hasPending = false;
        }
        else {
            input = ByteBuffer.allocate(0);
        }
        decode(input, true);
    }

    private void decode(ByteBuffer input, boolean endOfInput) {
        while (true) {
            this.charBuffer.clear();
            CoderResult result = this.decoder.decode(input, this.charBuffer, endOfInput);
            if (result.isMalformed() || result.isUnmappable()) {
                this.onInvalidText.onInvalidText("内容不是合法的 UTF-8 文本");
            }
            if (result.isOverflow()) {
                // 字符缓冲区太小：清空后继续消费剩余输入
                continue;
            }
            break;
        }
        if (endOfInput) {
            CoderResult flush = this.decoder.flush(this.charBuffer);
            if (flush.isError()) {
                this.onInvalidText.onInvalidText("内容不是合法的 UTF-8 文本");
            }
        }
    }

    /**
     * 文本非法回调。
     */
    @FunctionalInterface
    public interface InvalidTextCallback {

        /**
         * @param reason 固定安全文案
         */
        void onInvalidText(String reason);
    }
}
