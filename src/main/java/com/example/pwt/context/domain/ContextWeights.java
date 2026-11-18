package com.example.pwt.context.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * 上下文相关性评分（设计文档 11.4）。
 *
 * <p>加权求和而不是模型打分：每项都能用列值或检索分数解释，Agent 拿到
 * {@code relevance_score} 之后还能追问“为什么是这条”，后续换成模型排序也不影响接口。
 *
 * <p>约束类记忆不走排序，由 {@code ContextBuilderService} 作为硬规则优先注入。
 *
 * @author pwt
 */
public final class ContextWeights {

    /** 关键词相关度权重。 */
    public static final double RELEVANCE = 0.35D;
    /** 重要度权重。 */
    public static final double IMPORTANCE = 0.20D;
    /** 置信度权重。 */
    public static final double CONFIDENCE = 0.15D;
    /** 新近度权重。 */
    public static final double RECENCY = 0.10D;
    /** 项目匹配权重。 */
    public static final double PROJECT_MATCH = 0.10D;
    /** 使用信号权重。 */
    public static final double USAGE_SIGNAL = 0.10D;

    /** 全文检索未命中、由 LIKE 兜底命中时的相关度分项，固定低分（11.4）。 */
    public static final double LIKE_FALLBACK_RELEVANCE = 0.30D;

    /** LIKE 兜底命中时的相似度上限：只要判定为相似就给满值，粗粒度信号交给其它分项区分。 */
    private static final double LIKE_SIMILARITY_CEILING = 1.0D;

    /** 新近度半衰期：过了这个时长，分项衰减到一半。 */
    private static final Duration RECENCY_HALF_LIFE = Duration.ofDays(180);

    /** 使用信号半衰期。 */
    private static final Duration USAGE_HALF_LIFE = Duration.ofDays(60);

    /** 置信度缺失时的中性取值：不奖励也不惩罚未标注置信度的历史数据。 */
    private static final double UNKNOWN_CONFIDENCE = 0.5D;

    /** 重要度取值区间，与 {@code pt_memory.importance} 的 CHECK 一致。 */
    private static final int MIN_IMPORTANCE = 1;
    private static final int MAX_IMPORTANCE = 5;

    private ContextWeights() {
    }

    /**
     * 加权求和。
     *
     * @param parts 已归一化到 0~1 的各分项
     * @return 总分，同样落在 0~1
     */
    public static double score(Parts parts) {
        return RELEVANCE * parts.relevance()
            + IMPORTANCE * parts.importance()
            + CONFIDENCE * parts.confidence()
            + RECENCY * parts.recency()
            + PROJECT_MATCH * parts.projectMatch()
            + USAGE_SIGNAL * parts.usageSignal();
    }

    /**
     * 重要度分项：把 1~5 线性映射到 0~1。
     *
     * @param importance 列值，越界或缺失时收敛到区间端点
     * @return 0~1
     */
    public static double importance(Integer importance) {
        if (importance == null) {
            return (MIN_IMPORTANCE - 1) / (double) (MAX_IMPORTANCE - 1);
        }
        int value = Math.max(MIN_IMPORTANCE, Math.min(MAX_IMPORTANCE, importance));
        return (value - MIN_IMPORTANCE) / (double) (MAX_IMPORTANCE - MIN_IMPORTANCE);
    }

    /**
     * 置信度分项，缺失时取中性值。
     *
     * @param confidence 列值
     * @return 0~1
     */
    public static double confidence(BigDecimal confidence) {
        if (confidence == null) {
            return UNKNOWN_CONFIDENCE;
        }
        return Math.max(0.0D, Math.min(1.0D, confidence.doubleValue()));
    }

    /**
     * 新近度分项：按半衰期指数衰减。
     *
     * <p>用衰减而不是线性截断：线性截断会让“恰好越过阈值”的记忆分数突降为 0，
     * 排序因此在阈值附近抖动；衰减保证时间差很小时分数差也很小。
     *
     * @param createdAt 创建时间，缺失按最早处理
     * @param now       基准时刻
     * @return 0~1
     */
    public static double recency(Instant createdAt, Instant now) {
        return decay(createdAt, now, RECENCY_HALF_LIFE);
    }

    /**
     * 使用信号分项：距最近一次被上下文使用越近越高。
     *
     * <p>从未使用过（{@code last_used_at} 为空）记 0，让新记忆靠新近度与内容分竞争，
     * 而不是靠一个凭空假定的默认值插队。
     *
     * @param lastUsedAt 最近使用时间，可为空
     * @param now        基准时刻
     * @return 0~1
     */
    public static double usageSignal(Instant lastUsedAt, Instant now) {
        return decay(lastUsedAt, now, USAGE_HALF_LIFE);
    }

    /**
     * 项目匹配分项。
     *
     * <p>请求未指定项目时统一记满值：此时项目不是筛选条件，不该让任何候选因此丢分。
     * 记忆没有项目归属时记 0，由评分自然降权，而不是在 SQL 层直接排除。
     *
     * @param memoryProjectId 记忆归属项目，可为空
     * @param requestProjectId 请求项目，可为空
     * @return 0~1
     */
    public static double projectMatch(String memoryProjectId, String requestProjectId) {
        if (requestProjectId == null) {
            return 1.0D;
        }
        return requestProjectId.equals(memoryProjectId) ? 1.0D : 0.0D;
    }

    /**
     * 相似度分项，取值即布尔检索相关度按候选集最大值归一化后的结果。
     *
     * @param normalizedRelevance 归一化相关度
     * @return 0~1
     */
    public static double relevance(double normalizedRelevance) {
        return Math.max(0.0D, Math.min(1.0D, normalizedRelevance));
    }

    /**
     * LIKE 兜底路径的相关度取值，固定低分。
     *
     * @return 固定低分
     */
    public static double likeFallbackRelevance() {
        return LIKE_FALLBACK_RELEVANCE;
    }

    /**
     * LIKE 兜底路径的相似度取值。
     *
     * @return 相似度
     */
    public static double likeSimilarity() {
        return LIKE_SIMILARITY_CEILING;
    }

    private static double decay(Instant from, Instant now, Duration halfLife) {
        if (from == null || now == null) {
            return 0.0D;
        }
        double elapsedMillis = Math.max(0.0D, Duration.between(from, now).toMillis());
        return Math.pow(0.5D, elapsedMillis / (double) halfLife.toMillis());
    }

    /**
     * 评分分项。
     *
     * @param relevance    关键词相关度
     * @param importance   重要度
     * @param confidence   置信度
     * @param recency      新近度
     * @param projectMatch 项目匹配
     * @param usageSignal  使用信号
     */
    public record Parts(double relevance, double importance, double confidence, double recency,
                        double projectMatch, double usageSignal) {
    }
}
