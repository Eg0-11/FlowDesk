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
 * <h2>「最多只多读一个字节」</h2>
 * <p>只在下游抛出异常是不够的：如果批量读取把调用方请求的长度（例如 1 MiB）原样透传给底层流，
 * 那么「超限」被发现之前，底层已经被读掉了一大截 —— 对 5 字节上限的请求可以从网络/磁盘上拖走 100 字节，
 * 攻击者只要反复发超大请求就能持续消耗 I/O 与磁盘临时空间。</p>
 * <p>因此每次批量读取传给底层的长度都被收敛为 {@code remaining + 1}：</p>
 * <ul>
 *   <li>{@code remaining} 是「距离上限还差多少字节」；</li>
 *   <li>多出的那 <b>1</b> 个字节是必要的：内容长度<b>恰好等于上限</b>时必须能读到 EOF，
 *       否则会把合法内容误判为超限；</li>
 *   <li>于是累计消费量最多是 {@code maxBytes + 1}，超过即抛错并停止读取。</li>
 * </ul>
 *
 * <p>{@code skip} 同样受此约束，不能靠一次大跨度跳过绕开限制。</p>
 *
 * <h2>超限是<b>终态</b></h2>
 * <p>「抛错」本身不够：调用方可以捕获异常后继续调用 {@code read()}，如果每次调用都先去底层读一个字节，
 * 那么重试 10 次就会从底层拖走 10 个字节 —— 上限形同虚设。因此第一次读到第 {@code max + 1} 个字节时：</p>
 * <ol>
 *   <li>消费掉那唯一的探测字节；</li>
 *   <li><b>先把流置为永久超限</b>，再触发回调（回调抛异常也不会丢失终态）；</li>
 *   <li>此后 {@code read()}、{@code read(byte[], int, int)}、{@code skip(long)} 一律立即失败，
 *       <b>绝不再访问底层流</b>。</li>
 * </ol>
 * <p>于是底层消费量与 {@link #bytesRead()} 都<b>不可能超过 {@code max + 1}</b>，
 * 无论调用方如何重试或混用三种读取方式。</p>
 *
 * <p>零长度的 {@code read(buffer, off, 0)} 与 {@code skip(0)} 按 {@link InputStream} 的约定返回 0：
 * 它们本来就不访问底层，也不改变任何状态，因此不受终态影响。</p>
 */
public final class SizeLimitedInputStream extends FilterInputStream {

    private final long maxBytes;

    private final ByteLimitExceededCallback onExceeded;

    private long bytesRead;

    private boolean exceeded;

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
        if (this.exceeded) {
            failBecauseExceeded();
        }
        int value = this.in.read();
        if (value >= 0) {
            record(1);
        }
        return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        if (this.exceeded) {
            failBecauseExceeded();
        }
        int allowed = (int) Math.min(length, readAllowance());
        int read = this.in.read(buffer, offset, allowed);
        if (read > 0) {
            record(read);
        }
        return read;
    }

    @Override
    public long skip(long count) throws IOException {
        if (count <= 0L) {
            return 0L;
        }
        if (this.exceeded) {
            failBecauseExceeded();
        }
        long allowed = Math.min(count, readAllowance());
        long skipped = this.in.skip(allowed);
        if (skipped > 0L) {
            record(skipped);
        }
        return skipped;
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    @Override
    public synchronized void reset() throws IOException {
        // 不支持 mark/reset：reset 必须失败，绝不能把限流计数倒回去
        throw new IOException("mark/reset 不受支持");
    }

    /**
     * @return 到目前为止实际读取到的字节数，永不超过 {@code maxBytes + 1}
     */
    public long bytesRead() {
        return this.bytesRead;
    }

    /**
     * @return 是否已经进入永久超限状态
     */
    public boolean exceeded() {
        return this.exceeded;
    }

    /**
     * 本次批量读取/跳过最多允许消费的字节数：{@code remaining + 1}。
     *
     * <p>{@code remaining + 1} 在 {@code maxBytes == Long.MAX_VALUE} 时会溢出，
     * 因此显式做了边界处理；{@code remaining} 为负（已超限）时返回 0。</p>
     */
    private long readAllowance() {
        long remaining = this.maxBytes - this.bytesRead;
        if (remaining < 0L) {
            return 0L;
        }
        if (remaining == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return remaining + 1L;
    }

    private void record(long delta) {
        this.bytesRead += delta;
        if (this.bytesRead > this.maxBytes) {
            // 先置终态再回调：回调抛异常时终态必须已经生效
            this.exceeded = true;
            this.onExceeded.onLimitExceeded(this.maxBytes);
        }
    }

    private void failBecauseExceeded() {
        this.onExceeded.onLimitExceeded(this.maxBytes);
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
