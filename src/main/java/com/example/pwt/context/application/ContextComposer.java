package com.example.pwt.context.application;

import com.example.pwt.common.util.TokenEstimator;
import com.example.pwt.context.ContextProperties;
import com.example.pwt.context.domain.ContextWeights;
import com.example.pwt.context.domain.Similarity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 上下文组装：排序、去相似、按优先级分配 Token 预算（设计文档 11.3 第 5～7 步、11.4、11.5）。
 *
 * <p>纯函数，不碰数据库也不碰 Spring：输入候选与预算，输出注入清单与被丢弃的说明。
 * M5 最难验证的正是“预算不足时先丢谁”，把它从数据访问里剥出来才能用给定输入直接断言。
 *
 * @author pwt
 */
final class ContextComposer {

    /** 一条注入项自身的结构开销估算（字段名、引用 ID、分隔符），单位 token。 */
    private static final int ITEM_OVERHEAD_TOKENS = 16;

    private ContextComposer() {
    }

    /**
     * 组装记忆段。
     *
     * @param candidates   召回候选，顺序不敏感
     * @param fullText     是否由全文检索命中；false 表示 {@code LIKE} 兜底，相关度记固定低分
     * @param projectId    请求项目，可为空
     * @param now          基准时刻
     * @param tokenBudget  留给记忆段的预算
     * @param properties   配额配置
     * @return 组装结果
     */
    static Result compose(List<ContextCandidate> candidates, boolean fullText, UUID projectId,
                          java.time.Instant now, int tokenBudget, ContextProperties properties) {
        Map<UUID, Double> scores = score(candidates, fullText, projectId, now);
        List<Scored> ranked = candidates.stream()
            .map(candidate -> new Scored(candidate, scores.getOrDefault(candidate.id(), 0.0D)))
            .sorted(Comparator.comparingDouble(Scored::score).reversed()
                .thenComparing(item -> item.candidate().id().toString()))
            .toList();

        List<Scored> deduplicated = dropDuplicates(ranked);
        Map<String, Integer> quotas = quotasOf(properties);
        Map<String, Integer> used = new HashMap<>();
        Map<String, Integer> droppedByQuota = new LinkedHashMap<>();

        List<ContextPacket.Item> selected = new ArrayList<>();
        int spent = 0;
        boolean truncated = false;
        for (Scored item : deduplicated) {
            String type = item.candidate().type();
            int limit = quotas.getOrDefault(type, Integer.MAX_VALUE);
            if (used.getOrDefault(type, 0) >= limit) {
                droppedByQuota.merge(type, 1, Integer::sum);
                continue;
            }
            int cost = cost(item);
            if (spent + cost > tokenBudget) {
                // 放不下就跳过它继续试下一条，而不是整段停住：
                // 最相关的那条常常最长（未写摘要时注入的是正文），一旦它超预算就全段清空，
                // 会让 Agent 在明明还有 383 token 可用时空手而归。
                // 11.5 的要求是“优先减少低相关补充项”，不是“最高分项超预算就全部放弃”。
                truncated = true;
                continue;
            }
            spent += cost;
            used.merge(type, 1, Integer::sum);
            selected.add(toPacketItem(item));
        }
        return new Result(selected, spent, truncated, droppedByQuota, deduplicated.size());
    }

    /**
     * 估算一条注入项的开销。
     *
     * @param item 候选
     * @return token 数
     */
    static int cost(Scored item) {
        return TokenEstimator.estimate(item.candidate().text()) + ITEM_OVERHEAD_TOKENS;
    }

    /**
     * 加权评分（11.4）。
     *
     * @param candidates 候选
     * @param fullText   是否全文检索命中
     * @param projectId  请求项目
     * @param now        基准时刻
     * @return 记忆 ID 到总分
     */
    static Map<UUID, Double> score(List<ContextCandidate> candidates, boolean fullText, UUID projectId,
                                   java.time.Instant now) {
        double maxRelevance = candidates.stream()
            .map(ContextCandidate::relevance)
            .filter(java.util.Objects::nonNull)
            .mapToDouble(Double::doubleValue)
            .max()
            .orElse(0.0D);
        Map<UUID, Double> scores = new LinkedHashMap<>();
        for (ContextCandidate candidate : candidates) {
            double relevance = fullText && maxRelevance > 0.0D && candidate.relevance() != null
                ? candidate.relevance() / maxRelevance
                : ContextWeights.likeFallbackRelevance();
            ContextWeights.Parts parts = new ContextWeights.Parts(
                ContextWeights.relevance(relevance),
                ContextWeights.importance(candidate.importance()),
                ContextWeights.confidence(candidate.confidence()),
                ContextWeights.recency(candidate.createdAt(), now),
                ContextWeights.projectMatch(stringId(candidate.projectId()), stringId(projectId)),
                ContextWeights.usageSignal(candidate.lastUsedAt(), now));
            scores.put(candidate.id(), ContextWeights.score(parts));
        }
        return scores;
    }

    /**
     * 去除与已保留项高度相似的候选，保留分数更高的那条（11.3 第 6 步）。
     *
     * @param ranked 已按分数倒序
     * @return 去重结果
     */
    static List<Scored> dropDuplicates(List<Scored> ranked) {
        List<Scored> kept = new ArrayList<>(ranked.size());
        for (Scored item : ranked) {
            boolean duplicate = kept.stream()
                .anyMatch(existing -> Similarity.duplicate(existing.candidate().text(), item.candidate().text()));
            if (!duplicate) {
                kept.add(item);
            }
        }
        return kept;
    }

    private static Map<String, Integer> quotasOf(ContextProperties properties) {
        Map<String, Integer> quotas = new LinkedHashMap<>();
        if (properties != null) {
            for (ContextProperties.TypeQuota quota : properties.getTypeQuotas()) {
                if (quota != null && quota.type() != null) {
                    quotas.put(quota.type(), quota.limit());
                }
            }
        }
        return quotas;
    }

    private static ContextPacket.Item toPacketItem(Scored item) {
        ContextCandidate candidate = item.candidate();
        return new ContextPacket.Item(candidate.id(), candidate.type(), candidate.text(),
            candidate.confidence(), round(item.score()), candidate.sourceRefs(), candidate.riskLevel());
    }

    private static double round(double value) {
        return Math.round(value * 10000.0D) / 10000.0D;
    }

    private static String stringId(UUID id) {
        return id == null ? null : id.toString();
    }

    /**
     * 候选及其分数。
     *
     * @param candidate 候选
     * @param score     加权总分，0~1
     */
    record Scored(ContextCandidate candidate, double score) {
    }

    /**
     * 记忆段组装结果。
     *
     * @param items          注入项，按分数倒序
     * @param tokensUsed     记忆段实际占用的估算 token
     * @param truncated      是否因预算截断
     * @param droppedByQuota 因类型配额未注入的条数
     * @param deduplicated   去重后的候选数
     */
    record Result(List<ContextPacket.Item> items, int tokensUsed, boolean truncated,
                  Map<String, Integer> droppedByQuota, int deduplicated) {
    }
}
