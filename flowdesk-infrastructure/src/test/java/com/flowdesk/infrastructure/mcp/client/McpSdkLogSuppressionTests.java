package com.flowdesk.infrastructure.mcp.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flowdesk.application.integration.QueryFailure;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * SDK 日志旁路的收口（FD-0016-R1）。
 *
 * <p>只看 {@code McpQueryLogger} 是不够的：官方 SDK 自己也会写日志，而且写的是
 * <b>远端原文与内部细节</b> —— {@code LifecycleInitializer} 在 INFO 打印远端的
 * {@code serverInfo} 与 {@code instructions}，失败路径还会打印异常对象（消息 + 堆栈）。</p>
 *
 * <p>本类用两个办法把这件事变成可断言的事实：</p>
 * <ol>
 *   <li><b>哨兵</b>：把唯一文本分别放进 initialize 响应（{@code serverInfo.name} 与
 *       {@code instructions}）和失败响应（JSON-RPC 错误消息），因此「远端原文有没有进日志」
 *       可以直接用字符串断言；</li>
 *   <li><b>正向对照</b>：显式打开诊断档（{@code DEBUG}）时，SDK 确实会把哨兵写进日志 ——
 *       这既证明请求真的发生过、哨兵真的到过客户端，也证明默认 {@code OFF} 关掉的正是这条旁路；
 *       交付默认必须让哨兵不出现，而不是靠测试环境额外关日志。</li>
 * </ol>
 *
 * <p>日志捕获挂在 <b>root</b> logger 上（而不是只挂我们自己的 logger），
 * 因此任何来源（含 SDK）写出的内容都会被看到；测试结束恢复交付默认级别，避免污染其它用例。</p>
 */
class McpSdkLogSuppressionTests {

    private static final String INIT_SENTINEL = "sentinel-remote-instructions-4f1c7a";

    private static final String ERROR_SENTINEL = "sentinel-remote-error-9d2b3e";

    private static final String ASSET_HIT = "{\"assetId\":\"AST-900001\",\"assetType\":\"SERVER\","
            + "\"status\":\"IN_SERVICE\",\"source\":\"DEMO\"}";

    private ControllableMcpEndpoint endpoint;

    private final ListAppender<ILoggingEvent> rootAppender = new ListAppender<>();

    @BeforeEach
    void startEndpoint() throws Exception {
        this.endpoint = ControllableMcpEndpoint.start();
        this.rootAppender.start();
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                .addAppender(this.rootAppender);
    }

    @AfterEach
    void stopEndpoint() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                .detachAppender(this.rootAppender);
        this.rootAppender.stop();
        this.endpoint.close();
        // 恢复交付默认：日志级别是 JVM 级状态，不能留给下一个测试
        McpSdkLogControl.apply(McpSdkLogControl.DEFAULT_LEVEL);
    }

    @Test
    void theShippedDefaultKeepsRemoteTextAndThrowableDetailOutOfTheLogs() {
        this.endpoint.respondWithInitializeMetadata(INIT_SENTINEL, INIT_SENTINEL);
        this.endpoint.respondWithJsonRpcError(-32603, ERROR_SENTINEL);

        McpSdkLogControl.apply(McpSdkLogControl.DEFAULT_LEVEL);
        assertThat(McpSdkLogControl.currentLevel()).isEqualTo("OFF");

        int postsBefore = this.endpoint.postCount();
        McpClientFailureException failure = catchThrowableOfType(
                () -> toolClient().call(endpoint(), "asset_get", "AST-900001"), McpClientFailureException.class);

        assertThat(failure).isNotNull();
        assertThat(failure.failure()).isEqualTo(QueryFailure.REMOTE_TOOL_ERROR);
        assertThat(this.endpoint.postCount()).as("请求真的发生了（不是被跳过）").isGreaterThan(postsBefore);
        assertThat(causeChainContains(failure, ERROR_SENTINEL))
                .as("远端错误原文确实到过客户端内部（否则这条断言没有对象）")
                .isTrue();

        assertThat(loggedText())
                .as("交付默认下日志里不得出现远端原文")
                .doesNotContain(INIT_SENTINEL)
                .doesNotContain(ERROR_SENTINEL);
        assertThat(eventsFromSdkLogger()).as("SDK 自己的日志在默认配置下一条都不输出").isEmpty();
    }

    @Test
    void theDiagnosticLevelDoesLogThemWhichIsWhyTheDefaultIsOff() {
        this.endpoint.respondWithInitializeMetadata(INIT_SENTINEL, INIT_SENTINEL);
        this.endpoint.respondWithToolText(ASSET_HIT);

        McpSdkLogControl.apply("DEBUG");
        assertThat(McpSdkLogControl.currentLevel()).isEqualTo("DEBUG");

        McpToolResponse response = toolClient().call(endpoint(), "asset_get", "AST-900001");

        assertThat(response.remoteError()).isFalse();
        assertThat(response.text()).contains("AST-900001");
        assertThat(loggedText())
                .as("正向对照：打开诊断档后 SDK 确实会把远端 serverInfo/instructions 写进日志")
                .contains(INIT_SENTINEL);
    }

    @Test
    void theProjectsOwnMetadataLogStaysAvailableWhileTheSdkIsSilenced() {
        this.endpoint.respondWithInitializeMetadata(INIT_SENTINEL, INIT_SENTINEL);
        this.endpoint.respondWithToolText(ASSET_HIT);
        McpSdkLogControl.apply(McpSdkLogControl.DEFAULT_LEVEL);

        assertThat(adapter().findAsset("AST-900001").isFound()).isTrue();

        List<ILoggingEvent> own = this.rootAppender.list.stream()
                .filter(event -> McpQueryLogger.class.getName().equals(event.getLoggerName()))
                .toList();
        assertThat(own).as("项目自己的固定元数据日志必须保留").hasSize(1);
        assertThat(own.get(0).getMessage())
                .isEqualTo("mcp query completed alias={} tool={} result={} durationMs={}");
        assertThat(loggedText()).doesNotContain(INIT_SENTINEL);
        assertThat(eventsFromSdkLogger()).isEmpty();
    }

    private McpToolClient toolClient() {
        return new McpToolClient(Duration.ofSeconds(2));
    }

    private McpServerEndpoint endpoint() {
        return McpServerEndpoint.of(this.endpoint.baseUrl());
    }

    private McpAssetQueryAdapter adapter() {
        return new McpAssetQueryAdapter(toolClient(), endpoint());
    }

    private List<ILoggingEvent> eventsFromSdkLogger() {
        return this.rootAppender.list.stream()
                .filter(event -> event.getLoggerName() != null
                        && event.getLoggerName().startsWith(McpSdkLogControl.SDK_LOGGER_NAME))
                .toList();
    }

    private String loggedText() {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : this.rootAppender.list) {
            text.append(event.getFormattedMessage()).append('\n');
            Object[] arguments = event.getArgumentArray();
            if (arguments != null) {
                for (Object argument : arguments) {
                    text.append(argument).append('\n');
                }
            }
            if (event.getThrowableProxy() != null) {
                text.append(event.getThrowableProxy().getClassName()).append(':')
                        .append(event.getThrowableProxy().getMessage()).append('\n');
            }
        }
        return text.toString();
    }

    private static boolean causeChainContains(Throwable thrown, String sentinel) {
        Throwable current = thrown;
        int depth = 0;
        while (current != null && depth++ < 16) {
            if (current.getMessage() != null && current.getMessage().contains(sentinel)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
