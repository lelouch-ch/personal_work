package com.example.pwt.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 脱敏测试（设计文档 15.1 第 3 条）。
 *
 * @author pwt
 */
class MaskingTest {

    @Test
    void shouldMaskCredentialAssignments() {
        assertThat(Masking.mask("Authorization: Bearer abcdefghijklmnop"))
            .doesNotContain("abcdefghijklmn");
        assertThat(Masking.mask("api_key=sk-abcdefghijklmnop1234"))
            .doesNotContain("abcdefghijklmnop");
    }

    @Test
    void shouldMaskJwtAndOwnTokenPrefix() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJvd25lciJ9.abcdefghijklmnopqrstuvwxyz";
        assertThat(Masking.mask(jwt)).doesNotContain("abcdefghijklmnopqrstuvwxyz");
        assertThat(Masking.mask("key pwt_0123456789abcdef0123456789abcdef end"))
            .doesNotContain("0123456789abcdef");
    }

    @Test
    void shouldKeepOrdinaryChineseText() {
        String text = "完成上传链路的重试与幂等改造，涉及 12 个接口。";

        assertThat(Masking.mask(text)).isEqualTo(text);
    }

    @Test
    void shouldTruncateOverlongInput() {
        String result = Masking.mask("长".repeat(1500));

        assertThat(result).hasSizeLessThanOrEqualTo(1020);
        assertThat(result).endsWith("...(truncated)");
    }

    @Test
    void shouldPassThroughNull() {
        assertThat(Masking.mask(null)).isNull();
        assertThat(Masking.mask("")).isEmpty();
    }
}
