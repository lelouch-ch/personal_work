package com.example.pwt.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 凭证生成与摘要测试。
 *
 * @author pwt
 */
class SecretsTest {

    @Test
    void shouldGeneratePrefixedHighEntropyTokens() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            tokens.add(Secrets.generateToken());
        }

        assertThat(tokens).hasSize(1000);
        assertThat(tokens).allMatch(token -> token.startsWith(Secrets.TOKEN_PREFIX));
        // 前缀 + 32 字节的十六进制表示
        assertThat(tokens).allMatch(token -> token.length() == Secrets.TOKEN_PREFIX.length() + 64);
    }

    @Test
    void shouldHashDeterministicallyForLookup() {
        String token = Secrets.generateToken();

        assertThat(Secrets.hash(token)).isEqualTo(Secrets.hash(token));
        assertThat(Secrets.hash(token)).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void shouldProduceDifferentHashWithPepper() {
        String token = Secrets.generateToken();

        assertThat(Secrets.hash(token, "pepper-a")).isNotEqualTo(Secrets.hash(token, "pepper-b"));
        assertThat(Secrets.hash(token, "pepper-a")).isNotEqualTo(Secrets.hash(token));
    }

    @Test
    void shouldTreatBlankPepperAsAbsent() {
        String token = Secrets.generateToken();

        assertThat(Secrets.hash(token, "  ")).isEqualTo(Secrets.hash(token));
        assertThat(Secrets.hash(token, null)).isEqualTo(Secrets.hash(token));
    }

    @Test
    void shouldNeverExposeFullTokenInPreview() {
        String token = Secrets.generateToken();

        String preview = Secrets.displayPrefix(token);

        assertThat(preview).hasSize(12).isEqualTo(token.substring(0, 12));
        assertThat(token).hasSizeGreaterThan(preview.length());
    }

    @Test
    void shouldRejectNullSecret() {
        assertThatThrownBy(() -> Secrets.hash(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("secret must not be null");
    }
}
