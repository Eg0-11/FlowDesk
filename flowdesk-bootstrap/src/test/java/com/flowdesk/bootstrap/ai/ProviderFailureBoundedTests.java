package com.flowdesk.bootstrap.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowdesk.application.ai.AiChatUseCase;
import com.flowdesk.application.ai.AiProviderException;
import com.flowdesk.application.ai.ChatCommand;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 上游不可用时的失败时效性测试。
 *
 * <p>Spring AI 的默认重试策略是 {@code max-attempts=10}、{@code multiplier=5}、初始退避 2000ms，
 * 累计退避可达约 4 分钟。这会让「上游故障 → 502」在客户端看来根本不是 502，而是超时，
 * 与接口契约相悖。deepseek profile 因此收窄为有界重试，本测试把它锁死。</p>
 *
 * <p>本测试把 base-url 指向一个确定没有监听者的本地端口，因此不会产生任何真实外部调用。</p>
 */
@SpringBootTest(properties = {
        "spring.profiles.active=deepseek",
        "DEEPSEEK_API_KEY=test-fake-key-not-a-real-secret"
})
class ProviderFailureBoundedTests {

    private static final Duration ACCEPTABLE_BUDGET = Duration.ofSeconds(15);

    @Autowired
    private AiChatUseCase aiChatUseCase;

    @DynamicPropertySource
    static void pointAtClosedPort(DynamicPropertyRegistry registry) throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        registry.add("spring.ai.openai.base-url", () -> "http://127.0.0.1:" + closedPort);
    }

    @Test
    void surfacesProviderFailureAsAiProviderExceptionWithinBudget() {
        long startedAt = System.nanoTime();

        assertThatThrownBy(() -> aiChatUseCase.chat(new ChatCommand("请用一句话介绍 FlowDesk")))
                .isInstanceOf(AiProviderException.class);

        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
        assertThat(elapsed)
                .as("上游不可用时必须在预算内失败，否则客户端只会等到超时而不是收到 502")
                .isLessThan(ACCEPTABLE_BUDGET);
    }
}
