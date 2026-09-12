package com.flowdesk.agent.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 请求级工具调用记录器的隔离性与线程安全性测试。
 */
class ToolInvocationRecorderTest {

    @Test
    void keepsConcurrentRequestsIsolated() {
        ToolInvocationRecorder first = new ToolInvocationRecorder();
        ToolInvocationRecorder second = new ToolInvocationRecorder();

        first.record("lookup_support_policy", true);

        assertThat(first.invocations()).containsExactly(new ToolInvocation("lookup_support_policy", true));
        assertThat(second.invocations()).isEmpty();
        assertThat(second.anySucceeded()).isFalse();
        assertThat(second.totalRecorded()).isZero();
    }

    @Test
    void doesNotLeakStateAcrossSequentialRequests() {
        ToolInvocationRecorder firstRequest = new ToolInvocationRecorder();
        firstRequest.record("lookup_support_policy", true);

        // 新请求使用新实例，等价于上一个请求的生命周期已结束
        ToolInvocationRecorder secondRequest = new ToolInvocationRecorder();

        assertThat(secondRequest.invocations()).isEmpty();
        assertThat(firstRequest.invocations()).hasSize(1);
    }

    @Test
    void recordsConcurrentlyWithoutLosingEntries() throws Exception {
        ToolInvocationRecorder recorder = new ToolInvocationRecorder();
        int threadCount = 8;
        int perThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int thread = 0; thread < threadCount; thread++) {
                boolean success = thread % 2 == 0;
                futures.add(pool.submit(() -> {
                    startGate.await();
                    for (int i = 0; i < perThread; i++) {
                        recorder.record(SupportPolicyTools.TOOL_NAME, success);
                    }
                    return null;
                }));
            }
            startGate.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(recorder.totalRecorded()).isEqualTo(threadCount * perThread);
        assertThat(recorder.invocations()).hasSize(threadCount * perThread);
        assertThat(recorder.anySucceeded()).isTrue();
    }

    @Test
    void returnsImmutableSnapshot() {
        ToolInvocationRecorder recorder = new ToolInvocationRecorder();
        recorder.record("lookup_support_policy", true);

        List<ToolInvocation> snapshot = recorder.invocations();
        recorder.record("lookup_support_policy", false);

        assertThat(snapshot).hasSize(1);
        assertThat(recorder.invocations()).hasSize(2);
    }
}
