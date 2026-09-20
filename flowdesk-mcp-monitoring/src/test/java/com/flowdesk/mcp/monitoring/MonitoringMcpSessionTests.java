package com.flowdesk.mcp.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * MCP 会话生命周期的测试（FD-0015）。
 *
 * <p>背景：本模块把 {@code spring.ai.mcp.server.streamable-http.disallow-delete} 显式设为
 * {@code false}。把它设成 {@code true} 等于把「协议层的会话清理」和「监控写操作」混为一谈 ——
 * {@code DELETE /mcp} 结束的是调用方自己的会话，它<b>不是</b>对监控数据的写操作。
 * 代价是真实存在的（FD-0014 阶段在资产 MCP 上实测过）：被拒绝的 DELETE 会让会话（以及它打开的流）一直留在服务端，
 * 进程关闭时 Tomcat 优雅关停会一直等这个活跃请求，测试 JVM 因此无法在 30 秒内退出，
 * 最终被 Surefire 强杀（{@code Surefire is going to kill self fork JVM}）。</p>
 *
 * <p>本类用两条互补的证据钉住修复：</p>
 * <ol>
 *   <li><b>行为证据</b>：真实 HTTP —— 会话可用 → {@code DELETE} 返回 200（不是 405）→ 同一会话再也不能用（404）；</li>
 *   <li><b>装配证据</b>：真实 SDK 客户端 {@code close()} 之后，服务端会话表里不再有它。</li>
 * </ol>
 *
 * <p>另外记一条实测到的 SDK 边界，以免后来者踩坑：Streamable HTTP 里 JSON-RPC 请求的应答由
 * {@code text/event-stream} 承载，<b>已实现</b>的方法在 200 ms 内就把流结束掉（实测 EOF 立即到达，
 * 包括工具自己返回 {@code isError=true} 的情况）；而<b>未实现</b>的方法如果直接落到 SDK 0.17.0，
 * 它只把 {@code -32601 Method not found} 写进流里却不结束它（FD-0014-R2 阶段实测 3 秒后仍无 EOF），
 * 服务端因此会一直挂着一个活跃请求。本模块的入口闸门正是为此在请求进入 SDK 之前就把未实现的方法
 * 收敛成有界的普通 JSON（覆盖见 {@code MonitoringMcpTransportErrorTests}）；
 * 「没有注册这些处理器」则由 {@code MonitoringMcpCapabilitiesTests} 在容器层面断言。
 * 该边界与闸门规则见 ADR 0012 与 README 第二十二章（SDK 侧的最小复现见 ADR 0011 的 FD-0014-R2 章节）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "flowdesk.monitoring.source.mode=demo"
})
class MonitoringMcpSessionTests {

    @LocalServerPort
    private int port;

    @Autowired
    private WebMvcStreamableServerTransportProvider transportProvider;

    /**
     * 会话真的能被结束：{@code DELETE} 是 200，且结束之后同一个会话标识不再可用。
     *
     * @throws Exception 请求失败
     */
    @Test
    void aClientCanTerminateItsOwnSessionWithDelete() throws Exception {
        MonitoringMcpRawClient raw = new MonitoringMcpRawClient(this.port);

        MonitoringMcpRawClient.RawResponse initialized = raw.initialize();
        assertThat(initialized.status()).as("initialize 必须成功").isEqualTo(200);
        String sessionId = initialized.sessionId();
        assertThat(sessionId).as("initialize 必须下发 Mcp-Session-Id").isNotBlank();

        // 会话是活的：握手通知被接受（202 Accepted）
        assertThat(raw.post(MonitoringMcpRawClient.INITIALIZED_NOTIFICATION, sessionId).status())
                .as("会话存在时，通知必须被接受")
                .isEqualTo(202);

        // 结束会话：不是 405 —— 405 正是 disallow-delete=true 时的行为
        MonitoringMcpRawClient.RawResponse deleted = raw.delete(sessionId);
        assertThat(deleted.status())
                .as("DELETE /mcp 是协议层的会话清理，必须被支持；405 说明 disallow-delete 又被打回了 true")
                .isEqualTo(200);

        // 会话真的没了：同一个标识再用就是 404
        MonitoringMcpRawClient.RawResponse afterDelete =
                raw.post(MonitoringMcpRawClient.INITIALIZED_NOTIFICATION, sessionId);
        assertThat(afterDelete.status())
                .as("会话被结束之后，旧标识必须查不到")
                .isEqualTo(404);
    }

    /**
     * 不带会话标识的 {@code DELETE} 不会把服务端弄坏：仍然是固定的错误响应，不回显任何内容。
     *
     * @throws Exception 请求失败
     */
    @Test
    void deletingWithoutASessionIdIsRejectedWithoutEchoingAnything() throws Exception {
        MonitoringMcpRawClient.RawResponse response = new MonitoringMcpRawClient(this.port).delete("");

        assertThat(response.status()).as("无效会话标识不能被当成成功").isNotEqualTo(200);
        assertThat(response.body())
                .as("响应里只有固定文案")
                .doesNotContain("sessions")
                .doesNotContain("ConcurrentHashMap")
                .doesNotContain("at io.modelcontextprotocol");
    }

    /**
     * 真实 SDK 客户端 {@code close()} 之后，服务端不再保留会话。
     *
     * <p>这是 FD-0015 里「测试 JVM 退不出去」的直接原因：会话留着，它打开的流也留着，
     * 上下文关闭时 Tomcat 优雅关停就在等这个活跃请求。</p>
     *
     * <p>SDK 的 {@code close()} 是<b>异步</b>的：它发出 {@code DELETE /mcp} 之后不等响应
     * （实测：close 返回瞬间会话还在，250 ms 内消失）。因此这里等的是「收敛」本身，
     * 用的是有界轮询（最多 5 秒、每 25 ms 一次）而不是「把超时调大」：
     * 会话如果真的留下来，本测试会在 5 秒后失败，而不是被放过。</p>
     */
    @Test
    void closingARealSdkClientLeavesNoLiveServerSideSession() throws Exception {
        int before = liveSessions().size();

        try (McpSyncClient client = MonitoringMcpTestClient.connect(this.port)) {
            client.initialize();

            assertThat(liveSessions().size())
                    .as("会话建立后，服务端必须真的持有它（否则本测试没有意义）")
                    .isEqualTo(before + 1);
        }

        awaitSessionCount(before);

        assertThat(liveSessions().size())
                .as("客户端 close() 必须真的结束服务端会话，不能留下悬挂的会话与流")
                .isEqualTo(before);
    }

    /**
     * 等待服务端会话表收敛到期望条数（有界轮询，超时即失败）。
     *
     * @param expected 期望的会话条数
     * @throws InterruptedException 等待被打断
     */
    private void awaitSessionCount(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();

        while (System.nanoTime() < deadline) {
            if (liveSessions().size() == expected) {
                return;
            }
            Thread.sleep(25);
        }
    }

    /**
     * 读取服务端会话表（SDK 0.17 的 Streamable HTTP 传输层把它放在私有字段里，
     * 因此这里用 {@code ReflectionTestUtils}；字段改名会让本测试直接失败，而不是悄悄跳过）。
     *
     * @return 服务端当前的会话表
     */
    private Map<?, ?> liveSessions() {
        Object sessions = ReflectionTestUtils.getField(this.transportProvider, "sessions");

        assertThat(sessions)
                .as("找不到会话表说明 SDK 的内部结构变了，本测试必须显式失败")
                .isInstanceOf(Map.class);
        return (Map<?, ?>) sessions;
    }
}
