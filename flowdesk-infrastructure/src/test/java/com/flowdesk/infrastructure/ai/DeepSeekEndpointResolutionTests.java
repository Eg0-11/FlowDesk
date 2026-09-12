package com.flowdesk.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.DefaultUriBuilderFactory;

/**
 * 端点解析等价性测试。
 *
 * <p>拦截器必须与 Spring 请求的真实 URI 完全对齐，因此这里用 {@link MockRestServiceServer}
 * 捕获 {@code RestClient} 实际请求的 URI，并与
 * {@code new DefaultUriBuilderFactory(baseUrl).expand(completionsPath)} 的结果逐一比对。
 * 该组合正是 {@code OpenAiApi} 内部的构造方式
 * （{@code restClientBuilder.clone().baseUrl(baseUrl)} 后 {@code .post().uri(completionsPath)}）。</p>
 */
class DeepSeekEndpointResolutionTests {

    @ParameterizedTest(name = "base=[{0}] path=[{1}]")
    @CsvSource({
            "https://api.deepseek.com, /chat/completions",
            "https://gw.example.com/openai/v1, /chat/completions",
            "https://api.deepseek.com, chat/completions",
            "https://api.deepseek.com/, /chat/completions",
            "https://api.deepseek.com/v1/, /chat/completions",
            "https://gw.example.com/openai/v1, chat/completions",
            "https://gw.example.com/openai/v1/, chat/completions",
            "https://gw.example.com/openai/v1, v2/chat/completions",
    })
    void uriBuilderFactoryResolvesTheSameUriTheRestClientRequests(String baseUrl, String completionsPath) {
        URI viaFactory = new DefaultUriBuilderFactory(baseUrl).expand(completionsPath);

        assertThat(viaFactory).isEqualTo(requestUri(baseUrl, completionsPath));
    }

    @Test
    void doesNotInsertASeparatorTheRestClientDoesNotInsert() {
        // base 自带路径、completions-path 缺前导斜杠时，Spring 是直接拼接的
        URI resolved = new DefaultUriBuilderFactory("https://gw.example.com/openai/v1").expand("chat/completions");

        assertThat(resolved).hasToString("https://gw.example.com/openai/v1chat/completions");
        assertThat(resolved).isNotEqualTo(URI.create("https://gw.example.com/openai/v1/chat/completions"));
    }

    @Test
    void keepsTheLeadingSlashCaseUnchanged() {
        URI resolved = new DefaultUriBuilderFactory("https://gw.example.com/openai/v1").expand("/chat/completions");

        assertThat(resolved).hasToString("https://gw.example.com/openai/v1/chat/completions");
    }

    /**
     * 用 MockRestServiceServer 捕获 RestClient 实际请求的 URI，全程不产生真实网络调用。
     */
    private static URI requestUri(String baseUrl, String completionsPath) {
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AtomicReference<URI> captured = new AtomicReference<>();
        server.expect(request -> captured.set(request.getURI()))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.POST))
                .andRespond(response -> {
                    throw new IllegalStateException("仅用于捕获请求 URI");
                });
        try {
            builder.build().post().uri(completionsPath).retrieve().toBodilessEntity();
        } catch (RuntimeException expected) {
            // 响应是刻意的占位实现，这里只关心捕获到的 URI
        }
        return captured.get();
    }
}
