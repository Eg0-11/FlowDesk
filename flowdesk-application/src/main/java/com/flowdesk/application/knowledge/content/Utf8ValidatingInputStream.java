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

    /** 跳过时使用的临时缓冲区大小。 */
    private static final int SCRATCH_SIZE = 4096;

    private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);

    private final InvalidTextCallback onInvalidText;

    private final CharBuffer charBuffer = CharBuffer.allocate(CHAR_BUFFER_SIZE);

    private final ByteBuffer pending = ByteBuffer.allocate(PENDING_CAPACITY);

    private boolean hasPending;

    private boolean finished;

    /**
     * 前瞻到的一个字节（{@code -1} 表示没有）。
     *
     * <p>它已经过校验（NUL 检查 + 解码），因此在后续 {@code read} 中必须<b>原样、恰好一次</b>
     * 交还调用方，并且不再重复喂给解码器。</p>
     */
    private int lookahead = -1;

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
        if (length == 0) {
            return 0;
        }
        if (this.lookahead >= 0) {
            // 前瞻字节优先交还：它已经校验过，且只能交还一次
            buffer[offset] = (byte) this.lookahead;
            this.lookahead = -1;
            return 1;
        }
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

    /**
     * 跳过必须经过本类的 {@code read}，否则调用方可以 skip 掉内容来绕过 UTF-8 与 NUL 校验。
     *
     * <p>这里还需要处理一个更隐蔽的漏洞：如果恰好跳过「请求的字节数」就停下，
     * 那么被跳过内容的<b>末尾截断序列</b>永远不会被检查 —— 例如只含一个字节 {@code 0xC3}
     * 的流调用 {@code skip(1)} 会「成功」，而它其实是一个缺少后续字节的非法 UTF-8 前导字节。</p>
     *
     * <p>做法是在跳完之后做一次<b>单字节前瞻</b>（不依赖 {@code available()}）：
     * 若前瞻发现 EOF，解码器就会在这里完成 {@code endOfInput} 与 {@code flush}，
     * 截断序列随之被判定为非法；若前瞻拿到一个字节，则把它存起来交给后续 {@code read}
     * （原样、恰好一次、不重复解码）。</p>
     */
    @Override
    public long skip(long count) throws IOException {
        if (count <= 0L) {
            return 0L;
        }
        byte[] scratch = new byte[(int) Math.min(count, SCRATCH_SIZE)];
        long skipped = 0L;
        while (skipped < count) {
            int read = read(scratch, 0, (int) Math.min(scratch.length, count - skipped));
            if (read < 0) {
                // 真实 EOF：read 内部已经完成 endOfInput/flush，截断序列会在此抛错
                return skipped;
            }
            skipped += read;
        }
        probeEndOfInput();
        return skipped;
    }

    /**
     * 单字节前瞻：只用于在「恰好跳完」时确认是否已经到达真实 EOF。
     */
    private void probeEndOfInput() throws IOException {
        if (this.lookahead >= 0 || this.finished) {
            return;
        }
        byte[] single = new byte[1];
        int read = read(single, 0, 1);
        if (read > 0) {
            this.lookahead = single[0] & 0xFF;
        }
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
