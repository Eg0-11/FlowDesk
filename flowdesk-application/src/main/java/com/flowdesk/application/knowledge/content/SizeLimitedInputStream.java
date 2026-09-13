package com.flowdesk.application.knowledge.content;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 按<b>实际读取到的字节数</b>限流的输入流。
 *
 * <p>为什么不能只信客户端：{@code Content-Length}、{@code MultipartFile.getSize()}
 * 与 Servlet 容器配置都是<b>声明</b>，可能缺失、可能是错的，也可能是恶意构造的。
 * 唯一可靠的判断依据是一路读过来真正拿到了多少字节，所以限制必须落在读取路径上。</p>
 *
 * <p>超限时立刻抛出（而不是读完再判断）：调用方因此可以马上停止，
 * 不会为了一个注定被拒绝的请求把几百兆数据读完。</p>
 */
public final class SizeLimitedInputStream extends FilterInputStream {

    private final long maxBytes;

    private final ByteLimitExceededCallback onExceeded;

    private long bytesRead;

    /**
     * @param delegate   被包装的流
     * @param maxBytes   允许的最大字节数，必须大于 0
     * @param onExceeded 超限时的回调（在读取线程上同步执行）
     */
    public SizeLimitedInputStream(InputStream delegate, long maxBytes, ByteLimitExceededCallback onExceeded) {
        super(delegate);
        if (maxBytes <= 0L) {
            throw new IllegalArgumentException("maxBytes 必须大于 0");
        }
        this.maxBytes = maxBytes;
        this.onExceeded = onExceeded;
    }

    @Override
    public int read() throws IOException {
        int value = this.in.read();
        if (value >= 0) {
            count(1);
        }
        return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int read = this.in.read(buffer, offset, length);
        if (read > 0) {
            count(read);
        }
        return read;
    }

    @Override
    public long skip(long count) throws IOException {
        long skipped = this.in.skip(count);
        if (skipped > 0) {
            count(skipped);
        }
        return skipped;
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    /**
     * @return 到目前为止实际读取到的字节数
     */
    public long bytesRead() {
        return this.bytesRead;
    }

    private void count(long delta) {
        this.bytesRead += delta;
        if (this.bytesRead > this.maxBytes) {
            this.onExceeded.onLimitExceeded(this.maxBytes);
        }
    }

    /**
     * 超限回调：由调用方决定抛出什么异常，从而让限流逻辑与错误契约解耦。
     */
    @FunctionalInterface
    public interface ByteLimitExceededCallback {

        /**
         * @param maxBytes 配置的上限
         */
        void onLimitExceeded(long maxBytes);
    }
}
