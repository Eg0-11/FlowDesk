package com.flowdesk.application.knowledge.content;

import java.io.IOException;
import java.io.InputStream;

/**
 * 受校验输入流的 {@code skip} 实现：<b>用 read 来跳过</b>。
 *
 * <p>为什么需要：{@link java.io.FilterInputStream#skip(long)} 默认直接委托给底层流，
 * 于是「跳过」这条路径会绕过文件头校验、UTF-8 解码与 NUL 检查 ——
 * 调用方只要先 skip 掉全部内容，就等于既拿到了「校验通过」的结论，又什么都没校验。</p>
 *
 * <p>这里的做法是把 skip 变成「读进临时缓冲区并丢弃」，因此被跳过的字节同样会经过包装流的
 * {@code read}，所有校验照常生效。跳过长度仍受底层的限流约束（超限时照常抛错）。</p>
 */
final class ValidatedSkips {

    /** 跳过时使用的临时缓冲区大小。 */
    private static final int SCRATCH_SIZE = 4096;

    private ValidatedSkips() {
    }

    /**
     * 通过 {@code stream.read(...)} 跳过至多 {@code count} 字节。
     *
     * @param stream 受校验的输入流（必须已重写 read）
     * @param count  期望跳过的字节数
     * @return 实际跳过的字节数
     * @throws IOException 读取失败或校验失败
     */
    static long skip(InputStream stream, long count) throws IOException {
        if (count <= 0L) {
            return 0L;
        }
        byte[] scratch = new byte[(int) Math.min(count, SCRATCH_SIZE)];
        long skipped = 0L;
        while (skipped < count) {
            int read = stream.read(scratch, 0, (int) Math.min(scratch.length, count - skipped));
            if (read < 0) {
                break;
            }
            skipped += read;
        }
        return skipped;
    }
}
