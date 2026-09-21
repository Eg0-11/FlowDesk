package com.flowdesk.infrastructure.mcp.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowdesk.application.integration.QueryFailure;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpTransportException;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/**
 * 失败分类与中断传播（FD-0016 / FD-0016-R1）。
 *
 * <p>中断必须在<b>整条 cause 链</b>上被识别：外层是 {@link IOException} 或
 * {@link McpTransportException} 时也不能提前结束遍历。每个用例都在触发中断后
 * <b>同一个线程</b>内断言中断标志，并在 {@code finally} 里清掉，避免污染其它测试。</p>
 */
class McpFailureMapperTests {

    @Test
    void aDirectInterruptIsUnavailableAndRestoresTheFlag() {
        try {
            assertThat(McpFailureMapper.classify(new InterruptedException("stop"))).isEqualTo(QueryFailure.UNAVAILABLE);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        }
        finally {
            Thread.interrupted();
        }
    }

    @Test
    void aRuntimeWrappedInterruptIsAvailableAndRestoresTheFlag() {
        try {
            Throwable thrown = new IllegalStateException("outer", new InterruptedException("stop"));

            assertThat(McpFailureMapper.classify(thrown)).isEqualTo(QueryFailure.UNAVAILABLE);
            assertThat(Thread.currentThread().isInterrupted()).as("运行期包装不能吞掉中断").isTrue();
        }
        finally {
            Thread.interrupted();
        }
    }

    @Test
    void anIoWrappedInterruptIsStillDetectedInsteadOfStoppingAtTheIOException() {
        try {
            Throwable thrown = new IOException("transport broke", new InterruptedException("stop"));

            assertThat(McpFailureMapper.classify(thrown)).isEqualTo(QueryFailure.UNAVAILABLE);
            assertThat(Thread.currentThread().isInterrupted())
                    .as("IOException 分类不能提前结束扫描而漏掉里层中断")
                    .isTrue();
        }
        finally {
            Thread.interrupted();
        }
    }

    @Test
    void aTransportWrappedInterruptIsStillDetected() {
        try {
            Throwable thrown = new McpTransportException("stream closed", new InterruptedException("stop"));

            assertThat(McpFailureMapper.classify(thrown)).isEqualTo(QueryFailure.UNAVAILABLE);
            assertThat(Thread.currentThread().isInterrupted())
                    .as("传输异常分类不能提前结束扫描而漏掉里层中断")
                    .isTrue();
        }
        finally {
            Thread.interrupted();
        }
    }

    @Test
    void anInterruptDeepInTheChainIsStillDetected() {
        try {
            Throwable thrown = new McpTransportException("l4",
                    new IOException("l3", new IllegalStateException("l2",
                            new RuntimeException("l1", new InterruptedException("stop")))));

            assertThat(McpFailureMapper.classify(thrown)).isEqualTo(QueryFailure.UNAVAILABLE);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        }
        finally {
            Thread.interrupted();
        }
    }

    @Test
    void aSelfReferencingChainTerminatesAndIsClassifiedAsUnavailable() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second");
        first.initCause(second);
        second.initCause(first);

        assertThat(McpFailureMapper.classify(first)).isEqualTo(QueryFailure.UNAVAILABLE);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void theExistingContractsAreUnchanged() {
        assertThat(McpFailureMapper.classify(new IOException("boom"))).isEqualTo(QueryFailure.UNAVAILABLE);
        assertThat(McpFailureMapper.classify(new McpTransportException("boom"))).isEqualTo(QueryFailure.UNAVAILABLE);
        assertThat(McpFailureMapper.classify(new RuntimeException("boom"))).isEqualTo(QueryFailure.UNAVAILABLE);
        assertThat(McpFailureMapper.classify(null)).isEqualTo(QueryFailure.UNAVAILABLE);

        assertThat(McpFailureMapper.classify(McpError.builder(-32601).message("no").build()))
                .isEqualTo(QueryFailure.REMOTE_TOOL_ERROR);
        assertThat(McpFailureMapper.classify(new McpError("plain")))
                .as("没有 JSON-RPC 错误对象的 McpError 仍是远端拒绝")
                .isEqualTo(QueryFailure.REMOTE_TOOL_ERROR);

        assertThat(McpFailureMapper.classify(new TimeoutException("late"))).isEqualTo(QueryFailure.TIMEOUT);
        assertThat(McpFailureMapper.classify(new HttpTimeoutException("late"))).isEqualTo(QueryFailure.TIMEOUT);
        assertThat(McpFailureMapper.classify(new McpTransportException("late", new TimeoutException("t"))))
                .as("外层是传输异常时超时仍然优先")
                .isEqualTo(QueryFailure.TIMEOUT);
        assertThat(Thread.currentThread().isInterrupted()).as("非中断路径不得设置中断标志").isFalse();
    }

    @Test
    void restoreInterruptIfPresentReportsWithoutClassifying() {
        assertThat(McpFailureMapper.restoreInterruptIfPresent(new IOException("boom"))).isFalse();
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        try {
            assertThat(McpFailureMapper.restoreInterruptIfPresent(
                    new IOException("boom", new InterruptedException("stop")))).isTrue();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        }
        finally {
            Thread.interrupted();
        }
    }
}
