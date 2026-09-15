package com.flowdesk.bootstrap.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 关闭依赖库正文日志（FD-0010-R1）。
 *
 * <p>{@code DashScopeEmbeddingModel} 在「调用抛异常」的分支上执行
 * {@code logger.error("Error embedding request: {}", request.getInstructions(), ex)} ——
 * 也就是把<b>切片正文</b>写进日志，而且发生在我们的适配器能捕获异常之前。
 * 因此「FlowDesk 自己有节制的日志」并不足以保证不泄露。</p>
 *
 * <p>本测试用<b>真实 Spring 上下文 + 真实 {@link DashScopeEmbeddingModel}</b>，
 * base-url 指向本机的一个未监听端口（{@code http://127.0.0.1:1}）：
 * 失败是「连接被拒绝」，既不触达任何真实上游，也不需要网络。</p>
 *
 * <p>两个方向都要验证，避免出现「什么都没记录所以永远通过」的假测试：</p>
 * <ol>
 *   <li>profile 配置下该 logger 已关闭，带 sentinel 的正文<b>不进入</b>日志；</li>
 *   <li>把该 logger 临时打开到 ERROR，同一次调用<b>确实会</b>把正文写进日志 ——
 *       证明这条泄露路径真实存在，而我们的配置正是挡住它的那道闸门。</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "spring.profiles.active=dashscope-embedding",
        "DASHSCOPE_API_KEY=test-fake-key-not-a-real-secret",
        "spring.ai.dashscope.base-url=http://127.0.0.1:1",
        "spring.datasource.url=jdbc:postgresql://localhost:5432/flowdesk",
        "spring.datasource.username=flowdesk",
        "spring.datasource.password=flowdesk",
        "spring.flyway.enabled=false",
        "spring.datasource.hikari.initialization-fail-timeout=-1",
        "flowdesk.knowledge.storage.root=target/knowledge-log-redaction-it"
})
class KnowledgeEmbeddingLogRedactionTests {

    private static final String DASHSCOPE_LOGGER_NAME =
            "com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel";

    private static final String SENTINEL = "SENTINEL-CHUNK-BODY-切片正文-绝不能进日志";

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private org.springframework.core.env.Environment environment;

    private Logger dashscopeLogger;

    private final ListAppender<ILoggingEvent> rootAppender = new ListAppender<>();

    @BeforeEach
    void attachAppender() {
        this.dashscopeLogger = (Logger) LoggerFactory.getLogger(DASHSCOPE_LOGGER_NAME);
        this.rootAppender.start();
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(this.rootAppender);
    }

    @AfterEach
    void detachAppender() {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(this.rootAppender);
        this.rootAppender.stop();
    }

    @Test
    void theShippedProfileTurnsTheLibraryLoggerOff() {
        assertThat(this.environment.getProperty("logging.level." + DASHSCOPE_LOGGER_NAME))
                .as("profile 必须显式关闭该 logger")
                .isEqualTo("OFF");
        assertThat(this.dashscopeLogger.getEffectiveLevel()).isEqualTo(Level.OFF);
        assertThat(this.dashscopeLogger.isErrorEnabled()).as("error 日志必须不可用").isFalse();
        assertThat(this.dashscopeLogger.isWarnEnabled())
                .as("warn 分支同样会打印正文，因此也必须关掉")
                .isFalse();
    }

    @Test
    void aFailingCallDoesNotWriteTheChunkBodyToTheLog() {
        assertThat(throwableOfFailingCall()).as("调用必须失败（本机端口无人监听）").isNotNull();

        assertThat(eventsContaining(SENTINEL))
                .as("带 sentinel 的切片正文不得出现在任何日志事件里")
                .isEmpty();
        assertThat(this.rootAppender.list)
                .as("该 logger 在 profile 下不得产生任何事件")
                .noneMatch(event -> DASHSCOPE_LOGGER_NAME.equals(event.getLoggerName()));
    }

    @Test
    void theSameCallDoesWriteTheChunkBodyWhenTheLoggerIsEnabled() {
        // 反证：把依赖库的 logger 临时打开，正文确实会被写进日志
        Level original = this.dashscopeLogger.getLevel();
        this.dashscopeLogger.setLevel(Level.ERROR);
        try {
            assertThat(throwableOfFailingCall()).isNotNull();

            assertThat(eventsContaining(SENTINEL))
                    .as("依赖库确实会打印正文 —— 这正是必须关掉它的原因")
                    .isNotEmpty();
        }
        finally {
            this.dashscopeLogger.setLevel(original);
        }
    }

    // ---------- 辅助 ----------

    /**
     * 用 sentinel 正文调用真实的 DashScope EmbeddingModel（base-url 指向未监听端口）。
     *
     * @return 调用抛出的异常；未抛出则为 {@code null}
     */
    private Throwable throwableOfFailingCall() {
        var options = com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions.builder()
                .model("text-embedding-v4")
                .dimensions(1024)
                .build();
        return org.assertj.core.api.Assertions.catchThrowable(
                () -> this.embeddingModel.call(new EmbeddingRequest(List.of(SENTINEL), options)));
    }

    private List<String> eventsContaining(String needle) {
        return this.rootAppender.list.stream()
                .filter(event -> event.getFormattedMessage() != null
                        && event.getFormattedMessage().contains(needle))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
