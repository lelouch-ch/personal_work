package com.example.pwt.context.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

/**
 * 评分分项测试（设计文档 11.4）。
 *
 * @author pwt
 */
class ContextWeightsTest {

    private static final Instant NOW = Instant.parse("2026-11-18T00:00:00Z");

    @Test
    void shouldMapImportanceLinearlyOntoUnitRange() {
        assertThat(ContextWeights.importance(1)).isZero();
        assertThat(ContextWeights.importance(5)).isEqualTo(1.0D);
        assertThat(ContextWeights.importance(3)).isEqualTo(0.5D);
        // 越界收敛而不是抛异常：列值有 CHECK 约束，走到这里说明是历史脏数据，不该中断构建
        assertThat(ContextWeights.importance(9)).isEqualTo(1.0D);
        assertThat(ContextWeights.importance(null)).isZero();
    }

    @Test
    void shouldUseNeutralValueWhenConfidenceMissing() {
        assertThat(ContextWeights.confidence(null)).isEqualTo(0.5D);
        assertThat(ContextWeights.confidence(new BigDecimal("0.9300"))).isEqualTo(0.93D);
    }

    @Test
    void shouldDecayRecencyByHalfLife() {
        assertThat(ContextWeights.recency(NOW, NOW)).isEqualTo(1.0D);
        assertThat(ContextWeights.recency(NOW.minus(180, ChronoUnit.DAYS), NOW)).isCloseTo(0.5D,
            org.assertj.core.api.Assertions.within(0.01D));
        // 未来时间（时钟偏差）不能算出大于 1 的分项
        assertThat(ContextWeights.recency(NOW.plus(10, ChronoUnit.DAYS), NOW)).isEqualTo(1.0D);
        assertThat(ContextWeights.recency(null, NOW)).isZero();
    }

    @Test
    void shouldTreatNeverUsedMemoryAsZeroUsageSignal() {
        assertThat(ContextWeights.usageSignal(null, NOW)).isZero();
        assertThat(ContextWeights.usageSignal(NOW.minus(60, ChronoUnit.DAYS), NOW)).isCloseTo(0.5D,
            org.assertj.core.api.Assertions.within(0.01D));
    }

    @Test
    void shouldGiveFullProjectMatchWhenRequestHasNoProject() {
        // 请求未指定项目时项目不是筛选条件，不该让任何候选因此丢分
        assertThat(ContextWeights.projectMatch(null, null)).isEqualTo(1.0D);
        assertThat(ContextWeights.projectMatch("p-1", null)).isEqualTo(1.0D);
        assertThat(ContextWeights.projectMatch(null, "p-1")).isZero();
        assertThat(ContextWeights.projectMatch("p-1", "p-1")).isEqualTo(1.0D);
        assertThat(ContextWeights.projectMatch("p-2", "p-1")).isZero();
    }

    @Test
    void shouldClampRelevanceIntoUnitRange() {
        assertThat(ContextWeights.relevance(-0.5D)).isZero();
        assertThat(ContextWeights.relevance(3.0D)).isEqualTo(1.0D);
    }

    @Test
    void shouldWeightPartsAsDocumented() {
        ContextWeights.Parts full = new ContextWeights.Parts(1.0D, 1.0D, 1.0D, 1.0D, 1.0D, 1.0D);
        assertThat(ContextWeights.score(full)).isEqualTo(1.0D);

        ContextWeights.Parts onlyRelevance = new ContextWeights.Parts(1.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D);
        assertThat(ContextWeights.score(onlyRelevance)).isEqualTo(0.35D);

        assertThat(new ContextWeights.Parts(0, 0, 0, 0, 0, 0)).isNotNull();
        assertThat(ContextWeights.IMPORTANCE).isEqualTo(0.20D);
        assertThat(ContextWeights.CONFIDENCE).isEqualTo(0.15D);
        assertThat(ContextWeights.RECENCY).isEqualTo(0.10D);
        assertThat(ContextWeights.PROJECT_MATCH).isEqualTo(0.10D);
        assertThat(ContextWeights.USAGE_SIGNAL).isEqualTo(0.10D);
    }

    @Test
    void shouldScoreLikeFallbackAsFixedLowRelevance() {
        assertThat(ContextWeights.likeFallbackRelevance()).isLessThan(1.0D);
        assertThat(ContextWeights.likeFallbackRelevance()).isGreaterThan(0.0D);
    }
}
