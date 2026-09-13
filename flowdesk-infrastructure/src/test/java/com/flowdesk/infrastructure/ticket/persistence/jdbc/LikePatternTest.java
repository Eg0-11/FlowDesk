package com.flowdesk.infrastructure.ticket.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * LIKE 模式转义测试：{@code %}、{@code _} 与转义符自身都必须按普通字符处理。
 */
class LikePatternTest {

    @Test
    void escapesTheTwoWildcardsAndTheEscapeCharacterItself() {
        assertThat(LikePattern.escape("%")).isEqualTo("!%");
        assertThat(LikePattern.escape("_")).isEqualTo("!_");
        assertThat(LikePattern.escape("!")).isEqualTo("!!");
    }

    @Test
    void leavesOrdinaryTextUntouched() {
        assertThat(LikePattern.escape("无法登录")).isEqualTo("无法登录");
        assertThat(LikePattern.escape("abc123")).isEqualTo("abc123");
        assertThat(LikePattern.escape("")).isEmpty();
        assertThat(LikePattern.escape("50% off")).isEqualTo("50!% off");
    }

    @Test
    void escapesMixedTextInOnePass() {
        // 转义符先被翻倍，因此后续插入的转义符不会被重复处理
        assertThat(LikePattern.escape("a%b_c!d")).isEqualTo("a!%b!_c!!d");
        assertThat(LikePattern.escape("%_!")).isEqualTo("!%!_!!");
        assertThat(LikePattern.escape("!!!")).isEqualTo("!!!!!!");
    }

    @Test
    void buildsAContainsPattern() {
        assertThat(LikePattern.contains("登录")).isEqualTo("%登录%");
        assertThat(LikePattern.contains("100%")).isEqualTo("%100!%%");
        assertThat(LikePattern.contains("_")).isEqualTo("%!_%");
        assertThat(LikePattern.contains("!")).isEqualTo("%!!%");
        assertThat(LikePattern.contains("")).isEqualTo("%%");
    }

    @Test
    void escapeClauseMatchesTheEscapeCharacterUsedByTheEscaper() {
        assertThat(LikePattern.ESCAPE_CLAUSE)
                .isEqualTo(" ESCAPE '" + LikePattern.ESCAPE_CHARACTER + "'");
    }
}
