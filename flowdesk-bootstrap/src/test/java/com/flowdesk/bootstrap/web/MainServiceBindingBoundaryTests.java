package com.flowdesk.bootstrap.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.flowdesk.bootstrap.FlowDeskApplication;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * 主服务监听边界（FD-0021）：只允许本机回环，且拒绝必须发生在<b>创建 Web 服务器之前</b>。
 *
 * <p>{@code application.yml} 里写 {@code server.address: 127.0.0.1} 只是<b>默认值</b>：
 * 任何人都可以用命令行或环境变量把它覆盖成 {@code 0.0.0.0}，因此边界必须由启动期闸门保证
 * —— 否则主服务（当前<b>没有鉴权</b>）会在毫不知情的情况下暴露在所有网卡上。</p>
 *
 * <p>这些用例启动的是<b>真实主服务上下文与真实 Tomcat</b>（{@code --server.port=0} 随机端口）：
 * 合法配置断言「真的在回环上处于监听」，非法配置断言「启动失败且没有任何监听」。</p>
 */
class MainServiceBindingBoundaryTests {

    @Test
    void theShippedDefaultBindsToLoopbackOnly() throws IOException {
        try (ConfigurableApplicationContext context = start()) {
            assertThat(context.getEnvironment().getProperty("server.address"))
                    .as("交付默认值")
                    .isEqualTo("127.0.0.1");

            int port = listeningPort(context);
            assertThat(port).as("随机端口应当已经分配").isPositive();
            assertThat(canConnect(InetAddress.getLoopbackAddress(), port))
                    .as("回环上必须真的在监听")
                    .isTrue();
        }
    }

    @Test
    void theLoopbackPortIsNotReachableThroughANonLoopbackLocalAddress() throws IOException {
        InetAddress external = findNonLoopbackIpv4();
        assumeTrue(external != null, "本机没有非回环 IPv4 地址，无法验证「只有回环可达」，跳过");

        try (ConfigurableApplicationContext context = start()) {
            int port = listeningPort(context);
            assertThat(canConnect(external, port))
                    .as("绑定在回环上的服务不应能通过本机的非回环地址访问（address=%s）", external.getHostAddress())
                    .isFalse();
        }
    }

    @Test
    void anExplicitLoopbackAddressStillStarts() {
        try (ConfigurableApplicationContext context = startWith("127.0.0.1")) {
            assertThat(listeningPort(context)).isPositive();
        }
    }

    @Test
    void anIpv6LoopbackAddressIsAcceptedAndStartsWhenThePlatformSupportsIt() throws IOException {
        assumeTrue(ipv6LoopbackUsable(), "本机不支持 IPv6 回环 ::1，无法验证该写法，跳过");

        try (ConfigurableApplicationContext context = startWith("::1")) {
            assertThat(context.getEnvironment().getProperty("server.address")).isEqualTo("::1");
            assertThat(listeningPort(context)).isPositive();
        }
    }

    @Test
    void bindingToAllInterfacesFailsBeforeAnyWebServerExistsAndNothingListens() throws IOException {
        int port = freePort();

        Throwable thrown = catchThrowable(() -> startOn(port, "0.0.0.0"));

        assertThat(thrown)
                .as("在环境准备阶段就被拒绝：固定且不回显配置原值的文案")
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(MainServiceBindingGuard.MESSAGE);
        assertThat(thrown.getClass().getName())
                .as("不是 ApplicationContextException/WebServerException：上下文与 Web 服务器都没被创建")
                .doesNotContain("ApplicationContextException");

        assertThat(portIsFree(port) && !canConnect(InetAddress.getLoopbackAddress(), port))
                .as("拒绝之后没有任何进程在这个端口上监听")
                .isTrue();
    }

    @Test
    void bindingToTheUnspecifiedIpv6AddressFails() {
        assertThatThrownBy(() -> startWith("::"))
                .as(":: 等于监听所有 IPv6 接口")
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(MainServiceBindingGuard.MESSAGE);
    }

