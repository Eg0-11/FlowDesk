package com.flowdesk.mcp.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.flowdesk.mcp.asset.config.LoopbackAddressPolicy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;

/**
 * 监听地址的启动期 fail-fast（FD-0014）。
 *
 * <p>资产 MCP 服务只允许在本机回环上监听。{@code application.yml} 里写 {@code 127.0.0.1}
 * 只是<b>默认值</b>；任何人都可以用 {@code --server.address=0.0.0.0} 覆盖它，
 * 因此必须在装配阶段拒绝 —— 否则服务会在毫不知情的情况下监听所有网卡。</p>
 *
 * <p>这些用例不启动 Web 服务器（校验 Bean 在服务器绑定之前创建并失败），
 * 也不需要任何外部依赖。</p>
 */
class AssetMcpBindingFailFastTests {

    @Test
    void bindingToAllInterfacesFailsFast() {
        assertThatThrownBy(() -> startWith("0.0.0.0"))
                .as("0.0.0.0 会让服务暴露在所有网卡上")
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("127.0.0.1");
    }

    @Test
    void theRejectionHappensBeforeAnyWebServerIsCreated() {
        Throwable thrown = catchThrowable(() -> startWith("0.0.0.0"));

        assertThat(thrown)
                .as("在环境准备阶段就被拒绝：不是 Tomcat 报错，也没有绑定过任何端口")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("只允许监听本机回环地址")
                .hasMessageContaining("server.address");
        assertThat(thrown.getClass().getName())
                .as("不是 ApplicationContextException/WebServerException —— 说明上下文与 Web 服务器都没被创建")
                .doesNotContain("ApplicationContextException");
    }

    @Test
    void bindingToAnExternalOrPrivateAddressFailsFast() {
        for (String address : new String[] { "192.168.1.10", "10.0.0.5", "203.0.113.7", "2001:db8::1" }) {
            assertThatThrownBy(() -> startWith(address))
                    .as("address=%s", address)
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("只允许监听本机回环地址")
                    .hasMessageNotContaining(address);
        }
    }

    @Test
    void bindingToTheUnspecifiedIpv6AddressFailsFast() {
        assertThatThrownBy(() -> startWith("::"))
                .as(":: 等于监听所有 IPv6 接口")
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("127.0.0.1");
    }

    @Test
    void bindingToAHostNameFailsFastBecauseNamesNeedResolution() {
        for (String address : new String[] { "example.com", "my-host.local" }) {
            assertThatThrownBy(() -> startWith(address))
                    .as("address=%s：名字可能被 hosts/DNS 指向任何地方", address)
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("只允许监听本机回环地址")
                    .hasMessageNotContaining(address);
        }
        // localhost 同样被拒绝（需要解析），但错误信息里会把它作为「不接受的写法」举例，
        // 因此这里只断言「被拒绝」这一点
        assertThatThrownBy(() -> startWith("localhost")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void aBlankAddressFailsFast() {
        assertThatThrownBy(() -> startWith("  "))
                .as("空白地址等于「不限制」，必须拒绝")
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void theShippedDefaultStillStarts() {
        try (ConfigurableApplicationContext context = builder().run(arguments("127.0.0.1", "0"))) {
            assertThat(context.getEnvironment().getProperty("server.address")).isEqualTo("127.0.0.1");
            assertThat(context.getBean(com.flowdesk.mcp.asset.directory.AssetDirectory.class))
                    .isInstanceOf(com.flowdesk.mcp.asset.directory.UnavailableAssetDirectory.class);
        }
    }

    @Test
    void anIpv6LoopbackLiteralIsAcceptedByThePolicy() {
        assertThat(LoopbackAddressPolicy.isLoopbackLiteral("::1")).isTrue();
        assertThat(LoopbackAddressPolicy.isLoopbackLiteral("0:0:0:0:0:0:0:1")).isTrue();
    }

    /**
     * @param address server.address 的取值
     * @return 启动结果（失败时抛异常）
     */
    private static ConfigurableApplicationContext startWith(String address) {
        return builder().run(arguments(address, "0"));
    }

    /**
     * @return 默认 profile 的嵌套上下文构建器（不继承构建机器的环境变量）
     */
    private static SpringApplicationBuilder builder() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        return new SpringApplicationBuilder(AssetMcpApplication.class).environment(environment);
    }

    /**
     * @param address server.address
     * @param port    server.port（0 = 随机端口）
     * @return 参数数组
     */
    private static String[] arguments(String address, String port) {
        List<String> arguments = new ArrayList<>(List.of(
                "--server.address=" + address,
                "--server.port=" + port));
        return arguments.toArray(String[]::new);
    }
}
