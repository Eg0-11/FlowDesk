package com.flowdesk.mcp.asset.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 监听地址「只认字面量」判定的单元测试（FD-0014）。
 *
 * <p>与重排 Endpoint 的判定同一思路：不做 DNS、不接受前缀或含糊写法。
 * 这里覆盖的正例与反例就是启动期校验的全部依据。</p>
 */
class LoopbackAddressPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = { "127.0.0.1", "127.0.0.2", "127.5.5.5", "127.255.255.255", "::1",
            "0:0:0:0:0:0:0:1", "0000:0000:0000:0000:0000:0000:0000:0001", "[::1]" })
    void acceptsLiteralLoopbackAddresses(String address) {
        assertThat(LoopbackAddressPolicy.isLoopbackLiteral(address)).as("address=%s", address).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = { "0.0.0.0", "::", "10.0.0.1", "192.168.1.10", "203.0.113.7", "8.8.8.8",
            "2001:db8::1", "::2", "localhost", "LOCALHOST", "example.com", "127.example.com",
            "127.0.0.1.attacker.example", "127.5", "127.999.999.999", "127.0.0.1.", "127..0.1",
            "0127.0.0.1", "2130706433", "::ffff:127.0.0.1", "fe80::1%lo0" })
    void rejectsEverythingThatIsNotACompleteLiteral(String address) {
        assertThat(LoopbackAddressPolicy.isLoopbackLiteral(address)).as("address=%s", address).isFalse();
    }

    @Test
    void rejectsNullAndBlank() {
        assertThat(LoopbackAddressPolicy.isLoopbackLiteral(null)).isFalse();
        assertThat(LoopbackAddressPolicy.isLoopbackLiteral("")).isFalse();
        assertThat(LoopbackAddressPolicy.isLoopbackLiteral("   ")).isFalse();
    }

    @Test
    void trimsSurroundingWhitespaceBeforeJudging() {
        assertThat(LoopbackAddressPolicy.isLoopbackLiteral(" 127.0.0.1 ")).isTrue();
    }
}
