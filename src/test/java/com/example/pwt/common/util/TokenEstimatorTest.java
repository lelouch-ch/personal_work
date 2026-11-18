package com.example.pwt.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Token 估算测试（设计文档 11.5）。
 *
 * @author pwt
 */
class TokenEstimatorTest {

    @Test
    void shouldReturnZeroForNullOrEmpty() {
        assertThat(TokenEstimator.estimate(null)).isZero();
        assertThat(TokenEstimator.estimate("")).isZero();
    }

    @Test
    void shouldCostChineseHeavierThanAscii() {
        // 统一按 4 字符折算会低估一半以上，中文段落会在预算已超时后被继续注入
        String chinese = "数据库连接池超时配置";
        String ascii = "database pool timeout";

        assertThat(TokenEstimator.estimate(chinese)).isGreaterThan(TokenEstimator.estimate(ascii));
        assertThat(TokenEstimator.estimate(chinese)).isEqualTo(chinese.length());
    }

    @Test
    void shouldRoundUpNeverDown() {
        // 估算只允许偏高：偏低会让 build_context 撑爆调用方模型的上下文窗口
        assertThat(TokenEstimator.estimate("abc")).isEqualTo(1);
        assertThat(TokenEstimator.estimate("abcdefgh")).isEqualTo(2);
    }

    @Test
    void shouldSumMultipleTexts() {
        assertThat(TokenEstimator.estimateAll(List.of("锁", "lock"))).isEqualTo(2);
        assertThat(TokenEstimator.estimateAll(null)).isZero();
    }
}
