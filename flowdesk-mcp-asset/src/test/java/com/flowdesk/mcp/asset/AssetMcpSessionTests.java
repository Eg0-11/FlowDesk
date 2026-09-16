package com.flowdesk.mcp.asset;

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
 * MCP 会话生命周期的测试（FD-0014-R1）。
 *
 * <p>背景：FD-0014 把 {@code spring.ai.mcp.server.streamable-http.disallow-delete} 设成了
 * {@code true}。那是把「协议层的会话清理」和「资产写操作」混为一谈 ——
 * {@code DELETE /mcp} 结束的是调用方自己的会话，它<b>不是</b>对资产数据的写操作。
 * 代价是真实存在的：被拒绝的 DELETE 会让会话（以及它打开的流）一直留在服务端，
 * 进程关闭时 Tomcat 优雅关停会一直等这个活跃请求，测试 JVM 因此无法在 30 秒内退出，
 * 最终被 Surefire 强杀（{@code Surefire is going to kill self fork JVM}）。</p>
 *
 * <p>本类用两条互补的证据钉住修复：</p>
 * <ol>
 *   <li><b>行为证据</b>：真实 HTTP —— 会话可用 → {@code DELETE} 返回 200（不是 405）→ 同一会话再也不能用（404）；</li>
 *   <li><b>装配证据</b>：真实 SDK 客户端 {@code close()} 之后，服务端会话表里不再有它。</li>
 * </ol>
 *
 * <p>另外记一条排查时实测到的边界，以免后来者踩坑：Streamable HTTP 里 JSON-RPC 请求的应答由
 * {@code text/event-stream} 承载，<b>已实现</b>的方法在 200 ms 内就把流结束掉（实测 EOF 立即到达，
 * 包括工具自己返回 {@code isError=true} 的情况）；而调用本服务<b>未实现</b>的方法
 * （{@code resources/list}、{@code prompts/list}）时，SDK 0.17.0 只把 {@code -32601 Method not found}
 * 写进流里却不结束它（实测 3 秒后仍无 EOF，会话被 DELETE 之后依然如此），服务端因此一直挂着
 * 一个活跃请求。所以本测试套件<b>不</b>通过 HTTP 调用未实现的方法，
 * 「没有注册这些处理器」改由 {@code AssetMcpCapabilitiesTests} 在容器层面断言。
 * 该边界已写入 ADR 0011 与 README 第二十一章。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "flowdesk.asset.directory.mode=demo"
})
class AssetMcpSessionTests {

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
        AssetMcpRawClient raw = new AssetMcpRawClient(this.port);

        AssetMcpRawClient.RawResponse initialized = raw.initialize();
        assertThat(initialized.status()).as("initialize 必须成功").isEqualTo(200);
        String sessionId = initialized.sessionId();
        assertThat(sessionId).as("initialize 必须下发 Mcp-Session-Id").isNotBlank();

        // 会话是活的：握手通知被接受（202 Accepted）
        assertThat(raw.post(AssetMcpRawClient.INITIALIZED_NOTIFICATION, sessionId).status())
                .as("会话存在时，通知必须被接受")
                .isEqualTo(202);

        // 结束会话：不是 405 —— 405 正是 disallow-delete=true 时的行为
        AssetMcpRawClient.RawResponse deleted = raw.delete(sessionId);
        assertThat(deleted.status())
                .as("DELETE /mcp 是协议层的会话清理，必须被支持；405 说明 disallow-delete 又被打回了 true")
                .isEqualTo(200);

        // 会话真的没了：同一个标识再用就是 404
        AssetMcpRawClient.RawResponse afterDelete =
                raw.post(AssetMcpRawClient.INITIALIZED_NOTIFICATION, sessionId);
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
        AssetMcpRawClient.RawResponse response = new AssetMcpRawClient(this.port).delete("");

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
     * <p>这是 FD-0014 里「测试 JVM 退不出去」的直接原因：会话留着，它打开的流也留着，
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

        try (McpSyncClient client = AssetMcpTestClient.connect(this.port)) {
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