    @Test
    void bindingToLanPublicOrHostNameAddressesFails() throws IOException {
        int port = freePort();
        for (String address : new String[] { "192.168.1.10", "10.0.0.5", "203.0.113.7",
                "2001:db8::1", "example.com", "my-host.local" }) {

            assertThatThrownBy(() -> startOn(port, address))
                    .as("address=%s", address)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("只允许监听本机回环地址")
                    .hasMessageNotContaining(address);
        }
        // localhost 同样被拒绝（绑定需要解析）；错误文案里把它作为「不接受的写法」举例，
        // 因此这里只断言「被拒绝」
        assertThatThrownBy(() -> startOn(port, "localhost")).isInstanceOf(IllegalStateException.class);
        assertThat(portIsFree(port)).as("所有非法取值都没有留下监听").isTrue();
    }

    @Test
    void aBlankAddressFails() {
        assertThatThrownBy(() -> startWith("  "))
                .as("空白地址等于「不限制」，必须拒绝")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aHighPrecedencePropertySourceCannotBypassTheGuard() {
        StandardEnvironment environment = isolatedEnvironment();
        environment.getPropertySources()
                .addFirst(new MapPropertySource("simulatedEnvironmentVariable",
                        Map.of("server.address", "0.0.0.0")));

        assertThatThrownBy(() -> new SpringApplicationBuilder(FlowDeskApplication.class)
                .environment(environment)
                .run("--server.port=0"))
                .as("环境变量/更高优先级的属性源同样绕不过闸门")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("只允许监听本机回环地址");
    }

    @Test
    void thePolicyAcceptsOnlyLoopbackLiterals() {
        for (String accepted : new String[] { "127.0.0.1", "127.0.0.5", "127.255.255.254",
                "::1", "0:0:0:0:0:0:0:1", "[::1]" }) {

            assertThat(LoopbackAddressPolicy.isLoopbackLiteral(accepted)).as("accept %s", accepted).isTrue();
        }
        for (String rejected : new String[] { null, "", "  ", "0.0.0.0", "::", "192.168.1.10",
                "10.0.0.5", "203.0.113.7", "2001:db8::1", "localhost", "example.com",
                "0127.0.0.1", "127.5", "127.0.0.1.5", "127.0.0.1.example.com" }) {

            assertThat(LoopbackAddressPolicy.isLoopbackLiteral(rejected)).as("reject %s", rejected).isFalse();
        }
    }

    /**
     * @return 交付默认配置下的上下文（只把端口设为随机，地址不动）
     */
    private static ConfigurableApplicationContext start() {
        return builder().run("--server.port=0");
    }

    private static ConfigurableApplicationContext startWith(String address) {
        return builder().run("--server.address=" + address, "--server.port=0");
    }

    private static ConfigurableApplicationContext startOn(int port, String address) {
        return builder().run("--server.address=" + address, "--server.port=" + port);
    }

    /**
     * @return 默认 profile 的上下文构建器（不继承构建机器的环境变量，避免宿主机注入的
     *         {@code SERVER__PORT} 之类变量干扰本测试自身的判定）
     */
    private static SpringApplicationBuilder builder() {
        return new SpringApplicationBuilder(FlowDeskApplication.class).environment(isolatedEnvironment());
    }

    private static StandardEnvironment isolatedEnvironment() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        return environment;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    /**
     * @param context 已启动的 Web 上下文
     * @return 真实 Web 服务器正在监听的端口
     */
    private static int listeningPort(ConfigurableApplicationContext context) {
        return ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    private static boolean portIsFree(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("0.0.0.0", port));
            return true;
        }
        catch (IOException ex) {
            return false;
        }
    }

    private static boolean canConnect(InetAddress address, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), 1500);
            return true;
        }
        catch (IOException ex) {
            return false;
        }
    }

    private static boolean ipv6LoopbackUsable() {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(InetAddress.getByName("::1"), 0));
            return true;
        }
        catch (IOException ex) {
            return false;
        }
    }

    private static InetAddress findNonLoopbackIpv4() throws IOException {
        for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            for (InetAddress address : Collections.list(network.getInetAddresses())) {
                if (!address.isLoopbackAddress() && address instanceof java.net.Inet4Address
                        && !address.isAnyLocalAddress() && !address.isLinkLocalAddress()) {

                    return address;
                }
            }
        }
        return null;
    }
}
